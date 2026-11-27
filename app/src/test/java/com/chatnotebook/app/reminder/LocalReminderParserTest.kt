package com.chatnotebook.app.reminder

import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalReminderParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-26 是星期四。 */
    private val now = epoch("2026-11-26T10:00:00+08:00")

    @Test
    fun tomorrowWithChinesePointTime() {
        val result = detect("明天下午三点提醒我交周报")

        assertNotNull(result)
        assertEquals(epoch("2026-11-27T15:00:00+08:00"), result?.triggerAt)
        assertEquals("明天下午三点", result?.phrase)
    }

    @Test
    fun dayAfterTomorrowWithColonTime() {
        val result = detect("后天上午9:30开会")

        assertEquals(epoch("2026-11-28T09:30:00+08:00"), result?.triggerAt)
    }

    @Test
    fun threeDaysLaterWithEveningTime() {
        val result = detect("大后天晚上8点交房租")

        assertEquals(epoch("2026-11-29T20:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun weekdayLaterThisWeek() {
        val result = detect("周五下午两点评审")

        assertEquals(epoch("2026-11-27T14:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun weekdayAlreadyPassedRollsToNextWeek() {
        val result = detect("周三下午两点评审")

        assertEquals(epoch("2026-12-02T14:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun weekdayTodayUsesLaterTime() {
        val result = detect("周四下午两点评审")

        assertEquals(epoch("2026-11-26T14:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun worshipDayIsSunday() {
        val result = detect("礼拜天下午3点")

        assertEquals(epoch("2026-11-29T15:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun monthDayLaterThisYear() {
        val result = detect("12月5日上午10点体检")

        assertEquals(epoch("2026-12-05T10:00:00+08:00"), result?.triggerAt)
        assertEquals("12月5日上午10点", result?.phrase)
    }

    @Test
    fun monthDayAlreadyPassedRollsToNextYear() {
        val result = detect("1月5日9点体检")

        assertEquals(epoch("2027-01-05T09:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun explicitYearIsUsed() {
        val result = detect("2027年3月1日晚上7点体检")

        assertEquals(epoch("2027-03-01T19:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun timeOnlyTodayWhenStillAhead() {
        val result = detect("11点开会")

        assertEquals(epoch("2026-11-26T11:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun timeOnlyUsesTomorrowWhenAlreadyPassed() {
        val result = detect("3点开会")

        assertEquals(epoch("2026-11-27T03:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun relativeMinutesFromSentAt() {
        val result = detect("30分钟后提醒我", sentAt = now)

        assertEquals(now + 30 * 60_000L, result?.triggerAt)
        assertEquals("30分钟后", result?.phrase)
    }

    @Test
    fun relativeHoursWithChineseNumber() {
        val result = detect("两小时后提醒我", sentAt = now)

        assertEquals(now + 2 * 60 * 60_000L, result?.triggerAt)
    }

    @Test
    fun relativeDaysKeepsSentTime() {
        val result = detect("三天后提醒我", sentAt = now)

        assertEquals(epoch("2026-11-29T10:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun relativeMinutesCountedFromPastSentAt() {
        val sentAt = now - 10 * 60_000L
        val result = detect("30分钟后提醒我", sentAt = sentAt)

        assertEquals(now + 20 * 60_000L, result?.triggerAt)
    }

    @Test
    fun dateWithoutTimeYieldsNothing() {
        assertNull(detect("明天开会讨论预算"))
    }

    @Test
    fun pastTodayTimeYieldsNothing() {
        assertNull(detect("今天上午9点交材料"))
    }

    @Test
    fun beyondOneYearYieldsNothing() {
        assertNull(detect("2027年12月31日10点交材料"))
    }

    @Test
    fun invalidMinuteYieldsNothing() {
        assertNull(detect("下午3:75开会"))
    }

    @Test
    fun firstCompleteMatchWins() {
        val result = detect("明天下午2点或者下午4点开会")

        assertEquals(epoch("2026-11-27T14:00:00+08:00"), result?.triggerAt)
        assertEquals("明天下午2点", result?.phrase)
    }

    @Test
    fun chineseHalfHour() {
        val result = detect("明天下午两点半开会")

        assertEquals(epoch("2026-11-27T14:30:00+08:00"), result?.triggerAt)
    }

    @Test
    fun fullWidthDigitsAreNormalized() {
        val result = detect("明天下午３点开会")

        assertEquals(epoch("2026-11-27T15:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun noonHourIsConverted() {
        val result = detect("明天中午1点开会")

        assertEquals(epoch("2026-11-27T13:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun midnightTwelveIsConvertedToZero() {
        val result = detect("明天凌晨12点开会")

        assertEquals(epoch("2026-11-27T00:00:00+08:00"), result?.triggerAt)
    }

    @Test
    fun zeroCountRelativeIsIgnored() {
        assertNull(detect("0分钟后开会", sentAt = now))
    }

    @Test
    fun blankContentYieldsNothing() {
        assertNull(detect("   ", sentAt = now))
    }

    private fun detect(content: String, sentAt: Long = now): DetectedReminder? =
        LocalReminderParser.detect(content, sentAt = sentAt, now = now, zone = zone)

    private fun epoch(value: String): Long =
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
}