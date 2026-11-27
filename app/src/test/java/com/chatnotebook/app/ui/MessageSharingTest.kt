package com.chatnotebook.app.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageRecord
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageSharingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val filesByUri = mutableMapOf<Uri, File>()

    @Test fun sharesCurrentTextWithBlankLinesWithoutAttachments() = runBlocking {
        val intent = MessageSharing.prepare(
            context,
            listOf(message("修改后的正文"), message("第二条")),
        ) { error("Text share requires no attachment lookup") }

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("修改后的正文\n\n第二条", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test fun sharesSingleImageWithTextAndReadOnlyGrant() = runBlocking {
        val image = attachment("image", "photo.jpg", "image/jpeg")
        val intent = MessageSharing.prepare(context, listOf(message("说明", image)), ::shareUri, ::sourceUri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals("说明", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val uri = intent.clipData!!.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertArrayEquals("attachments/photo.jpg".toByteArray(), filesByUri.getValue(uri).readBytes())
    }

    @Test fun setsCommonMimeTypeForMultipleAttachments() = runBlocking {
        val jpeg = attachment("image", "photo.jpg", "image/jpeg")
        val png = attachment("image", "photo.png", "image/png")
        val audio = attachment("audio", "voice.m4a", "audio/mp4")

        val images = MessageSharing.prepare(context, listOf(message("", jpeg, png)), ::shareUri, ::sourceUri)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, images.action)
        assertEquals("image/*", images.type)
        assertEquals(2, images.clipData!!.itemCount)
        assertTrue(images.hasExtra(Intent.EXTRA_STREAM))

        val mixed = MessageSharing.prepare(context, listOf(message("", jpeg, audio)), ::shareUri, ::sourceUri)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, mixed.action)
        assertEquals("*/*", mixed.type)
        val last = mixed.clipData!!.getItemAt(1).uri
        assertArrayEquals(audio.relativePath.toByteArray(), filesByUri.getValue(last).readBytes())
    }

    @Test fun unreadableAttachmentDiscardsPreparedCopies() = runBlocking {
        val cache = File(context.cacheDir, "shared-messages")
        val before = cache.list()?.toSet().orEmpty()
        val available = attachment("image", "photo.jpg", "image/jpeg")
        val missing = attachment("image", "missing.jpg", "image/jpeg")

        val error = runCatching {
            MessageSharing.prepare(context, listOf(message("说明", available, missing)), ::shareUri) { path ->
                if (path == missing.relativePath) null else sourceUri(path)
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, filesByUri.size)
        assertEquals(before, cache.list()?.toSet().orEmpty())
    }

    @Test fun preferredAppsComeFirstAndOthersFollowByName() {
        val ordered = MessageSharing.orderShareTargets(
            listOf(
                shareTarget("com.example.zeta", "Zeta"),
                shareTarget("com.ss.android.lark", "飞书"),
                shareTarget("com.example.alpha", "Alpha"),
                shareTarget("com.tencent.mm", "微信"),
                shareTarget("com.tencent.mobileqq", "QQ"),
            ),
        )

        assertEquals(listOf("微信", "QQ", "飞书", "Alpha", "Zeta"), ordered.map { it.label })
    }

    @Test fun missingPreferredAppsDoNotLeaveGaps() {
        val ordered = MessageSharing.orderShareTargets(
            listOf(
                shareTarget("com.example.beta", "Beta"),
                shareTarget("com.tencent.wework", "企业微信"),
            ),
        )

        assertEquals(listOf("企业微信", "Beta"), ordered.map { it.label })
    }

    private fun shareTarget(packageName: String, label: String) = MessageShareTarget(
        ComponentName(packageName, "$packageName.ShareActivity"),
        label,
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
    )

    private fun sourceUri(path: String): Uri =
        Uri.fromFile(File(context.cacheDir, path.substringAfterLast('/')).apply { writeText(path) })

    private fun shareUri(file: File): Uri =
        Uri.parse("content://test.share/${file.parentFile!!.name}/${file.name}")
            .also { filesByUri[it] = file }

    private fun attachment(kind: String, name: String, mime: String) =
        MessageAttachment(name, kind, "attachments/$name", mime, 1, createdAt = 1)

    private fun message(text: String, vararg attachments: MessageAttachment) =
        MessageRecord("message", "session", "user", "原文", text, createdAt = 1, attachments = attachments.toList())
}
