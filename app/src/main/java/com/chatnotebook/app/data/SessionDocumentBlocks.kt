package com.chatnotebook.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One renderable piece of an organized document: Markdown text or a message's images. */
sealed interface DocumentBlock {
    data class Text(val markdown: String) : DocumentBlock

    data class Images(val messageId: String, val attachments: List<MessageAttachment>) : DocumentBlock
}

/**
 * Splits a stored document into text and image blocks without touching the stored text.
 * Local documents bind images to `『HH:mm』` anchors; model documents collect images at the end.
 */
object SessionDocumentBlocks {
    private const val LOCAL_SNAPSHOT = "本地整理"
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val paragraphBreak = Regex("\n\\s*\n")
    private val newAnchor = Regex("""^『(\d{1,2}:\d{2})』""")
    private val oldAnchor = Regex("""^\*\*(\d{1,2}:\d{2})(?: · [^*]+)?\*\*$""")

    fun build(
        document: SessionDocumentRecord,
        messages: List<MessageRecord>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DocumentBlock> {
        val kept = keepDocumentMessages(messages)
        if (document.modelSnapshot != LOCAL_SNAPSHOT) {
            return aiBlocks(document.content, kept)
        }
        val byTime = kept.groupBy { timeFormat.format(Instant.ofEpochMilli(it.createdAt).atZone(zoneId)) }
        val consumed = mutableMapOf<String, Int>()
        val blocks = mutableListOf<DocumentBlock>()
        document.content.split(paragraphBreak)
            .filter { it.isNotBlank() }
            .forEach { paragraph ->
                blocks += DocumentBlock.Text(paragraph)
                val time = anchorTime(paragraph) ?: return@forEach
                val index = consumed[time] ?: 0
                val message = byTime[time]?.getOrNull(index) ?: return@forEach
                consumed[time] = index + 1
                val images = message.imageAttachments()
                if (images.isNotEmpty()) blocks += DocumentBlock.Images(message.id, images)
            }
        return blocks
    }

    internal fun anchorTime(paragraph: String): String? {
        val trimmed = paragraph.trim()
        return (newAnchor.find(trimmed) ?: oldAnchor.find(trimmed))?.groupValues?.get(1)
    }

    private fun aiBlocks(content: String, kept: List<MessageRecord>): List<DocumentBlock> {
        val blocks = mutableListOf<DocumentBlock>()
        val text = content.split(paragraphBreak).filter { it.isNotBlank() }
        text.forEach { blocks += DocumentBlock.Text(it) }
        kept.forEach { message ->
            val images = message.imageAttachments()
            if (images.isNotEmpty()) blocks += DocumentBlock.Images(message.id, images)
        }
        return blocks
    }
}

internal fun MessageRecord.imageAttachments(): List<MessageAttachment> =
    attachments.filter { it.kind == "image" }
