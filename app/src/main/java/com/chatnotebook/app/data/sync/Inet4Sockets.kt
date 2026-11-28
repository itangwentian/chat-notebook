package com.chatnotebook.app.data.sync

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.StructTimeval
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketImpl
import java.net.SocketOptions
import java.net.SocketTimeoutException

/**
 * WSA 的镜像网络只转发 IPv4 监听，而 Android 默认的 java.net 套接字是 IPv6 双栈，
 * 表现为 `::ffff:` 监听、局域网设备无法连入。这里直接用 android.system.Os 创建
 * AF_INET 监听套接字与已连接套接字，保证二维码里的地址与端口对外可达。
 */
internal class Inet4SocketImpl : SocketImpl() {
    private var timeoutMs = 0
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private fun socketFd(): FileDescriptor = fd ?: throw SocketException("套接字已关闭")

    /**
     * `java.net.Socket(SocketImpl)` 不会把 created 置位，首次调用 getImpl()（如 setSoTimeout）
     * 会懒创建并回调这里。文件描述符在 adopt() 时已就绪，因此只需保留现状。
     */
    override fun create(stream: Boolean) = Unit

    override fun bind(host: InetAddress?, port: Int) = throw UnsupportedOperationException("IPv4 套接字只用于已接受的连接")

    override fun connect(host: String?, port: Int) = throw UnsupportedOperationException("IPv4 套接字只用于已接受的连接")

    override fun connect(address: InetAddress?, port: Int) = throw UnsupportedOperationException("IPv4 套接字只用于已接受的连接")

    override fun connect(address: SocketAddress?, timeout: Int) =
        throw UnsupportedOperationException("IPv4 套接字只用于已接受的连接")

    override fun listen(backlog: Int) = throw UnsupportedOperationException("IPv4 套接字只用于已接受的连接")

    override fun accept(si: SocketImpl) = throw UnsupportedOperationException("IPv4 套接字只用于已接受的连接")

    override fun getInputStream(): InputStream = input ?: Inet4InputStream().also { input = it }

    override fun getOutputStream(): OutputStream = output ?: Inet4OutputStream().also { output = it }

    override fun available(): Int = 0

    override fun sendUrgentData(data: Int) = throw IOException("不支持紧急数据")

    override fun close() {
        val descriptor = fd ?: return
        fd = null
        runCatching { Os.close(descriptor) }
    }

    override fun setOption(optID: Int, value: Any?) {
        val descriptor = fd ?: return
        when (optID) {
            SocketOptions.SO_TIMEOUT -> {
                val millis = (value as? Number)?.toInt() ?: 0
                timeoutMs = millis
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        Os.setsockoptTimeval(
                            descriptor,
                            OsConstants.SOL_SOCKET,
                            OsConstants.SO_RCVTIMEO,
                            StructTimeval.fromMillis(millis.toLong()),
                        )
                    }
                }
            }
            SocketOptions.TCP_NODELAY -> runCatching {
                Os.setsockoptInt(
                    descriptor,
                    OsConstants.IPPROTO_TCP,
                    OsConstants.TCP_NODELAY,
                    if (value == true) 1 else 0,
                )
            }
            else -> Unit
        }
    }

    override fun getOption(optID: Int): Any? = when (optID) {
        SocketOptions.SO_TIMEOUT -> timeoutMs
        else -> null
    }

    fun peerAddress(): InetAddress? = address

    fun peerPort(): Int = port

    fun localAddress(): InetAddress? = localAddressValue

    fun localPortValue(): Int = localport

    private var localAddressValue: InetAddress? = null

    fun adopt(client: FileDescriptor, peer: InetSocketAddress?, localPort: Int) {
        fd = client
        address = peer?.address
        port = peer?.port ?: 0
        localport = localPort
        localAddressValue = runCatching {
            (Os.getsockname(client) as? InetSocketAddress)?.address
        }.getOrNull()
    }

    private fun waitReadable(): Boolean {
        val descriptor = socketFd()
        val pollFd = StructPollfd().apply {
            fd = descriptor
            events = OsConstants.POLLIN.toShort()
        }
        val ready = try {
            Os.poll(arrayOf(pollFd), if (timeoutMs == 0) -1 else timeoutMs)
        } catch (error: ErrnoException) {
            throw SocketException("等待数据失败：${error.message}")
        }
        if (ready == 0) return false
        if (pollFd.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) {
            throw SocketException("套接字不可用")
        }
        return true
    }

    private inner class Inet4InputStream : InputStream() {
        private val single = ByteArray(1)

        override fun read(): Int {
            val count = read(single, 0, 1)
            return if (count <= 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!waitReadable()) throw SocketTimeoutException("读取超时")
            return try {
                val count = Os.read(socketFd(), b, off, len)
                if (count == 0) -1 else count
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN) throw SocketTimeoutException("读取超时")
                throw IOException("读取失败", error)
            }
        }
    }

    private inner class Inet4OutputStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var written = 0
            while (written < len) {
                val count = try {
                    Os.write(socketFd(), b, off + written, len - written)
                } catch (error: ErrnoException) {
                    throw IOException("写入失败", error)
                }
                if (count <= 0) throw IOException("写入失败")
                written += count
            }
        }

        override fun flush() = Unit
    }
}

/** 直接用 AF_INET 监听的 IPv4 服务端套接字，规避默认套接字的 IPv6 双栈映射。 */
internal class Inet4Listener {
    private var descriptor: FileDescriptor? = null

    var localPort = 0
        private set

    var isClosed = false
        private set

    var soTimeout: Int = 0
        set(value) {
            field = value
            descriptor?.let { setReceiveTimeout(it, value) }
        }

    fun bind(address: InetAddress, port: Int) {
        val socket = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        } catch (error: ErrnoException) {
            throw IOException("无法创建 IPv4 监听套接字", error)
        }
        try {
            Os.setsockoptInt(socket, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
            Os.bind(socket, address, port)
            Os.listen(socket, 50)
            localPort = (Os.getsockname(socket) as InetSocketAddress).port
        } catch (error: Exception) {
            runCatching { Os.close(socket) }
            throw error
        }
        descriptor = socket
        if (soTimeout > 0) setReceiveTimeout(socket, soTimeout)
    }

    fun accept(): Socket {
        val socket = descriptor ?: throw SocketException("监听套接字已关闭")
        val pollFd = StructPollfd().apply {
            fd = socket
            events = OsConstants.POLLIN.toShort()
        }
        val ready = try {
            Os.poll(arrayOf(pollFd), if (soTimeout == 0) -1 else soTimeout)
        } catch (error: ErrnoException) {
            throw SocketException("等待连接失败：${error.message}")
        }
        if (ready == 0) throw SocketTimeoutException("等待连接超时")
        if (pollFd.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) {
            throw SocketException("监听套接字不可用")
        }
        val client = try {
            Os.accept(socket, null)
        } catch (error: ErrnoException) {
            throw SocketException("接受连接失败：${error.message}")
        }
        val peer = runCatching { Os.getpeername(client) as? InetSocketAddress }.getOrNull()
        val accepted = Inet4SocketImpl()
        accepted.adopt(client, peer, localPort)
        return Inet4Socket(accepted)
    }

    fun close() {
        isClosed = true
        val socket = descriptor ?: return
        descriptor = null
        runCatching { Os.close(socket) }
    }

    private fun setReceiveTimeout(socket: FileDescriptor, millis: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            Os.setsockoptTimeval(
                socket,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(millis.toLong()),
            )
        }
    }
}

internal class Inet4Socket internal constructor(private val inet4: Inet4SocketImpl) : Socket(inet4) {
    override fun getInetAddress(): InetAddress? = inet4.peerAddress()

    override fun getPort(): Int = inet4.peerPort()

    override fun getLocalAddress(): InetAddress? = inet4.localAddress()

    override fun getLocalPort(): Int = inet4.localPortValue()

    override fun isConnected(): Boolean = true

    override fun isBound(): Boolean = true

    override fun getRemoteSocketAddress(): SocketAddress? =
        inet4.peerAddress()?.let { InetSocketAddress(it, inet4.peerPort()) }

    override fun getLocalSocketAddress(): SocketAddress? =
        inet4.localAddress()?.let { InetSocketAddress(it, inet4.localPortValue()) }
}