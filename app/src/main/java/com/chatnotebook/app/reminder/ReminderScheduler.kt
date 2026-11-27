package com.chatnotebook.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.chatnotebook.app.data.REMINDER_SCHEDULED
import com.chatnotebook.app.data.ReminderRecord

/** 用一次性精确闹钟安排提醒；没有精确闹钟权限时降级为非精确调度。 */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** 返回 true 表示已按精确闹钟调度，false 表示降级为非精确调度。 */
    fun schedule(reminder: ReminderRecord): Boolean {
        val pending = broadcast(reminder.id)
        alarms.cancel(pending)
        val exact = canScheduleExact()
        if (exact) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.triggerAt, null), pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pending)
        }
        return exact
    }

    fun cancel(reminderId: String) {
        alarms.cancel(broadcast(reminderId))
    }

    /** 幂等重排：只为待触发且时间未到的提醒注册闹钟，其余取消。 */
    fun rescheduleAll(reminders: List<ReminderRecord>, now: Long = System.currentTimeMillis()) {
        reminders.forEach { reminder ->
            if (reminder.status == REMINDER_SCHEDULED && reminder.triggerAt > now) {
                schedule(reminder)
            } else {
                cancel(reminder.id)
            }
        }
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun broadcast(reminderId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(reminderId),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_REMINDER)
                .setData(Uri.parse("$REMINDER_URI_PREFIX$reminderId"))
                .putExtra(EXTRA_REMINDER_ID, reminderId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ACTION_REMINDER = "com.chatnotebook.app.action.REMINDER"
        const val REMINDER_URI_PREFIX = "chatnotebook-reminder://"
        const val EXTRA_REMINDER_ID = "com.chatnotebook.app.extra.REMINDER_ID"

        /** 由提醒 id 稳定派生 requestCode；data URI 共同保证 PendingIntent 唯一。 */
        fun requestCode(reminderId: String): Int = reminderId.hashCode()

        /**
         * 取出广播里的提醒 id：优先 extra，其次 data 的 authority（id 直接放在 URI 主机位），
         * 最后回退到路径末段；都取不到时返回 null。
         */
        fun reminderIdOf(intent: Intent): String? {
            intent.getStringExtra(EXTRA_REMINDER_ID)?.takeIf { it.isNotBlank() }?.let { return it }
            val uri = intent.data ?: return null
            uri.authority?.takeIf { it.isNotBlank() }?.let { return it }
            return uri.lastPathSegment?.takeIf { it.isNotBlank() }
        }
    }
}