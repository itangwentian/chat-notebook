package com.chatnotebook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingTest {
    @Test fun parsesHeadingsQuotesBulletsAndParagraphs() {
        val blocks = MarkdownParser.blocks("# 标题\n\n> 引用\n- 列表\n普通")

        assertEquals(
            listOf(
                MarkdownBlockKind.Heading1,
                MarkdownBlockKind.Quote,
                MarkdownBlockKind.Bullet,
                MarkdownBlockKind.Paragraph,
            ),
            blocks.map { it.kind },
        )
        assertEquals("标题", blocks.first().text)
    }

    @Test fun mergesConsecutiveLinesIntoOneParagraph() {
        val blocks = MarkdownParser.blocks("第一行\n第二行\n\n第三行")

        assertEquals(2, blocks.size)
        assertEquals("第一行\n第二行", blocks.first().text)
        assertEquals(MarkdownBlockKind.Paragraph, blocks.first().kind)
    }

    @Test fun parsesInlineEmphasisAndColor() {
        val runs = MarkdownParser.runs(
            "**粗** 与 *斜* 和 ~~删~~ <span style=\"color:#C62828\">红</span>",
        )

        assertEquals("粗 与 斜 和 删 红", runs.joinToString("") { it.text })
        assertTrue(runs.any { it.bold && it.text == "粗" })
        assertTrue(runs.any { it.italic && it.text == "斜" })
        assertTrue(runs.any { it.strike && it.text == "删" })
        assertTrue(runs.any { it.color == 0xFFC62828.toInt() && it.text == "红" })
    }

    @Test fun combinesColorWithBold() {
        val runs = MarkdownParser.runs("<span style=\"color:#2E7D32\">**绿**</span>")

        assertEquals("绿", runs.joinToString("") { it.text })
        assertTrue(runs.single().bold)
        assertEquals(0xFF2E7D32.toInt(), runs.single().color)
    }
}
