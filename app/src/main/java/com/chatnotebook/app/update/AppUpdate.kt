package com.chatnotebook.app.update

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** GitHub 上可用的新版本。 */
internal data class AppUpdate(
    val version: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/** 通过 GitHub Releases 检查并下载最新安装包。 */
internal class AppUpdater(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = LATEST_RELEASE_URL,
) {
    /** 返回可用的新版本；网络不可达、没有新版本或缺少安装包时返回 null。 */
    suspend fun latest(currentVersion: String): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val release = json.decodeFromString<ReleaseDto>(response.body?.string().orEmpty())
                val version = release.tagName.removePrefix("v").trim()
                if (compareVersions(version, currentVersion) <= 0) return@withContext null
                val asset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: return@withContext null
                AppUpdate(version, asset.downloadUrl, asset.size)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    /** 下载安装包到 [targetDir]，返回已下载的文件。 */
    suspend fun download(
        update: AppUpdate,
        targetDir: File,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val target = File(targetDir, "chat_notebook-${update.version}.apk")
        client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("下载失败：响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        }
        if (target.length() <= 0L) throw IOException("下载失败：文件为空")
        target
    }
}

/** 比较点分版本号，忽略前缀 v；无法解析的段按 0 处理。 */
internal fun compareVersions(left: String, right: String): Int {
    val a = versionParts(left)
    val b = versionParts(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val diff = (a.getOrNull(index) ?: 0) - (b.getOrNull(index) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

private fun versionParts(value: String): List<Int> =
    value.removePrefix("v")
        .trim()
        .split('.')
        .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.SECONDS)
    .build()

private const val LATEST_RELEASE_URL = "https://api.github.com/repos/itangwentian/chat-notebook/releases/latest"

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)