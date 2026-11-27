package com.chatnotebook.app.data.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import kotlinx.serialization.encodeToString

internal class LanFrames(socket: Socket) {
    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    inline fun <reified T> write(value: T, limit: Int = 8 * 1024 * 1024) {
        val bytes = syncJson.encodeToString(value).toByteArray()
        if (bytes.size !in 1..limit) throw IOException("同步数据超过大小限制")
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    inline fun <reified T> read(limit: Int = 8 * 1024 * 1024): T {
        val size = input.readInt()
        if (size !in 1..limit) throw IOException("同步数据大小无效")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return syncJson.decodeFromString(bytes.decodeToString())
    }
}
