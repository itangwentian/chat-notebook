package com.chatnotebook.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Builds the chronological transcript and turns it into one stored document. */
class SessionDocumentService(
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun transcript(
        messages: List<MessageRecord>,
        formats: Map<String, BubbleFormat> = emptyMap(),
    ): String = messages
        .sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })
        .mapNotNull { message ->
            val content = message.toMarkdown(formats).trim()
            if (content.isEmpty()) return@mapNotNull null
            val time = timeFormat.format(Instant.ofEpochMilli(message.createdAt).atZone(zoneId))
            "[$time] ${rolePrefix(message.role)}$content"
        }
        .joinToString("\n")

    fun generateLocal(
        session: SessionRecord,
        messages: List<MessageRecord>,
        formats: Map<String, BubbleFormat> = emptyMap(),
    ): SessionDocumentRecord {
        val local = LocalSessionDocumentComposer(zoneId).compose(session.title, messages, formats)
        return SessionDocumentRecord(
            sessionId = session.id,
            content = local.content,
            messageCount = local.messageCount,
            generatedAt = now(),
            modelSnapshot = "本地整理",
        )
    }

    /** User messages carry no role prefix; only assistant and system keep one. */
    private fun rolePrefix(role: String): String = when (role) {
        "assistant" -> "助手："
        "system" -> "系统："
        else -> ""
    }
}
