package com.chatnotebook.app.ui

import android.graphics.Bitmap
import android.net.Uri
import com.chatnotebook.app.data.DocumentBlock
import com.chatnotebook.app.data.MessageAttachment
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class DocumentExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val attachment = MessageAttachment(
        id = "a1",
        kind = "image",
        relativePath = "attachments/a1.png",
        mimeType = "image/png",
        sizeBytes = 10,
        createdAt = 0,
    )

    @Test fun defaultFileNameSanitizesTitleAndUsesFormatExtension() {
        assertEquals(
            "工作_2026_计划-19700101-0800.md",
            DocumentExporter.defaultFileName("工作/2026:计划", DocumentExportFormat.Markdown, 0L),
        )
        assertEquals(
            "整理结果-19700101-0800.pdf",
            DocumentExporter.defaultFileName("   ", DocumentExportFormat.Pdf, 0L),
        )
        assertEquals(
            "对话-19700101-0800.png",
            DocumentExporter.defaultFileName("对话", DocumentExportFormat.Png, 0L),
        )
    }

    @Test fun markdownExportEmbedsImagesAsDataUri() = runBlocking {
        val source = imageFile()
        val target = folder.newFile("out.md")
        val blocks = listOf(
            DocumentBlock.Text("# 工作"),
            DocumentBlock.Images("m1", listOf(attachment)),
            DocumentBlock.Text("『06:13』看这张图"),
        )

        val result = DocumentExporter.export(
            context(),
            Uri.fromFile(target),
            DocumentExportFormat.Markdown,
            content = "# 工作\n\n『06:13』看这张图",
            blocks = blocks,
            attachmentUri = { Uri.fromFile(source) },
        )

        assertTrue(result.isSuccess)
        val text = target.readText()
        assertTrue(text.contains("![图片](data:image/"))
        assertTrue(text.indexOf("# 工作") < text.indexOf("![图片]"))
        assertTrue(text.indexOf("![图片]") < text.indexOf("『06:13』看这张图"))
    }

    @Test fun markdownExportWithoutImagesKeepsContent() = runBlocking {
        val target = folder.newFile("plain.md")

        val result = DocumentExporter.export(
            context(),
            Uri.fromFile(target),
            DocumentExportFormat.Markdown,
            content = "# 工作\n\n『06:13』看文字",
        )

        assertTrue(result.isSuccess)
        assertEquals("# 工作\n\n『06:13』看文字", target.readText())
    }

    @Test fun pngExportWithImageProducesPng() = runBlocking {
        val source = imageFile()
        val target = folder.newFile("out.png")

        val result = DocumentExporter.export(
            context(),
            Uri.fromFile(target),
            DocumentExportFormat.Png,
            content = "# 工作\n\n『06:13』看这张图",
            blocks = listOf(
                DocumentBlock.Text("# 工作"),
                DocumentBlock.Images("m1", listOf(attachment)),
                DocumentBlock.Text("『06:13』看这张图"),
            ),
            attachmentUri = { Uri.fromFile(source) },
        )

        assertTrue(result.isSuccess)
        val bytes = target.readBytes()
        assertTrue(bytes.size > 0)
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])
    }

    @Test fun pngExportShrinksImagesToFitLongContent() = runBlocking {
        val source = imageFile(400, 300)
        val target = folder.newFile("long.png")
        val images = List(50) { index ->
            attachment.copy(id = "a$index", relativePath = "attachments/a$index.png")
        }

        val result = DocumentExporter.export(
            context(),
            Uri.fromFile(target),
            DocumentExportFormat.Png,
            content = "# 工作",
            blocks = listOf(DocumentBlock.Images("m1", images)),
            attachmentUri = { Uri.fromFile(source) },
        )

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val bytes = target.readBytes()
        assertTrue(bytes.size > 0)
        assertEquals(0x89.toByte(), bytes[0])
    }

    private fun context() = RuntimeEnvironment.getApplication()

    private fun imageFile(width: Int = 16, height: Int = 12): File {
        val file = folder.newFile("source-${width}x$height-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
