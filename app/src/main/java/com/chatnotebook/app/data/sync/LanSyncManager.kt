package com.chatnotebook.app.data.sync

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.chatnotebook.app.data.AttachmentStore
import com.chatnotebook.app.data.PersistentChatRepository
import com.chatnotebook.app.data.RecordRepository
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class LanDevice(val fingerprint: String, val name: String, val deviceCode: String = "")

internal data class LanSyncStatus(
    val directoryReady: Boolean = false,
    val peers: List<LanDevice> = emptyList(),
    val paused: Boolean = false,
    val qr: PairingOffer? = null,
    val joining: Boolean = false,
    val message: String = "",
    val lastSuccess: Long? = null,
)

internal class LanSyncManager(
    private val context: Context,
    private val mutations: Mutex,
) {
    private data class Storage(val records: RecordRepository, val attachments: AttachmentStore)

    /** 同步会话以“指纹 + 是否已有对端地址”为键，地址自愈时不必重启会话。 */
    private data class PeerKey(val fingerprint: String, val addressed: Boolean)

    private sealed interface Pairing {
        data class Show(val ticket: PairingTicket, val server: Inet4Listener, val lan: LanInterface) : Pairing
        data class Scan(val offer: PairingOffer) : Pairing
    }

    private val prefs = context.getSharedPreferences("local_network_sync", Context.MODE_PRIVATE)
    private val trustStore = LanTrustStore(prefs)
    private val trust = MutableStateFlow(trustStore.read())
    private val localDeviceCode by lazy { deviceCodeOf(context) }
    private val localName by lazy { deviceNameOf(context) }
    private val localModel = Build.MODEL.orEmpty()
    private val foreground = MutableStateFlow(false)
    private val storage = MutableStateFlow<Storage?>(null)
    private val running = MutableStateFlow(0)
    private val paused = MutableStateFlow(prefs.getBoolean("paused", false))
    private val pairing = MutableStateFlow<Pairing?>(null)
    private val sessionLock = Mutex()
    private val _status = MutableStateFlow(LanSyncStatus(
        peers = trust.value.devices(),
        paused = paused.value,
        lastSuccess = prefs.getLong("last_success", 0).takeIf { it > 0 },
    ))
    val status = _status.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(foreground, storage, pairing) { visible, store, handshaking ->
                if (visible && store != null && handshaking != null) store to handshaking else null
            }.collectLatest { active ->
                if (active != null) {
                    running.update { it + 1 }
                    try {
                        guarded("配对连接失败") { runPairing(active.first, active.second) }
                    } finally {
                        running.update { it - 1 }
                    }
                }
            }
        }
        scope.launch {
            combine(foreground, storage, trust, paused) { visible, store, devices, stopped ->
                if (visible && store != null && devices.isNotEmpty() && !stopped) {
                    store to devices.map { PeerKey(it.fingerprint, it.host.isNotBlank()) }
                } else {
                    null
                }
            }.distinctUntilChanged().collectLatest { active ->
                if (active != null) {
                    running.update { it + 1 }
                    try {
                        coroutineScope {
                            active.second.forEach { peer ->
                                launch {
                                    try {
                                        syncPeer(active.first, peer.fingerprint)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        message("局域网同步失败：${error.message ?: "请检查设备、网络和记录目录"}")
                                    }
                                }
                            }
                        }
                    } finally {
                        running.update { it - 1 }
                    }
                }
            }
        }
    }

    fun foreground(visible: Boolean) {
        foreground.value = visible
        if (!visible) {
            cancelPairing()
            if (trust.value.isNotEmpty() && !paused.value) message("等待双方打开应用")
        }
    }

    fun directory(records: RecordRepository?, attachments: AttachmentStore?) {
        if (records == null || attachments == null) {
            storage.value = null
            cancelPairing()
            _status.update { it.copy(directoryReady = false) }
        } else {
            storage.value = Storage(records, attachments)
            _status.update { it.copy(directoryReady = true) }
        }
    }

    suspend fun detachDirectory() {
        directory(null, null)
        running.first { it == 0 }
    }

    suspend fun switchDirectory() {
        detachDirectory()
        prefs.edit().remove("last_success").apply()
        _status.update { it.copy(lastSuccess = null, message = "记录目录已切换") }
    }

    fun showPairing() = scope.launch {
        guarded("无法生成配对二维码") {
            if (storage.value == null) return@guarded
            val transport = LanTransport(context)
            val lan = withTimeoutOrNull(10_000) { transport.interfaces().filterNotNull().first() }
            if (lan == null) {
                message("请先连接局域网")
                return@guarded
            }
            if (lan.address.address !is Inet4Address) {
                message("当前网络没有可用的 IPv4 地址，请检查局域网连接")
                return@guarded
            }
            val identity = LanIdentity.load()
            val server = transport.bind(lan, 0)
            val ticket = PairingTicket(
                identity.fingerprint,
                lan.address.address.hostAddress.orEmpty(),
                server.localPort,
                SystemClock::elapsedRealtime,
            )
            (pairing.value as? Pairing.Show)?.server?.let { runCatching { it.close() } }
            pairing.value = Pairing.Show(ticket, server, lan)
            _status.update { it.copy(qr = ticket.offer, joining = false, message = "等待另一台设备扫描") }
            delay(120_000)
            if ((pairing.value as? Pairing.Show)?.ticket === ticket) {
                cancelPairing()
                message("配对二维码已过期")
            }
        }
    }

    fun join(offer: PairingOffer) {
        if (storage.value == null) return
        (pairing.value as? Pairing.Show)?.let { runCatching { it.server.close() } }
        pairing.value = Pairing.Scan(offer)
        _status.update { it.copy(qr = null, joining = true, message = "正在连接另一台设备") }
        scope.launch {
            delay(120_000)
            if ((pairing.value as? Pairing.Scan)?.offer == offer) {
                cancelPairing()
                message("无法连接该设备，请确认两台设备在同一局域网后重试")
            }
        }
    }

    fun cancelPairing() {
        val show = pairing.value as? Pairing.Show
        show?.ticket?.cancel()
        show?.server?.let { runCatching { it.close() } }
        pairing.value = null
        _status.update { it.copy(qr = null, joining = false) }
    }

    fun pause(stopped: Boolean) {
        paused.value = stopped
        prefs.edit().putBoolean("paused", stopped).apply()
        _status.update {
            it.copy(
                paused = stopped,
                message = when {
                    stopped -> "同步已暂停"
                    trust.value.isEmpty() -> "等待配对设备"
                    else -> "等待自己的设备"
                },
            )
        }
    }

    fun unlink(fingerprint: String) = scope.launch {
        guarded("无法移除设备") {
            val remaining = trust.value.filterNot { it.fingerprint == fingerprint }
            if (remaining.size == trust.value.size) return@guarded
            updateTrust(remaining)
            if (remaining.isEmpty()) {
                pause(false)
                prefs.edit().remove("last_success").apply()
                _status.update { it.copy(lastSuccess = null, message = "已移除设备") }
            } else {
                _status.update { it.copy(message = "已移除设备") }
            }
        }
    }

    fun message(text: String) {
        _status.update { it.copy(message = text) }
    }

    private suspend fun runPairing(store: Storage, handshaking: Pairing) {
        var backoff = SESSION_RETRY_MIN_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            val identity = loadIdentity()
            val ended = identity != null && guarded("配对连接失败") {
                val transport = LanTransport(context)
                foregroundLanSessions(
                    MutableStateFlow(true), foreground, transport.interfaces(),
                    onFailure = { failure("配对连接失败", it) },
                ) { lan ->
                    when (handshaking) {
                        is Pairing.Show -> host(transport, lan, store, identity, handshaking)
                        is Pairing.Scan -> join(transport, lan, store, identity, handshaking.offer)
                    }
                }.collect {}
            }
            backoff = if (ended) SESSION_RETRY_MIN_MS else minOf(backoff * 2, SESSION_RETRY_MAX_MS)
            delay(backoff)
        }
    }

    private suspend fun syncPeer(store: Storage, fingerprint: String) {
        var backoff = SESSION_RETRY_MIN_MS
        while (trust.value.any { it.fingerprint == fingerprint }) {
            currentCoroutineContext().ensureActive()
            val identity = loadIdentity()
            val ended = identity != null && guarded("局域网同步失败") {
                store.records.startSyncTracking(identity.fingerprint)
                val transport = LanTransport(context)
                foregroundLanSessions(
                    MutableStateFlow(true), foreground, transport.interfaces(),
                    onFailure = { failure("局域网同步失败", it) },
                ) { lan ->
                    synchronize(transport, lan, store, identity, fingerprint)
                }.collect {}
            }
            backoff = if (ended) SESSION_RETRY_MIN_MS else minOf(backoff * 2, SESSION_RETRY_MAX_MS)
            delay(backoff)
        }
    }

    private suspend fun loadIdentity(): LanIdentity? = try {
        LanIdentity.load().also(::repairTrust)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        message("无法读取设备身份：${error.message ?: "请重新打开应用"}")
        null
    }

    private fun updateTrust(trusts: List<LanTrust>) {
        trustStore.replace(trusts)
        trust.value = trusts
        _status.update { it.copy(peers = trusts.devices()) }
    }

    private fun repairTrust(identity: LanIdentity) {
        val current = trust.value
        if (current.isEmpty()) return
        val repaired = current.repair(localDeviceCode, identity.fingerprint)
        if (repaired.trusts != current) updateTrust(repaired.trusts)
        if (repaired.identityChanged && repaired.trusts.isNotEmpty()) {
            message("本机设备身份已更新，请重新扫码配对")
        }
    }

    private fun List<LanTrust>.devices(): List<LanDevice> =
        map { LanDevice(it.fingerprint, it.name, it.deviceCode) }

    private suspend fun host(
        transport: LanTransport, lan: LanInterface, store: Storage, identity: LanIdentity, show: Pairing.Show,
    ) {
        if (!lan.address.address.address.contentEquals(show.lan.address.address.address)) {
            cancelPairing()
            message("网络已变化，请重新生成二维码")
            return
        }
        val server = if (show.server.isClosed) transport.bind(lan, show.server.localPort) else show.server
        transport.listen(lan, server) { socket ->
            guarded("配对未完成") {
                var saved: LanTrust? = null
                try {
                    LanPairing(identity, localName, localModel, localDeviceCode).accept(socket, show.ticket) { peer ->
                        saved = persistPeer(store, identity, peer)
                    }
                } finally {
                    saved?.let { activatePeer() }
                }
            }
        }
    }

    private suspend fun join(transport: LanTransport, lan: LanInterface, store: Storage, identity: LanIdentity, offer: PairingOffer) {
        val address = try {
            InetAddress.getByName(offer.address)
        } catch (_: Exception) {
            message("无法识别配对地址，请重新生成二维码")
            delay(SESSION_RETRY_MIN_MS)
            return
        }
        if (!lan.canReach(address)) {
            message("请确认两台设备连接同一局域网")
            delay(SESSION_RETRY_MIN_MS)
            return
        }
        message("正在连接另一台设备")
        var paired: PairDevice? = null
        val connected = try {
            transport.connect(lan, address, offer.port) { socket ->
                paired = LanPairing(identity, localName, localModel, localDeviceCode).join(socket, offer)
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!connected) {
            message("无法连接该设备，请确认两台设备在同一局域网后重试")
            delay(SESSION_RETRY_MIN_MS)
            return
        }
        paired?.let {
            persistPeer(store, identity, it)
            activatePeer()
        }
    }

    private suspend fun persistPeer(store: Storage, identity: LanIdentity, peer: PairDevice): LanTrust {
        currentCoroutineContext().ensureActive()
        check(storage.value === store && foreground.value && pairing.value != null) { "配对已取消" }
        val merged = trust.value.withPaired(peer, localDeviceCode, identity.fingerprint)
        updateTrust(merged)
        return merged.last()
    }

    private fun activatePeer() {
        cancelPairing()
        pause(false)
        _status.update { it.copy(message = "已加入自己的设备") }
    }

    private suspend fun synchronize(
        transport: LanTransport, lan: LanInterface, store: Storage, identity: LanIdentity, fingerprint: String,
    ) {
        val library = SyncLibrary(
            store.records,
            SyncAttachments(store.attachments, File(context.cacheDir, "lan-sync")),
            mutations,
        )
        val protocol = LanSyncProtocol(library)
        val initiator = identity.fingerprint < fingerprint
        val port = lanPort(identity.fingerprint, fingerprint)
        val server = transport.bind(lan, port)
        coroutineScope {
            if (initiator) {
                launch {
                    var last: Pair<Long, Long>? = null
                    var backoff = 650L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val peerHost = trust.value.firstOrNull { it.fingerprint == fingerprint }?.host.orEmpty()
                        val address = peerHost.takeIf { it.isNotBlank() }?.let {
                            runCatching { InetAddress.getByName(it) }.getOrNull()
                        }
                        if (address == null) {
                            message("请重新扫码配对以恢复同步")
                            delay(SESSION_RETRY_MAX_MS)
                            continue
                        }
                        if (!lan.canReach(address)) {
                            message("对端不在当前局域网，请重新扫码配对")
                            delay(SESSION_RETRY_MAX_MS)
                            continue
                        }
                        val generation = store.records.recordGeneration.value
                        val succeeded = guarded("局域网同步失败") {
                            transport.connect(lan, address, port) { socket ->
                                sessionLock.withLock {
                                    identity.secure(socket, true, fingerprint).use {
                                        val frames = LanFrames(it)
                                        val local = library.generation()
                                        frames.write(SyncEpoch(local))
                                        val remote = frames.read<SyncEpoch>()
                                        remote.requireCompatible()
                                        val epochs = local to remote.generation
                                        frames.write(epochs != last)
                                        if (epochs != last) {
                                            message("正在同步")
                                            val transfer = protocol.exchange(frames, initiator = true)
                                            last = epochs
                                            success(transfer)
                                        }
                                    }
                                }
                            }
                        }
                        backoff = if (succeeded) 650L else minOf(backoff * 2, 10_000L)
                        if (succeeded) withTimeoutOrNull(backoff) { store.records.recordGeneration.first { it != generation } }
                        else delay(backoff)
                    }
                }
            }
            transport.listen(lan, server) { socket ->
                if (!initiator) handlePaired(socket, identity, fingerprint, library, protocol)
            }
        }
    }

    private suspend fun handlePaired(
        socket: Socket, identity: LanIdentity, fingerprint: String, library: SyncLibrary, protocol: LanSyncProtocol,
    ) {
        val secure = try { identity.secure(socket, false, fingerprint) } catch (_: javax.net.ssl.SSLException) {
            return
        } catch (_: java.io.IOException) {
            return
        }
        sessionLock.withLock {
            secure.use {
                rememberHost(fingerprint, socket.inetAddress.hostAddress.orEmpty())
                guarded("局域网同步失败") {
                    val frames = LanFrames(it)
                    val remote = frames.read<SyncEpoch>()
                    remote.requireCompatible()
                    frames.write(SyncEpoch(library.generation()))
                    if (frames.read<Boolean>()) {
                        message("正在同步")
                        success(protocol.exchange(frames, initiator = false))
                    }
                }
            }
        }
    }

    /** 入站连接已通过指纹认证，用来源地址刷新对端地址，实现地址变化后的自愈。 */
    private fun rememberHost(fingerprint: String, host: String) {
        if (host.isBlank()) return
        val current = trust.value
        val target = current.firstOrNull { it.fingerprint == fingerprint } ?: return
        if (target.host == host) return
        updateTrust(current.map { if (it.fingerprint == fingerprint) it.copy(host = host) else it })
    }

    private fun success(transfer: SyncTransfer) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_success", now).apply()
        val summary = if (transfer.notes == 0 && transfer.attachmentBytes == 0L) "已同步" else
            "已接收 ${transfer.notes} 个对话，${transfer.attachmentBytes} 字节附件"
        _status.update { it.copy(lastSuccess = now, message = summary) }
        // 提醒可能来自另一台设备，同步完成后统一重排闹钟。
        scope.launch { PersistentChatRepository.shared(context).rescheduleReminders() }
    }

    private fun failure(fallback: String, error: Throwable) {
        message("$fallback：${error.message ?: "请检查设备、网络和记录目录"}")
    }

    private suspend fun guarded(fallback: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        failure(fallback, error)
        false
    }
}
