package com.chatnotebook.app.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDocumentServiceTest {
    private val service = SessionDocumentService(
        now = { 99L },
        zoneId = ZoneId.of("Asia/Shanghai"),
    )

    @Test fun transcriptIsChronologicalWithRolesAndSkipsDeleted() {
        val messages = listOf(
            MessageRecord("m2", "s", "assistant", "答复", createdAt = 1_700_000_060_000),
            MessageRecord("m1", "s", "user", "提问", createdAt = 1_700_000_000_000),
            MessageRecord("m3", "s", "user", "已删除", createdAt = 1_700_000_030_000, isDeleted = true),
        )

        assertEquals(
            "[2023-11-15 06:13] 提问\n[2023-11-15 06:14] 助手：答复",
            service.transcript(messages.filter { !it.isDeleted }),
        )
    }

    @Test fun transcriptKeepsBubbleFormatsAndHidesUserLabel() {
        val messages = listOf(
            MessageRecord("m1", "s", "user", "重点", createdAt = 1_700_000_000_000),
            MessageRecord("m2", "s", "assistant", "补充", createdAt = 1_700_000_060_000),
            MessageRecord("m3", "s", "user", "叠加", createdAt = 1_700_000_120_000),
        )
        val formats = mapOf(
            "m1" to BubbleFormat(styles = setOf(BubbleTextStyle.Bold), color = BubbleColor.Red),
            "m2" to BubbleFormat(styles = setOf(BubbleTextStyle.Italic)),
            "m3" to BubbleFormat(styles = setOf(BubbleTextStyle.Bold, BubbleTextStyle.StrikeThrough)),
        )

        val transcript = service.transcript(messages, formats)

        assertTrue(transcript.contains("<span style=\"color:#C62828\">**重点**</span>"))
        assertTrue(transcript.contains("*补充*"))
        assertTrue(transcript.contains("~~**叠加**~~"))
        assertTrue(!transcript.contains("用户"))
    }

    @Test fun localDocumentKeepsLatestDuplicateInChronologicalOrder() {
        val messages = listOf(
            MessageRecord("m1", "s", "user", "重复内容", createdAt = 1_700_000_000_000),
            MessageRecord("m2", "s", "user", "唯一内容", createdAt = 1_700_000_030_000),
            MessageRecord("m3", "s", "assistant", " 重复内容 ", createdAt = 1_700_000_060_000),
        )

        val document = service.generateLocal(SessionRecord("s", "工作", 1, 1), messages)

        assertEquals(2, document.messageCount)
        assertEquals("本地整理", document.modelSnapshot)
        assertTrue(document.content.contains("## 2023年11月15日"))
        assertTrue(document.content.contains("『06:14』重复内容"))
        assertTrue(document.content.contains("『06:13』唯一内容"))
        assertTrue(!document.content.contains("用户"))
        assertTrue(!document.content.contains("助手"))
        assertEquals(1, Regex("重复内容").findAll(document.content).count())
    }

    @Test fun localDocumentGroupsDaysAndKeepsTimePrefixOnFirstLineOnly() {
        val dayOne = 1_700_000_000_000 // 2023-11-15 06:13 Asia/Shanghai
        val dayTwo = dayOne + 24 * 60 * 60 * 1000
        val messages = listOf(
            MessageRecord("m1", "s", "user", "第一行\n第二行", createdAt = dayOne),
            MessageRecord("m2", "s", "assistant", "跨天答复", createdAt = dayTwo),
        )

        val document = service.generateLocal(SessionRecord("s", "工作", 1, 1), messages)

        assertEquals(
            """
            # 工作

            > 本地整理 · 共 2 条消息 · 2023年11月15日 06:13 至 2023年11月16日 06:13

            ## 2023年11月15日

            『06:13』第一行
            第二行

            ## 2023年11月16日

            『06:13』跨天答复
            """.trimIndent() + "\n",
            document.content,
        )
        assertTrue(!document.content.contains("助手"))
        assertTrue(!document.content.contains("用户"))
    }

    @Test fun localDocumentKeepsBubbleFormatsAsMarkdown() {
        val messages = listOf(
            MessageRecord("m1", "s", "user", "重点", createdAt = 1_700_000_000_000),
            MessageRecord("m2", "s", "assistant", "补充", createdAt = 1_700_000_030_000),
            MessageRecord(
                "m3",
                "s",
                "user",
                "合并内容",
                createdAt = 1_700_000_060_000,
                formatSegments = listOf(
                    MessageFormatSegment("第一段", "bold+red"),
                    MessageFormatSegment("第二段", "italic"),
                ),
            ),
        )
        val formats = mapOf(
            "m1" to BubbleFormat(styles = setOf(BubbleTextStyle.Bold), color = BubbleColor.Red),
            "m2" to BubbleFormat(styles = setOf(BubbleTextStyle.Italic)),
        )

        val document = service.generateLocal(SessionRecord("s", "工作", 1, 1), messages, formats)

        assertTrue(document.content.contains("<span style=\"color:#C62828\">**重点**</span>"))
        assertTrue(document.content.contains("*补充*"))
        assertTrue(document.content.contains("<span style=\"color:#C62828\">**第一段**</span>"))
        assertTrue(document.content.contains("*第二段*"))
        assertTrue(!document.content.contains("用户"))
    }

    }
