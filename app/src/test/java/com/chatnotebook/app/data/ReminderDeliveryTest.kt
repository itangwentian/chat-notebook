package com.chatnotebook.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderDeliveryTest {
    private val session = SessionRecord("s", "旅行", 1, 1)
    private val message = MessageRecord("m", "s", "user", "整理一下行程", createdAt = 1)

    @Test fun dueScheduledReminderCarriesSessionTitleAndMessageBody() {
        val payload = base(triggerAt = 1_000)

        val delivery = requireNotNull(reminderDeliveryOf(payload, "r", now = 1_000))

        assertEquals("r", delivery.reminderId)
        assertEquals("s", delivery.sessionId)
        assertEquals("旅行", delivery.sessionTitle)
        assertEquals("m", delivery.messageId)
        assertEquals("整理一下行程", delivery.body)
    }

    @Test fun deletedMessageLeavesMessageIdEmpty() {
        val payload = base(triggerAt = 1_000).copy(
            messages = listOf(message.copy(isDeleted = true)),
        )

        val delivery = requireNotNull(reminderDeliveryOf(payload, "r", now = 1_000))

        assertNull(delivery.messageId)
        assertEquals(DEFAULT_REMINDER_BODY, delivery.body)
    }

    @Test fun deliveredReminderDoesNotFireAgainOnceMarkedFired() {
        val payload = base(triggerAt = 1_000)
        requireNotNull(reminderDeliveryOf(payload, "r", now = 1_000))

        val fired = payload.copy(
            reminders = payload.reminders.map { it.copy(status = REMINDER_FIRED, updatedAt = 2_000) },
        )
        assertNull(reminderDeliveryOf(fired, "r", now = 2_000))
    }

    @Test fun cancelledOrUnknownRemindersNeverDeliver() {
        val cancelled = base(triggerAt = 1_000).copy(
            reminders = listOf(reminder().copy(status = REMINDER_CANCELLED, updatedAt = 2_000)),
        )

        assertNull(reminderDeliveryOf(cancelled, "r", now = 2_000))
        assertNull(reminderDeliveryOf(base(triggerAt = 1_000), "missing", now = 2_000))
    }

    @Test fun earlyDeliveryIsToleratedOnlyWithinFiveSeconds() {
        val payload = base(triggerAt = 10_000)

        assertNull(reminderDeliveryOf(payload, "r", now = 1_000))
        assertNull(reminderDeliveryOf(payload, "r", now = 10_000 - 5_001))
        requireNotNull(reminderDeliveryOf(payload, "r", now = 10_000 - 5_000))
    }

    @Test fun deletedMessageOrSessionFallsBackToDefaultBodyOrNothing() {
        val withoutMessage = base(triggerAt = 1_000).copy(
            messages = listOf(message.copy(isDeleted = true)),
        )
        assertEquals(DEFAULT_REMINDER_BODY, requireNotNull(reminderDeliveryOf(withoutMessage, "r", now = 1_000)).body)

        val withoutSession = base(triggerAt = 1_000).copy(
            sessions = listOf(session.copy(isDeleted = true)),
        )
        assertNull(reminderDeliveryOf(withoutSession, "r", now = 1_000))
    }

    @Test fun longMessageIsCollapsedAndTruncated() {
        val long = MessageRecord("m", "s", "user", "第一行\n\n第二行   " + "内容".repeat(80), createdAt = 1)
        val payload = base(triggerAt = 1_000).copy(messages = listOf(long))

        val body = requireNotNull(reminderDeliveryOf(payload, "r", now = 1_000)).body

        assertEquals(120, body.length)
        assertEquals(false, body.contains("\n"))
        assertEquals(false, body.contains("  "))
    }

    private fun reminder() = ReminderRecord(
        id = "r", sessionId = "s", messageId = "m", triggerAt = 1_000, createdAt = 1, updatedAt = 1,
    )

    private fun base(triggerAt: Long) = RecordPayload(
        sessions = listOf(session),
        messages = listOf(message),
        reminders = listOf(reminder().copy(triggerAt = triggerAt)),
    )
}