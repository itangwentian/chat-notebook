package com.chatnotebook.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.chatnotebook.app.data.DocumentBlock
import com.chatnotebook.app.data.MessageAttachment
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DocumentExportFormat(val label: String, val mimeType: String, val extension: String) {
    Markdown("导出 MD", "text/markdown", "md"),
    Pdf("导出 PDF", "application/pdf", "pdf"),
    Png("导出 PNG", "image/png", "png"),
}

object DocumentExporter {
    fun defaultFileName(
        title: String,
        format: DocumentExportFormat,
        time: Long = System.currentTimeMillis(),
    ): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
            .trim()
            .trim('_')
            .take(40)
            .ifBlank { "整理结果" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date(time))
        return "$safe-$stamp.${format.extension}"
    }

    suspend fun export(
        context: Context,
        uri: Uri,
        format: DocumentExportFormat,
        content: String,
        blocks: List<DocumentBlock> = emptyList(),
        attachmentUri: (String) -> Uri? = { null },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("无法写入所选位置")
            stream.use { output ->
                when (format) {
                    DocumentExportFormat.Markdown ->
                        output.write(markdownWithImages(context, content, blocks, attachmentUri).toByteArray(Charsets.UTF_8))
                    DocumentExportFormat.Pdf -> writePdf(context, output, content, blocks, attachmentUri)
                    DocumentExportFormat.Png -> writePng(context, output, content, blocks, attachmentUri)
                }
            }
        }
    }

    private fun hasImages(blocks: List<DocumentBlock>): Boolean =
        blocks.any { it is DocumentBlock.Images && it.attachments.isNotEmpty() }

    private fun markdownWithImages(
        context: Context,
        content: String,
        blocks: List<DocumentBlock>,
        attachmentUri: (String) -> Uri?,
    ): String {
        if (!hasImages(blocks)) return content
        val builder = StringBuilder()
        var total = 0
        blocks.forEach { block ->
            when (block) {
                is DocumentBlock.Text -> builder.append(block.markdown).append("\n\n")
                is DocumentBlock.Images -> block.attachments.forEach { attachment ->
                    val data = imageDataUri(context, attachment, attachmentUri) ?: return@forEach
                    total += data.length
                    if (total > MARKDOWN_TOTAL_MAX_CHARS) {
                        throw IOException("图片过大，无法导出为 Markdown，请改用 PDF")
                    }
                    builder.append("![图片](").append(data).append(")\n\n")
                }
            }
        }
        return builder.toString().trimEnd() + "\n"
    }

    private fun imageDataUri(
        context: Context,
        attachment: MessageAttachment,
        attachmentUri: (String) -> Uri?,
    ): String? {
        val uri = attachmentUri(attachment.relativePath) ?: return null
        val decoded = decodeSampledBitmap(context.contentResolver, uri, MARKDOWN_IMAGE_SIZE) ?: return null
        val scaled = scaleDown(decoded, MARKDOWN_IMAGE_SIZE)
        val keepAlpha = attachment.mimeType.lowercase(Locale.US).let {
            it == "image/png" || it == "image/webp" || it == "image/gif"
        }
        val bytes = ByteArrayOutputStream().use { buffer ->
            val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            scaled.compress(format, MARKDOWN_IMAGE_QUALITY, buffer)
            if (scaled !== decoded) scaled.recycle()
            buffer.toByteArray()
        }
        if (bytes.size > MARKDOWN_IMAGE_MAX_BYTES) {
            throw IOException("图片过大，无法导出为 Markdown，请改用 PDF")
        }
        val mime = if (keepAlpha) "image/png" else "image/jpeg"
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSize) return bitmap
        val scale = maxSize.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** PDF pages clip one tall, styled layout so headings, emphasis and colors stay intact. */
    private fun writePdf(output: OutputStream, content: String) {
        val spanned = markdownSpanned(content)
        val paint = textPaint(PDF_TEXT_SIZE)
        val layout = buildLayout(spanned, paint, (PDF_WIDTH - PDF_MARGIN * 2).toInt())
        val document = PdfDocument()
        try {
            val pageHeight = PDF_HEIGHT - PDF_MARGIN * 2
            var startLine = 0
            var pageNumber = 1
            while (true) {
                val pageTop = layout.getLineTop(startLine)
                var endLine = startLine
                while (endLine < layout.lineCount && layout.getLineBottom(endLine) - pageTop <= pageHeight) {
                    endLine++
                }
                if (endLine == startLine) endLine = (startLine + 1).coerceAtMost(layout.lineCount)
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create(),
                )
                val canvas = page.canvas
                canvas.save()
                canvas.clipRect(PDF_MARGIN, PDF_MARGIN, PDF_WIDTH - PDF_MARGIN, PDF_HEIGHT - PDF_MARGIN)
                canvas.translate(PDF_MARGIN, PDF_MARGIN - pageTop)
                layout.draw(canvas)
                canvas.restore()
                document.finishPage(page)
                if (endLine >= layout.lineCount) break
                startLine = endLine
                pageNumber++
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    /** Flows text and images page by page; images scale to the page instead of being clipped. */
    private fun writePdf(
        context: Context,
        output: OutputStream,
        content: String,
        blocks: List<DocumentBlock>,
        attachmentUri: (String) -> Uri?,
    ) {
        if (!hasImages(blocks)) {
            writePdf(output, content)
            return
        }
        val paint = textPaint(PDF_TEXT_SIZE)
        val width = (PDF_WIDTH - PDF_MARGIN * 2).toInt()
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create(),
        )
        var canvas = page.canvas
        var y = PDF_MARGIN
        val bottom = PDF_HEIGHT - PDF_MARGIN
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create(),
            )
            canvas = page.canvas
            y = PDF_MARGIN
        }

        try {
            blocks.forEach { block ->
                when (block) {
                    is DocumentBlock.Text -> {
                        val layout = buildLayout(markdownSpanned(block.markdown), paint, width)
                        var startLine = 0
                        while (startLine < layout.lineCount) {
                            val lineTop = layout.getLineTop(startLine)
                            var endLine = startLine
                            while (endLine < layout.lineCount &&
                                y + (layout.getLineBottom(endLine) - lineTop) <= bottom
                            ) {
                                endLine++
                            }
                            if (endLine == startLine) {
                                if (y > PDF_MARGIN) {
                                    newPage()
                                    continue
                                }
                                endLine = startLine + 1
                            }
                            val used = layout.getLineBottom(endLine - 1) - lineTop
                            canvas.save()
                            canvas.clipRect(0f, y, width.toFloat(), y + used)
                            canvas.translate(0f, y - lineTop)
                            layout.draw(canvas)
                            canvas.restore()
                            y += used
                            startLine = endLine
                            if (startLine < layout.lineCount) newPage()
                        }
                        y += PDF_BLOCK_GAP
                    }
                    is DocumentBlock.Images -> block.attachments.forEach { attachment ->
                        val bitmap = decodeAttachment(context, attachment, attachmentUri) ?: return@forEach
                        val maxWidth = width.toFloat()
                        val maxHeight = bottom - PDF_MARGIN
                        val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
                        val drawWidth = bitmap.width * scale
                        val drawHeight = bitmap.height * scale
                        if (y + drawHeight > bottom && y > PDF_MARGIN) newPage()
                        val left = PDF_MARGIN + (width - drawWidth) / 2f
                        canvas.drawBitmap(
                            bitmap,
                            null,
                            RectF(left, y, left + drawWidth, y + drawHeight),
                            imagePaint,
                        )
                        y += drawHeight + PDF_BLOCK_GAP
                    }
                }
            }
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun writePng(output: OutputStream, content: String) {
        val spanned = markdownSpanned(content)
        val width = (PNG_WIDTH - PNG_PADDING * 2).toInt()
        var textSize = PNG_TEXT_SIZE
        var paint = textPaint(textSize)
        var layout = buildLayout(spanned, paint, width)
        while (layout.height + PNG_PADDING * 2 > PNG_MAX_HEIGHT) {
            if (textSize <= PNG_MIN_TEXT_SIZE) {
                throw IOException("内容过长，无法导出为单张 PNG，请改用 PDF 或 Markdown")
            }
            textSize -= 2f
            paint = textPaint(textSize)
            layout = buildLayout(spanned, paint, width)
        }
        val height = (layout.height + PNG_PADDING * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(PNG_WIDTH, height, pngConfig(height))
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.translate(PNG_PADDING, PNG_PADDING)
            layout.draw(canvas)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("PNG 编码失败")
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writePng(
        context: Context,
        output: OutputStream,
        content: String,
        blocks: List<DocumentBlock>,
        attachmentUri: (String) -> Uri?,
    ) {
        if (!hasImages(blocks)) {
            writePng(output, content)
            return
        }
        val width = (PNG_WIDTH - PNG_PADDING * 2).toInt()
        val sources = pngSources(context, blocks, attachmentUri)
        try {
            var textSize = PNG_TEXT_SIZE
            var imageScale = 1f
            var entries = pngEntries(sources, width, textSize, imageScale)
            var contentHeight = entries.contentHeight()
            while (contentHeight + PNG_PADDING * 2 > PNG_MAX_HEIGHT) {
                when {
                    textSize > PNG_MIN_TEXT_SIZE -> textSize -= 2f
                    imageScale > PNG_MIN_IMAGE_SCALE ->
                        imageScale = (imageScale - 0.1f).coerceAtLeast(PNG_MIN_IMAGE_SCALE)
                    else -> throw IOException("内容过长，无法导出为单张 PNG，请改用 PDF 或 Markdown")
                }
                entries = pngEntries(sources, width, textSize, imageScale)
                contentHeight = entries.contentHeight()
            }
            val height = (contentHeight + PNG_PADDING * 2).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(PNG_WIDTH, height, pngConfig(height))
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                canvas.translate(PNG_PADDING, PNG_PADDING)
                var y = 0f
                entries.forEach { entry ->
                    when (entry) {
                        is PngEntry.Text -> {
                            canvas.save()
                            canvas.translate(0f, y)
                            entry.layout.draw(canvas)
                            canvas.restore()
                            y += entry.layout.height + PNG_BLOCK_GAP
                        }
                        is PngEntry.Image -> {
                            val drawWidth = entry.height * entry.bitmap.width.toFloat() / entry.bitmap.height
                            val left = (width - drawWidth) / 2f
                            canvas.drawBitmap(
                                entry.bitmap,
                                null,
                                RectF(left, y, left + drawWidth, y + entry.height),
                                Paint(Paint.FILTER_BITMAP_FLAG),
                            )
                            y += entry.height + PNG_BLOCK_GAP
                        }
                    }
                }
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("PNG 编码失败")
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            sources.forEach { if (it is PngSource.Image) it.bitmap.recycle() }
        }
    }

    private fun pngConfig(height: Int): Bitmap.Config =
        if (height > PNG_HIGH_CONFIG_HEIGHT) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    private fun pngSources(
        context: Context,
        blocks: List<DocumentBlock>,
        attachmentUri: (String) -> Uri?,
    ): List<PngSource> {
        val sources = mutableListOf<PngSource>()
        blocks.forEach { block ->
            when (block) {
                is DocumentBlock.Text -> sources += PngSource.Text(block.markdown)
                is DocumentBlock.Images -> block.attachments.forEach { attachment ->
                    decodeAttachment(context, attachment, attachmentUri)?.let { sources += PngSource.Image(it) }
                }
            }
        }
        return sources
    }

    private fun pngEntries(
        sources: List<PngSource>,
        width: Int,
        textSize: Float,
        imageScale: Float,
    ): List<PngEntry> {
        val paint = textPaint(textSize)
        return sources.map { source ->
            when (source) {
                is PngSource.Text -> PngEntry.Text(buildLayout(markdownSpanned(source.markdown), paint, width))
                is PngSource.Image -> {
                    val scale = minOf(width.toFloat() / source.bitmap.width, 1f) * imageScale
                    PngEntry.Image(source.bitmap, (source.bitmap.height * scale).toInt().coerceAtLeast(1))
                }
            }
        }
    }

    private fun List<PngEntry>.contentHeight(): Float = fold(0f) { total, entry ->
        total + when (entry) {
            is PngEntry.Text -> entry.layout.height
            is PngEntry.Image -> entry.height
        } + PNG_BLOCK_GAP
    }

    private fun decodeAttachment(
        context: Context,
        attachment: MessageAttachment,
        attachmentUri: (String) -> Uri?,
    ): Bitmap? {
        val uri = attachmentUri(attachment.relativePath) ?: return null
        return decodeSampledBitmap(context.contentResolver, uri, EXPORT_IMAGE_SIZE)
    }

    private fun textPaint(size: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.BLACK
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun buildLayout(text: Spanned, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.18f)
            .setIncludePad(false)
            .build()

    private sealed interface PngEntry {
        class Text(val layout: StaticLayout) : PngEntry
        class Image(val bitmap: Bitmap, val height: Int) : PngEntry
    }

    private sealed interface PngSource {
        class Text(val markdown: String) : PngSource
        class Image(val bitmap: Bitmap) : PngSource
    }

    private const val PDF_WIDTH = 595
    private const val PDF_HEIGHT = 842
    private const val PDF_MARGIN = 40f
    private const val PDF_TEXT_SIZE = 13f
    private const val PDF_BLOCK_GAP = 12f
    private const val PNG_WIDTH = 720
    private const val PNG_PADDING = 32f
    private const val PNG_MAX_HEIGHT = 20_000f
    private const val PNG_TEXT_SIZE = 20f
    private const val PNG_MIN_TEXT_SIZE = 10f
    private const val PNG_MIN_IMAGE_SCALE = 0.35f
    private const val PNG_HIGH_CONFIG_HEIGHT = 12_000
    private const val PNG_BLOCK_GAP = 18f
    private const val EXPORT_IMAGE_SIZE = 1600
    private const val MARKDOWN_IMAGE_SIZE = 1280
    private const val MARKDOWN_IMAGE_QUALITY = 80
    private const val MARKDOWN_IMAGE_MAX_BYTES = 4 * 1024 * 1024
    private const val MARKDOWN_TOTAL_MAX_CHARS = 16_000_000
}
