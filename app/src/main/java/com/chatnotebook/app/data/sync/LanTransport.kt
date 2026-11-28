package com.chatnotebook.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TIMEOUT_MS = 8_000

internal const val SESSION_RETRY_MIN_MS = 500L
internal const val SESSION_RETRY_MAX_MS = 10_000L

/** 同步端口范围，避开系统临时端口段，减少与系统分配端口的冲突。 */
internal const val LAN_PORT_BASE = 20_000
internal const val LAN_PORT_COUNT = 9_000

internal data class LanInterface(val network: Network, val address: LinkAddress) {
    fun canReach(peer: InetAddress): Boolean =
        peer != address.address && !peer.isAnyLocalAddress && !peer.isLoopbackAddress && !peer.isMulticastAddress &&
            sameSubnet(address.address.address, peer.address, address.prefixLength)
}

/**
 * 一对已配对设备共用的同步端口：两个指纹按固定顺序参与哈希，两端算出同一个端口，
 * 无需 mDNS 发现即可直连。
 */
internal fun lanPort(first: String, second: String): Int {
    val pair = listOf(first, second).sorted()
    return LAN_PORT_BASE + sha256("lan-port:${pair[0]}:${pair[1]}".toByteArray()).take(4).toInt(16) % LAN_PORT_COUNT
}

internal fun sameSubnet(local: ByteArray, remote: ByteArray, prefixLength: Int): Boolean {
    if (local.size != remote.size || prefixLength !in 1..(local.size * 8)) return false
    val fullBytes = prefixLength / 8
    for (index in 0 until fullBytes) if (local[index] != remote[index]) return false
    val remainingBits = prefixLength % 8
    return remainingBits == 0 || ((local[fullBytes].toInt() xor remote[fullBytes].toInt()) and
        (0xff shl (8 - remainingBits))) == 0
}

/**
 * 在条件满足时维持一次网络会话。会话因服务发布、发现或监听失败而结束时，
 * 按退避重建，避免一次瞬时错误让同步或配对永久停止。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> foregroundLanSessions(
    enabled: Flow<Boolean>,
    foreground: Flow<Boolean>,
    networks: Flow<T?>,
    onFailure: (Throwable) -> Unit = {},
    serve: suspend (T) -> Unit,
): Flow<Unit> = combine(enabled, foreground, networks) { requested, active, network ->
    network.takeIf { requested && active }
}.distinctUntilChanged().transformLatest { network ->
    if (network == null) return@transformLatest
    var backoff = SESSION_RETRY_MIN_MS
    while (true) {
        currentCoroutineContext().ensureActive()
        val failed = try {
            serve(network)
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
            true
        }
        delay(if (failed) backoff else SESSION_RETRY_MIN_MS)
        backoff = if (failed) minOf(backoff * 2, SESSION_RETRY_MAX_MS) else SESSION_RETRY_MIN_MS
    }
}

internal class LanTransport(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun interfaces(): Flow<LanInterface?> = callbackFlow {
        val links = mutableMapOf<Network, LinkAddress>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val usable = properties.linkAddresses.filterNot {
                    it.address.isLoopbackAddress || it.address.isAnyLocalAddress
                }
                val address = usable.firstOrNull { it.address is Inet4Address }
                    ?: usable.firstOrNull { !it.address.isLinkLocalAddress }
                    ?: usable.firstOrNull()
                if (address == null) links.remove(network) else links[network] = address
                publish()
            }

            override fun onLost(network: Network) {
                links.remove(network)
                publish()
            }

            private fun publish() {
                trySend(links.entries.firstOrNull()?.let { LanInterface(it.key, it.value) })
            }
        }
        trySend(null)
        connectivity.registerNetworkCallback(lanRequest(NetworkCapabilities.TRANSPORT_WIFI), callback)
        val ethernet = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
                callback.onLinkPropertiesChanged(network, properties)
            override fun onLost(network: Network) = callback.onLost(network)
        }
        try {
            connectivity.registerNetworkCallback(lanRequest(NetworkCapabilities.TRANSPORT_ETHERNET), ethernet)
            awaitClose { connectivity.unregisterNetworkCallback(ethernet) }
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    private fun lanRequest(transport: Int): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(transport)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    /**
     * 在局域网地址上绑定监听端口。传入 0 时由系统分配端口，供配对二维码携带。
     */
    fun bind(lan: LanInterface, port: Int = 0): Inet4Listener {
        val socket = Inet4Listener()
        try {
            socket.bind(lan.address.address, port)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
        return socket
    }

    suspend fun listen(
        lan: LanInterface,
        server: Inet4Listener,
        onIncoming: suspend (Socket) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val sockets = LanSockets(lan)
        server.soTimeout = 500
        try {
            coroutineScope {
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: SocketException) {
                        break
                    }
                    if (!lan.canReach(socket.inetAddress)) {
                        socket.close()
                        continue
                    }
                    sockets.track(socket)
                    launch {
                        try { onIncoming(socket) } finally { sockets.release(socket) }
                    }
                }
            }
        } finally {
            server.close()
            sockets.close()
        }
    }

    suspend fun connect(
        lan: LanInterface,
        address: InetAddress,
        port: Int,
        use: suspend (Socket) -> Unit,
    ) = withContext(Dispatchers.IO) { LanSockets(lan).connect(address, port, use) }
}

internal class LanSockets(private val lan: LanInterface) {
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    private var closed = false

    suspend fun connect(address: InetAddress, port: Int, use: suspend (Socket) -> Unit) {
        require(port in 1..65535 && lan.canReach(address))
        val socket = Socket()
        track(socket)
        try {
            lan.network.bindSocket(socket)
            socket.bind(InetSocketAddress(lan.address.address, 0))
            socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            use(socket)
        } finally {
            release(socket)
        }
    }

    @Synchronized
    fun track(socket: Socket) {
        if (closed) {
            socket.close()
            throw IOException("局域网连接已关闭")
        }
        socket.soTimeout = TIMEOUT_MS
        active.add(socket)
    }

    fun release(socket: Socket) {
        active.remove(socket)
        runCatching { socket.close() }
    }

    @Synchronized
    fun close() {
        closed = true
        active.forEach(::release)
    }
}
