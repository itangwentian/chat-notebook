package com.chatnotebook.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.chatnotebook.app.R
import com.chatnotebook.app.data.MessageAttachment
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prepares a single-image share intent through the existing message-share provider. */
internal object ImageSharing {
    suspend fun prepare(
        context: Context,
        attachment: MessageAttachment,
        attachmentUri: (String) -> Uri?,
        contentUri: (File) -> Uri = {
            FileProvider.getUriForFile(context, "${context.packageName}.message-share", it)
        },
    ): Intent = withContext(Dispatchers.IO) {
        val source = attachmentUri(attachment.relativePath)
            ?: throw IOException(context.getString(R.string.share_image_failed))
        val cache = File(context.cacheDir, "shared-messages")
        val expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        cache.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.deleteRecursively() }
        if (!cache.exists() && !cache.mkdirs()) {
            throw IOException(context.getString(R.string.share_image_failed))
        }
        val file = File(
            cache,
            "image-${UUID.randomUUID()}.${imageExtension(attachment.relativePath, attachment.mimeType)}",
        )
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException(context.getString(R.string.share_image_failed))
            input.use { from -> file.outputStream().use { to -> from.copyTo(to) } }
        } catch (error: Exception) {
            file.delete()
            throw IOException(context.getString(R.string.share_image_failed), error)
        }
        val stream = contentUri(file)
        Intent(Intent.ACTION_SEND).apply {
            type = attachment.mimeType.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, stream)
            clipData = ClipData.newUri(context.contentResolver, "image", stream)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
