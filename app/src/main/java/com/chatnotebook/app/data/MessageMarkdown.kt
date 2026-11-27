package com.chatnotebook.app.data

/**
 * Renders one message body with its bubble format as inline Markdown.
 * Merged messages keep the format of every segment; other messages use the message-level format.
 */
fun MessageRecord.toMarkdown(formats: Map<String, BubbleFormat>): String =
    if (formatSegments.isNotEmpty()) {
        formatSegments.joinToString("\n") { segment ->
            applyBubbleMarkdown(segment.text, BubbleFormat.fromId(segment.formatId))
        }
    } else {
        applyBubbleMarkdown(currentContent.trim(), formats[id])
    }

fun applyBubbleMarkdown(text: String, format: BubbleFormat?): String {
    if (format == null || text.isBlank()) return text
    var result = text
    for (style in BubbleTextStyle.entries) {
        if (style !in format.styles) continue
        val (prefix, suffix) = when (style) {
            BubbleTextStyle.Bold -> "**" to "**"
            BubbleTextStyle.Italic -> "*" to "*"
            BubbleTextStyle.StrikeThrough -> "~~" to "~~"
        }
        result = wrapLines(result, prefix, suffix)
    }
    format.color?.let { color ->
        result = wrapLines(result, "<span style=\"color:${color.markdownHex}\">", "</span>")
    }
    return result
}

/** Markdown emphasis does not span line breaks, so each non-blank line is wrapped separately. */
private fun wrapLines(text: String, prefix: String, suffix: String): String =
    text.split('\n').joinToString("\n") { line ->
        if (line.isBlank()) line else "$prefix$line$suffix"
    }
