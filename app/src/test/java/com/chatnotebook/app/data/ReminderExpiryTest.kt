package com.chatnotebook.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderExpiryTest {
    private val now = 10_000_000L

    private fun reminder(
        id: String,
        triggerAt: Long,
        status: String = REMINDER_SCHEDULED,
    ) = ReminderRecord(
        id = id,
        sessionId = "s",
        messageId = "m",
        triggerAt = triggerAt,
        createdAt = 1,
        updatedAt = 1,
        status = status,
    )

    @Test
    fun pastDueScheduledReminderIsCancelled() {
        val result = expirePastDueReminders(listOf(reminder("old", now - REMINDER_EXPIRY_GRACE_MS)), now)

        assertEquals(listOf("old"), result.expiredIds)
        assertEquals(REMINDER_CANCELLED, result.reminders.single().status)
        assertEquals(now, result.reminders.single().updatedAt)
    }

    @Test
    fun graceWindowAndFutureRemindersStayScheduled() {
        val inside = reminder("inside", now - REMINDER_EXPIRY_GRACE_MS + 1)
        val future = reminder("future", now + 1)

        val result = expirePastDueReminders(listOf(inside, future), now)

        assertTrue(result.expiredIds.isEmpty())
        assertEquals(listOf(inside, future), result.reminders)
    }

    @Test
    fun firedAndCancelledRemindersAreLeftAlone() {
        val fired = reminder("fired", now - REMINDER_EXPIRY_GRACE_MS * 2, REMINDER_FIRED)
        val cancelled = reminder("cancelled", now - REMINDER_EXPIRY_GRACE_MS * 2, REMINDER_CANCELLED)

        val result = expirePastDueReminders(listOf(fired, cancelled), now)

        assertTrue(result.expiredIds.isEmpty())
        assertEquals(listOf(fired, cancelled), result.reminders)
    }

    @Test
    fun onlyPastDueEntriesChangeInMixedList() {
        val expired = reminder("expired", now - REMINDER_EXPIRY_GRACE_MS - 1)
        val future = reminder("future", now + 60_000)

        val result = expirePastDueReminders(listOf(expired, future), now)

        assertEquals(listOf("expired"), result.expiredIds)
        assertEquals(REMINDER_CANCELLED, result.reminders[0].status)
        assertEquals(future, result.reminders[1])
    }
}