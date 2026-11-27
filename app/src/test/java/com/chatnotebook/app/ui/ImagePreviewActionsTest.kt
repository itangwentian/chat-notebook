package com.chatnotebook.app.ui

import android.content.Intent
import android.net.Uri
import com.chatnotebook.app.data.MessageAttachment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImagePreviewActionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun attachment(relativePath: String = "attachments/a.jpg", mimeType: String = "image/jpeg") =
        MessageAttachment(
            id = "a1",
            kind = "image",
            relativePath = relativePath,
            mimeType = mimeType,
            sizeBytes = 3,
            createdAt = 0,
        )

    @Test fun galleryNameUsesTimestampAndPathExtension() {
        val time = 0L
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(time))

        assertEquals(
            "chat_notebook_$stamp.png",
            galleryImageName("attachments/photo.PNG", "image/jpeg", time),
        )
    }

    @Test fun galleryNameFallsBackToMimeType() {
        val time = 0L
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(time))

        assertEquals("chat_notebook_$stamp.jpg", galleryImageName("attachments/photo", "image/jpeg", time))
        assertEquals("chat_notebook_$stamp.png", galleryImageName("attachments/photo", "image/png", time))
        assertEquals("webp", imageExtension("attachments/photo", "image/webp"))
        assertEquals("jpg", imageExtension("attachments/photo", "image/unknown"))
    }

    @Test fun imageSharingBuildsSingleSendIntent() = runBlocking {
        val source = folder.newFile("source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val context = RuntimeEnvironment.getApplication()

        val intent = ImageSharing.prepare(
            context,
            attachment(),
            attachmentUri = { Uri.fromFile(source) },
            contentUri = { Uri.fromFile(it) },
        )

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/jpeg", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertTrue(stream!!.path!!.contains("shared-messages"))
        assertTrue(stream.path!!.endsWith(".jpg"))
        assertTrue(File(stream.path!!).exists())
        assertEquals(1, intent.clipData?.itemCount)
    }

    @Test fun imageSharingFailsWhenAttachmentMissing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()

        val result = runCatching {
            ImageSharing.prepare(context, attachment(), attachmentUri = { null }, contentUri = { Uri.fromFile(it) })
        }

        val error = result.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(File(context.cacheDir, "shared-messages/image").exists())
    }
}
