package com.chatnotebook.app.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.chatnotebook.app.R
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageRecord
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MessageShareFileProvider : FileProvider()

internal data class MessageShareTarget(
    val component: ComponentName,
    val label: String,
    val icon: Bitmap,
)

internal data class MessageShareFormat(val action: String, val mimeType: String) {
    fun intent() = Intent(action).setType(mimeType)
}

internal object MessageSharing {
    /** 常用分享应用的包名，按此顺序优先展示。 */
    private val preferredPackages = listOf(
        "com.tencent.mm", // 微信
        "com.tencent.mobileqq", // QQ
        "com.ss.android.lark", // 飞书
        "com.tencent.wework", // 企业微信
        "com.alibaba.android.rimet", // 钉钉
        "org.telegram.messenger", // Telegram
        "com.whatsapp", // WhatsApp
    )

    fun format(messages: List<MessageRecord>): MessageShareFormat {
        val attachments = attachmentsOf(messages)
        val action = if (attachments.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        return MessageShareFormat(action, mimeTypeOf(attachments))
    }

    @Suppress("DEPRECATION")
    suspend fun findTargets(context: Context, format: MessageShareFormat): List<MessageShareTarget> =
        withContext(Dispatchers.IO) {
            val manager = context.packageManager
            manager.queryIntentActivities(format.intent(), PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { resolved ->
                    val activity = resolved.activityInfo ?: return@mapNotNull null
                    if (!activity.exported || activity.packageName == context.packageName) return@mapNotNull null
                    if (activity.permission != null &&
                        context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED
                    ) return@mapNotNull null
                    MessageShareTarget(
                        ComponentName(activity.packageName, activity.name),
                        resolved.loadLabel(manager).toString(),
                        resolved.loadIcon(manager).toBitmap(96, 96),
                    )
                }
                .distinctBy { it.component.packageName to it.label }
                .let(::orderShareTargets)
        }

    /** 常用应用优先，其余按名称排序。 */
    internal fun orderShareTargets(targets: List<MessageShareTarget>): List<MessageShareTarget> {
        val rank = preferredPackages.withIndex().associate { (index, packageName) -> packageName to index }
        return targets.sortedWith(
            compareBy({ rank[it.component.packageName] ?: Int.MAX_VALUE }, { it.label }),
        )
    }

    suspend fun prepare(
        context: Context,
        messages: List<MessageRecord>,
        contentUri: (File) -> Uri = { FileProvider.getUriForFile(context, "${context.packageName}.message-share", it) },
        attachmentUri: (String) -> Uri?,
    ): Intent {
        val text = messages.map { it.currentContent }.filter { it.isNotBlank() }.joinToString("\n\n")
        val attachments = attachmentsOf(messages)
        val streams = if (attachments.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            val cache = File(context.cacheDir, "shared-messages")
            val expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            cache.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.deleteRecursively() }
            val request = File(cache, UUID.randomUUID().toString())
            try {
                if (!request.mkdirs()) throw IOException("Cannot create share cache")
                attachments.mapIndexed { index, attachment ->
                    ensureActive()
                    val source = attachmentUri(attachment.relativePath) ?: throw IOException("Missing attachment")
                    val file = File(request, "attachment-${index + 1}.${extensionOf(attachment)}")
                    val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open attachment")
                    input.use { from -> file.outputStream().use { to -> from.copyTo(to) } }
                    contentUri(file)
                }
            } catch (error: Exception) {
                request.deleteRecursively()
                if (error is CancellationException) throw error
                throw IOException(context.getString(R.string.share_attachment_failed), error)
            }
        }
        require(text.isNotEmpty() || streams.isNotEmpty()) { context.getString(R.string.share_empty) }
        return format(messages).intent().apply {
            if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text)
            if (streams.isNotEmpty()) {
                if (streams.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, streams.single())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams))
                }
                clipData = ClipData.newUri(context.contentResolver, "attachments", streams.first()).apply {
                    streams.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun attachmentsOf(messages: List<MessageRecord>): List<MessageAttachment> =
        messages.flatMap { it.attachments }.distinctBy { it.relativePath }

    private fun mimeTypeOf(attachments: List<MessageAttachment>): String {
        val types = attachments.map { it.mimeType }.toSet()
        if (types.isEmpty()) return "text/plain"
        types.singleOrNull()?.let { return it }
        val family = types.map { it.substringBefore('/') }.distinct().singleOrNull()
        return if (family == null) "*/*" else "$family/*"
    }

    private fun extensionOf(attachment: MessageAttachment): String =
        attachment.relativePath.substringAfterLast('/').substringAfterLast('.', "")
            .takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) }
            ?: if (attachment.kind == "audio") "m4a" else "jpg"
}
