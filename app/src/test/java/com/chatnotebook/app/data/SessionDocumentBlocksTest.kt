package com.chatnotebook.app.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDocumentBlocksTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun message(
        id: String,
        content: String,
        createdAt: Long,
        images: List<MessageAttachment> = emptyList(),
        deleted: Boolean = false,
    ) = MessageRecord(
        id = id,
        sessionId = "s",
        role = "user",
        rawContent = content,
        createdAt = createdAt,
        isDeleted = deleted,
        attachments = images,
    )

    private fun image(id: String) = MessageAttachment(
        id = id,
        kind = "image",
        relativePath = "attachments/$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 10,
        createdAt = 0,
    )

    private fun document(content: String, snapshot: String = "本地整理") = SessionDocumentRecord(
        sessionId = "s",
        content = content,
        messageCount = 1,
        generatedAt = 0,
        modelSnapshot = snapshot,
    )

    @Test fun localDocumentPlacesImagesAfterTheirTimeAnchor() {
        val time = 1_700_000_000_000L
        val content = """
            # 工作

            > 本地整理 · 共 1 条消息

            ## 2023年11月15日

            『06:13』看这张图
        """.trimIndent()

        val blocks = SessionDocumentBlocks.build(
            document(content),
            listOf(message("m1", "看这张图", time, listOf(image("a1")))),
            zone,
        )

        val anchor = blocks.indexOfFirst { it is DocumentBlock.Text && it.markdown.contains("『06:13』") }
        assertTrue(anchor >= 0)
        val images = blocks[anchor + 1] as DocumentBlock.Images
        assertEquals("m1", images.messageId)
        assertEquals(listOf("a1"), images.attachments.map { it.id })
    }

    @Test fun sameMinuteMessagesConsumeAnchorsInOrder() {
        val time = 1_700_000_000_000L
        val content = """
            『06:13』第一条

            『06:13』第二条
        """.trimIndent()

        val blocks = SessionDocumentBlocks.build(
            document(content),
            listOf(
                message("m1", "第一条", time, listOf(image("a1"))),
                message("m2", "第二条", time + 1_000, listOf(image("a2"))),
            ),
            zone,
        )

        val images = blocks.filterIsInstance<DocumentBlock.Images>()
        assertEquals(2, images.size)
        assertEquals(listOf("a1"), images[0].attachments.map { it.id })
        assertEquals(listOf("a2"), images[1].attachments.map { it.id })
    }

    @Test fun deletedAndDeduplicatedMessagesKeepNoImages() {
        val time = 1_700_000_000_000L
        val content = "『06:14』保留内容"

        val blocks = SessionDocumentBlocks.build(
            document(content),
            listOf(
                message("old", "保留内容", time, listOf(image("a1"))),
                message("new", "保留内容", time + 60_000, listOf(image("a2"))),
                message("gone", "保留内容 ", time + 120_000, listOf(image("a3")), deleted = true),
            ),
            zone,
        )

        val images = blocks.filterIsInstance<DocumentBlock.Images>()
        assertEquals(1, images.size)
        assertEquals(listOf("a2"), images[0].attachments.map { it.id })
    }

    @Test fun modelDocumentAppendsImagesInTimeOrder() {
        val blocks = SessionDocumentBlocks.build(
            document("# 工作\n\n模型整理的正文", snapshot = "gpt-4o"),
            listOf(
                message("m2", "第二条", 1_700_000_060_000, listOf(image("b2"))),
                message("m1", "第一条", 1_700_000_000_000, listOf(image("b1"))),
            ),
            zone,
        )

        val images = blocks.filterIsInstance<DocumentBlock.Images>()
        assertEquals(listOf("b1", "b2"), images.flatMap { block -> block.attachments.map { it.id } })
        assertTrue(blocks.last() is DocumentBlock.Images)
    }

    @Test fun oldTimeLineFormatStillBindsImages() {
        val time = 1_700_000_000_000L
        val content = """
            **06:13**

            旧格式正文
        """.trimIndent()

        val blocks = SessionDocumentBlocks.build(
            document(content),
            listOf(message("m1", "旧格式正文", time, listOf(image("a1")))),
            zone,
        )

        val images = blocks.filterIsInstance<DocumentBlock.Images>()
        assertEquals(listOf("a1"), images.single().attachments.map { it.id })
    }

    @Test fun unmatchedTimeAddsNoImages() {
        val blocks = SessionDocumentBlocks.build(
            document("『09:00』没有对应消息"),
            listOf(message("m1", "别的内容", 1_700_000_000_000L, listOf(image("a1")))),
            zone,
        )

        assertTrue(blocks.none { it is DocumentBlock.Images })
    }
}
