package com.chatnotebook.app.reminder

import java.time.Instant
import java.time.ZoneId

/** 滚轮虚拟条目份数：足够长，正常手势与快速滑动都不会触及边界。 */
internal const val REMINDER_WHEEL_LOOP_COPIES = 500

/** 滚轮原始索引对应的值（小时 0-23、分钟 0-59）。 */
internal fun reminderWheelValue(index: Int, count: Int): Int = index.mod(count)

/** 初始定位到中间区段，使首尾两个方向都能继续滚动。 */
internal fun reminderWheelInitialIndex(count: Int, value: Int): Int =
    count * (REMINDER_WHEEL_LOOP_COPIES / 2) + value.mod(count)

internal fun reminderWheelItemCount(count: Int): Int = count * REMINDER_WHEEL_LOOP_COPIES

/**
 * 新建提醒的默认时间：本地日程建议晚于当前时优先使用建议，
 * 否则取当前时间 10 分钟后并清零秒与纳秒。
 */
internal fun defaultReminderInstant(nowMillis: Long, suggestedAt: Long?, zone: ZoneId): Instant =
    if (suggestedAt != null && suggestedAt > nowMillis) {
        Instant.ofEpochMilli(suggestedAt)
    } else {
        Instant.ofEpochMilli(nowMillis).atZone(zone).plusMinutes(10).withSecond(0).withNano(0).toInstant()
    }