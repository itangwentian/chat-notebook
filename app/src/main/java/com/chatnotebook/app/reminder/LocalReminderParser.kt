package com.chatnotebook.app.reminder

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 本地解析得到的提醒时间与原文片段。 */
data class DetectedReminder(val triggerAt: Long, val phrase: String)

/**
 * 从消息文本中识别常见中文日程表达。纯本地规则，不访问网络。
 */
object LocalReminderParser {
    const val MAX_DELAY_MS = 365L * 24 * 60 * 60 * 1_000
    private const val PERIOD_PATTERN = "凌晨|早上|上午|中午|下午|傍晚|晚上"
    private val RELATIVE = Regex("""(\d{1,4}|[零一二两三四五六七八九十]{1,3})\s*(分钟|分|小时|钟头|天)\s*后""")
    private val RELATIVE_DAY = Regex("""大后天|后天|明天|明日|今天|今日""")
    private val WEEKDAY = Regex("""(?:周|星期|礼拜)([一二三四五六日天1-7])""")
    private val MONTH_DAY = Regex("""(?:(\d{4})年)?(\d{1,2})月(\d{1,2})(?:日|号)""")
    private val COLON_TIME = Regex("""($PERIOD_PATTERN)?\s*(\d{1,2}):(\d{2})(?!\d)""")
    private val POINT_TIME = Regex(
        """($PERIOD_PATTERN)?\s*(\d{1,2}|[零一二两三四五六七八九十]{1,3})\s*点\s*(?:(半)|(\d{1,2})\s*分?)?""",
    )

    fun detect(
        content: String,
        sentAt: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): DetectedReminder? {
        if (content.isBlank()) return null
        val text = normalize(content)
        parseRelative(text, sentAt, now, zone)?.let { return it }

        val time = findTime(text) ?: return null
        val rule = findDate(text)
        val sentDate = instantDate(sentAt, zone)
        val date = if (rule == null) {
            val today = LocalDateTime.of(sentDate, time.value).atZone(zone).toInstant().toEpochMilli()
            if (today > now) sentDate else sentDate.plusDays(1)
        } else {
            resolveDate(rule, time.value, sentDate, now, zone) ?: return null
        }
        val triggerAt = LocalDateTime.of(date, time.value)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        if (!inRange(triggerAt, now)) return null

        val phraseStart = minOf(rule?.range?.first ?: time.range.first, time.range.first)
        val phraseEnd = maxOf(rule?.range?.last ?: time.range.last, time.range.last)
        return DetectedReminder(triggerAt, text.substring(phraseStart, phraseEnd + 1).trim())
    }

    private fun parseRelative(text: String, sentAt: Long, now: Long, zone: ZoneId): DetectedReminder? {
        val match = RELATIVE.find(text) ?: return null
        val count = parseCount(match.groupValues[1]) ?: return null
        if (count <= 0) return null
        val unit = match.groupValues[2]
        val triggerAt = when (unit) {
            "分钟", "分" -> sentAt + count * 60_000L
            "小时", "钟头" -> sentAt + count * 60L * 60_000L
            else -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(sentAt), zone)
                .plusDays(count.toLong())
                .toInstant()
                .toEpochMilli()
        }
        if (!inRange(triggerAt, now)) return null
        return DetectedReminder(triggerAt, match.value.trim())
    }

    private fun findTime(text: String): TimeMatch? {
        val colon = COLON_TIME.find(text)?.let { match ->
            val hour = match.groupValues[2].toIntOrNull() ?: return@let null
            val minute = match.groupValues[3].toIntOrNull() ?: return@let null
            val normalizedHour = normalizeHour(hour, match.groupValues[1]) ?: return@let null
            if (minute !in 0..59) return@let null
            TimeMatch(LocalTime.of(normalizedHour, minute), match.range)
        }
        val point = POINT_TIME.find(text)?.let { match ->
            val hour = parseCount(match.groupValues[2]) ?: return@let null
            val normalizedHour = normalizeHour(hour, match.groupValues[1]) ?: return@let null
            val minute = when {
                match.groupValues[3] == "半" -> 30
                match.groupValues[4].isNotEmpty() -> match.groupValues[4].toIntOrNull() ?: return@let null
                else -> 0
            }
            if (minute !in 0..59) return@let null
            TimeMatch(LocalTime.of(normalizedHour, minute), match.range)
        }
        return when {
            colon == null -> point
            point == null -> colon
            colon.range.first <= point.range.first -> colon
            else -> point
        }
    }

    private fun findDate(text: String): DateRule? {
        val relative = RELATIVE_DAY.find(text)?.let { match ->
            val days = when (match.value) {
                "大后天" -> 3L
                "后天" -> 2L
                "明天", "明日" -> 1L
                else -> 0L
            }
            RelativeDayRule(days, match.range)
        }
        val weekday = WEEKDAY.find(text)?.let { match ->
            val target = weekdayValue(match.groupValues[1]) ?: return@let null
            WeekdayRule(target, match.range)
        }
        val monthDay = MONTH_DAY.find(text)?.let { match ->
            val month = match.groupValues[2].toIntOrNull() ?: return@let null
            val day = match.groupValues[3].toIntOrNull() ?: return@let null
            if (month !in 1..12 || day !in 1..31) return@let null
            MonthDayRule(
                year = match.groupValues[1].toIntOrNull(),
                month = month,
                day = day,
                range = match.range,
            )
        }
        return listOfNotNull(relative, weekday, monthDay).minByOrNull { it.range.first }
    }

    private fun resolveDate(
        rule: DateRule,
        time: LocalTime,
        sentDate: LocalDate,
        now: Long,
        zone: ZoneId,
    ): LocalDate? = when (rule) {
        is RelativeDayRule -> sentDate.plusDays(rule.days)
        is WeekdayRule -> {
            val ahead = (rule.dayOfWeek - sentDate.dayOfWeek.value + 7) % 7
            val base = sentDate.plusDays(ahead.toLong())
            if (LocalDateTime.of(base, time).atZone(zone).toInstant().toEpochMilli() > now) {
                base
            } else {
                base.plusWeeks(1)
            }
        }
        is MonthDayRule -> {
            val year = rule.year ?: sentDate.year
            val base = try {
                LocalDate.of(year, rule.month, rule.day)
            } catch (_: DateTimeException) {
                return null
            }
            if (LocalDateTime.of(base, time).atZone(zone).toInstant().toEpochMilli() > now) {
                base
            } else if (rule.year == null) {
                base.plusYears(1)
            } else {
                null
            }
        }
    }

    private fun inRange(triggerAt: Long, now: Long): Boolean =
        triggerAt > now && triggerAt - now <= MAX_DELAY_MS

    private fun normalizeHour(hour: Int, period: String): Int? {
        if (period.isEmpty()) return hour.takeIf { it in 0..23 }
        if (hour !in 0..12) return null
        return when (period) {
            "凌晨" -> if (hour == 12) 0 else hour
            "早上", "上午" -> hour
            "中午" -> if (hour in 1..10) hour + 12 else hour
            else -> if (hour in 1..11) hour + 12 else hour
        }
    }

    private fun weekdayValue(value: String): Int? = when (value) {
        "一" -> 1
        "二" -> 2
        "三" -> 3
        "四" -> 4
        "五" -> 5
        "六" -> 6
        "日", "天", "7" -> 7
        "1" -> 1
        "2" -> 2
        "3" -> 3
        "4" -> 4
        "5" -> 5
        "6" -> 6
        else -> null
    }

    private fun parseCount(token: String): Int? {
        if (token.isEmpty()) return null
        if (token.all { it.isDigit() }) return token.toIntOrNull()
        if (token.length == 1) return chineseDigit(token[0])
        val index = token.indexOf('十')
        if (index < 0) return null
        val tens = if (index == 0) 1 else chineseDigit(token[0]) ?: return null
        val ones = if (index == token.lastIndex) 0 else {
            if (token.length != index + 2) return null
            chineseDigit(token[index + 1]) ?: return null
        }
        return tens * 10 + ones
    }

    private fun chineseDigit(char: Char): Int? = when (char) {
        '零' -> 0
        '一' -> 1
        '二', '两' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        else -> null
    }

    private fun instantDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    private fun normalize(text: String): String = buildString(text.length) {
        for (char in text) {
            when {
                char in '０'..'９' -> append('0' + (char - '０'))
                char == '：' -> append(':')
                char == '　' -> append(' ')
                else -> append(char)
            }
        }
    }

    private data class TimeMatch(val value: LocalTime, val range: IntRange)

    private sealed interface DateRule {
        val range: IntRange
    }

    private data class RelativeDayRule(val days: Long, override val range: IntRange) : DateRule
    private data class WeekdayRule(val dayOfWeek: Int, override val range: IntRange) : DateRule
    private data class MonthDayRule(
        val year: Int?,
        val month: Int,
        val day: Int,
        override val range: IntRange,
    ) : DateRule
}