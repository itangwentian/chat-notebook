package com.chatnotebook.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class LocalDocument(val content: String, val messageCount: Int)

/**
 * Builds a document without any model: chronological order, grouped by day, and
 * whitespace-normalized duplicates keep only their latest occurrence.
 */
class LocalSessionDocumentComposer(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    fun compose(
        title: String,
        messages: List<MessageRecord>,
        formats: Map<String, BubbleFormat> = emptyMap(),
    ): LocalDocument {
        val kept = keepDocumentMessages(messages)
        require(kept.isNotEmpty()) { "暂无可整理的消息" }

        val start = kept.first().createdAt.toZoned()
        val end = kept.last().createdAt.toZoned()
        val content = buildString {
            appendLine("# ${title.ifBlank { "对话整理" }}")
            appendLine()
            append("> 本地整理 · 共 ${kept.size} 条消息")
            if (start.toLocalDate() == end.toLocalDate()) {
                append(" · ${dayFormat.format(start)} ${timeFormat.format(start)}-${timeFormat.format(end)}")
            } else {
                append(
                    " · ${dayFormat.format(start)} ${timeFormat.format(start)} 至 " +
                        "${dayFormat.format(end)} ${timeFormat.format(end)}",
                )
            }
            appendLine()
            appendLine()
            var currentDay: LocalDate? = null
            kept.forEach { message ->
                val time = message.createdAt.toZoned()
                if (time.toLocalDate() != currentDay) {
                    currentDay = time.toLocalDate()
                    appendLine("## ${dayFormat.format(time)}")
                    appendLine()
                }
                appendLine("『${timeFormat.format(time)}』${message.toMarkdown(formats)}")
                appendLine()
            }
        }
        return LocalDocument(content.trimEnd() + "\n", kept.size)
    }

    private fun Long.toZoned() = Instant.ofEpochMilli(this).atZone(zoneId)
}

/** Messages a local document keeps: not deleted, non-blank, deduplicated keeping the latest. */
internal fun keepDocumentMessages(messages: List<MessageRecord>): List<MessageRecord> = messages
    .filter { !it.isDeleted && it.currentContent.isNotBlank() }
    .groupBy { it.currentContent.trim().replace(WHITESPACE, " ") }
    .values
    .map { group -> group.maxWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id }) }
    .sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })

private val WHITESPACE = Regex("\\s+")
