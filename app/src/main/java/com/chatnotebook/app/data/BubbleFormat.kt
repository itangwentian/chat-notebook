package com.chatnotebook.app.data

/** 气泡字体效果，可叠加。 */
enum class BubbleTextStyle(val id: String) {
    Bold("bold"),
    Italic("italic"),
    StrikeThrough("strikethrough"),
    ;

    companion object {
        fun fromId(id: String): BubbleTextStyle? = entries.firstOrNull { it.id == id }
    }
}

/** 气泡文字颜色，红、黄、绿互斥。 */
enum class BubbleColor(val id: String, val markdownHex: String) {
    Red("red", "#C62828"),
    Yellow("yellow", "#B26A00"),
    Green("green", "#2E7D32"),
    ;

    companion object {
        fun fromId(id: String): BubbleColor? = entries.firstOrNull { it.id == id }
    }
}

/** 一组可叠加的内置格式：若干字体效果加一种颜色。 */
data class BubbleFormat(
    val styles: Set<BubbleTextStyle> = emptySet(),
    val color: BubbleColor? = null,
) {
    val id: String
        get() = (BubbleTextStyle.entries.filter { it in styles }.map { it.id } + listOfNotNull(color?.id))
            .joinToString("+")

    fun toggleStyle(target: BubbleTextStyle): BubbleFormat? =
        BubbleFormat(
            if (target in styles) styles - target else styles + target,
            color,
        ).orNull()

    fun toggleColor(target: BubbleColor): BubbleFormat? =
        BubbleFormat(styles, if (color == target) null else target).orNull()

    private fun orNull(): BubbleFormat? = takeUnless { styles.isEmpty() && color == null }

    companion object {
        fun fromId(id: String?): BubbleFormat? {
            if (id.isNullOrBlank()) return null
            val styles = mutableSetOf<BubbleTextStyle>()
            var color: BubbleColor? = null
            for (part in id.split("+")) {
                val parsedStyle = BubbleTextStyle.fromId(part)
                val parsedColor = BubbleColor.fromId(part)
                when {
                    parsedStyle != null -> if (!styles.add(parsedStyle)) return null
                    parsedColor != null -> {
                        if (color != null) return null
                        color = parsedColor
                    }
                    else -> return null
                }
            }
            return if (styles.isEmpty() && color == null) null else BubbleFormat(styles, color)
        }
    }
}