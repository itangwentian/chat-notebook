package com.chatnotebook.app.reminder

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTimeDefaultsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun defaultsToTenMinutesLaterWithSecondsCleared() {
        val now = LocalDateTime.of(2026, 3, 4, 9, 15, 42, 123_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val result = defaultReminderInstant(now, null, zone).atZone(zone).toLocalDateTime()

        assertEquals(LocalDateTime.of(2026, 3, 4, 9, 25), result)
    }

    @Test
    fun futureSuggestionIsPreferred() {
        val now = LocalDateTime.of(2026, 3, 4, 9, 15).atZone(zone).toInstant().toEpochMilli()
        val suggested = LocalDateTime.of(2026, 3, 4, 15, 0).atZone(zone).toInstant().toEpochMilli()

        val result = defaultReminderInstant(now, suggested, zone)

        assertEquals(Instant.ofEpochMilli(suggested), result)
    }

    @Test
    fun pastSuggestionFallsBackToTenMinutesLater() {
        val now = LocalDateTime.of(2026, 3, 4, 9, 15, 30).atZone(zone).toInstant().toEpochMilli()
        val suggested = LocalDateTime.of(2026, 3, 4, 8, 0).atZone(zone).toInstant().toEpochMilli()

        val result = defaultReminderInstant(now, suggested, zone).atZone(zone).toLocalDateTime()

        assertEquals(LocalDateTime.of(2026, 3, 4, 9, 25), result)
    }

    @Test
    fun wheelValueWrapsInBothDirections() {
        assertEquals(23, reminderWheelValue(23, 24))
        assertEquals(0, reminderWheelValue(24, 24))
        assertEquals(0, reminderWheelValue(0, 24))
        assertEquals(23, reminderWheelValue(-1, 24))
        assertEquals(59, reminderWheelValue(59, 60))
        assertEquals(0, reminderWheelValue(60, 60))
    }

    @Test
    fun wheelInitialIndexStaysInsideMiddleCopy() {
        val initial = reminderWheelInitialIndex(24, 23)

        assertEquals(23, reminderWheelValue(initial, 24))
        assertTrue(initial > 0)
        assertTrue(initial < reminderWheelItemCount(24) - 24)
    }
}