package com.chatnotebook.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SERVICE_TYPE = "_chat-notebook._tcp."
private const val TIMEOUT_MS = 8_000

internal data class LanInterface(val network: Network, val address: LinkAddress) {
    fun canReach(peer: InetAddress): Boolean =
        peer != address.address && !peer.isAnyLocalAddress && !peer.isLoopbackAddress && !peer.isMulticastAddress &&
            sameSubnet(address.address.address, peer.address, address.prefixLength)
}

internal data class LanPeer(val discoveryId: String, val address: InetAddress, val port: Int)

internal fun sameSubnet(local: ByteArray, remote: ByteArray, prefixLength: Int): Boolean {
    if (local.size != remote.size || prefixLength !in 1..(local.size * 8)) return false
    val fullBytes = prefixLength / 8
    for (index in 0 until fullBytes) if (local[index] != remote[index]) return false
    val remainingBits = prefixLength % 8
    return remainingBits == 0 || ((local[fullBytes].toInt() xor remote[fullBytes].toInt()) and
        (0xff shl (8 - remainingBits))) == 0
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> foregroundLanSessions(
    enabled: Flow<Boolean>,
    foreground: Flow<Boolean>,
    networks: Flow<T?>,
    serve: suspend (T) -> Unit,
): Flow<Unit> = combine(enabled, foreground, networks) { requested, active, network ->
    network.takeIf { requested && active }
}.distinctUntilChanged().transformLatest { network ->
    if (network != null) serve(network)
}

internal class LanTransport(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)

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

    suspend fun serve(
        lan: LanInterface,
        discoveryId: String,
        selectPeer: (LanPeer) -> Boolean,
        onPeer: suspend (LanPeer, LanSockets) -> Unit,
        onIncoming: suspend (Socket) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val sockets = LanSockets(lan)
        val server = ServerSocket()
        val wifi = context.getSystemService(WifiManager::class.java)
        val multicast = wifi?.createMulticastLock("chat-notebook:lan")
        multicast?.acquire()
        try {
            server.bind(InetSocketAddress(lan.address.address, 0))
            server.soTimeout = 500
            coroutineScope {
                launch {
                    discovered(lan, discoveryId, server.localPort).filter(selectPeer)
                        .distinctUntilChanged().collectLatest { onPeer(it, sockets) }
                }
                try {
                    while (isActive) {
                        val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                        if (!lan.canReach(socket.inetAddress)) {
                            socket.close()
                            continue
                        }
                        sockets.track(socket)
                        launch {
                            try { onIncoming(socket) } finally { sockets.release(socket) }
                        }
                    }
                } finally {
                    server.close()
                    sockets.close()
                }
            }
        } finally {
            server.close()
            sockets.close()
            if (multicast?.isHeld == true) multicast.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun discovered(lan: LanInterface, id: String, port: Int): Flow<LanPeer> = callbackFlow {
        val resolve = mutableSetOf<String>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("无法发现局域网设备 ($errorCode)"))
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!resolve.add(serviceInfo.serviceName)) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolve.remove(info.serviceName)
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolve.remove(info.serviceName)
                        val peer = info.attributes["id"]?.decodeToString() ?: return
                        val protocol = info.attributes["v"]?.decodeToString()
                        if (peer == id || protocol != LAN_SYNC_VERSION.toString()) return
                        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                        val address = addresses.firstOrNull(lan::canReach) ?: return
                        trySend(LanPeer(peer, address, info.port))
                    }
                })
            }
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                close(IOException("无法发布局域网同步服务 ($errorCode)"))
            }
        }
        val service = NsdServiceInfo().apply {
            // A new listener uses a new DNS-SD instance name so cached SRV records cannot keep its old port.
            serviceName = "ChatNotebook-${UUID.randomUUID().toString().take(12)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", id)
            setAttribute("v", LAN_SYNC_VERSION.toString())
            if (Build.VERSION.SDK_INT >= 33) network = lan.network
        }
        nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, lan.network, context.mainExecutor, discovery)
            } else {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            }
            awaitClose { runCatching { nsd.stopServiceDiscovery(discovery) } }
        } finally {
            runCatching { nsd.unregisterService(registration) }
        }
    }
}

internal class LanSockets(private val lan: LanInterface) {
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    private var closed = false

    suspend fun connect(peer: LanPeer, use: suspend (Socket) -> Unit) {
        require(peer.port in 1..65535 && lan.canReach(peer.address))
        val socket = Socket()
        track(socket)
        try {
            lan.network.bindSocket(socket)
            socket.bind(InetSocketAddress(lan.address.address, 0))
            socket.connect(InetSocketAddress(peer.address, peer.port), TIMEOUT_MS)
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
