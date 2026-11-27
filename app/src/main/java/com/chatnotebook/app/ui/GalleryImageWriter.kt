package com.chatnotebook.app.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.chatnotebook.app.R
import com.chatnotebook.app.data.MessageAttachment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves one attachment into the system gallery as a new image entry. */
internal class GalleryImageWriter(private val context: Context) {
    suspend fun save(
        attachment: MessageAttachment,
        source: Uri,
        time: Long = System.currentTimeMillis(),
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = attachment.mimeType.ifBlank { "image/jpeg" }
        val extension = imageExtension(attachment.relativePath, mimeType)
        val fileName = galleryImageName(attachment.relativePath, mimeType, time)
        val baseName = fileName.substringBeforeLast('.')
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ChatNotebook")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = @Suppress("DEPRECATION") File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "ChatNotebook",
                )
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException(context.getString(R.string.save_to_gallery_failed))
                }
                var file = File(directory, "$baseName.$extension")
                var index = 1
                while (file.exists()) {
                    file = File(directory, "${baseName}_$index.$extension")
                    index++
                }
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException(context.getString(R.string.save_to_gallery_failed))
        try {
            val input = resolver.openInputStream(source)
                ?: throw IOException(context.getString(R.string.save_to_gallery_failed))
            input.use { from ->
                val output = resolver.openOutputStream(target)
                    ?: throw IOException(context.getString(R.string.save_to_gallery_failed))
                output.use { to -> from.copyTo(to) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        } catch (error: Exception) {
            resolver.delete(target, null, null)
            throw error
        }
        target
    }
}

/** File name for a saved gallery image; exported for unit tests. */
internal fun galleryImageName(relativePath: String, mimeType: String, time: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(time))
    return "chat_notebook_$stamp.${imageExtension(relativePath, mimeType)}"
}

/** Extension from the attachment path, falling back to the MIME type. */
internal fun imageExtension(relativePath: String, mimeType: String): String {
    val fromPath = relativePath.substringAfterLast('/').substringAfterLast('.', "")
        .takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) }
    if (fromPath != null) return fromPath.lowercase(Locale.US)
    return when (mimeType.lowercase(Locale.US)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}
