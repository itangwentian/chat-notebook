package com.chatnotebook.app.reminder

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.chatnotebook.app.data.REMINDER_CANCELLED
import com.chatnotebook.app.data.REMINDER_FIRED
import com.chatnotebook.app.data.REMINDER_SCHEDULED
import com.chatnotebook.app.data.ReminderRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: ReminderScheduler

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        scheduler = ReminderScheduler(context)
    }

    @Test fun schedulesExactAlarmClockWithUniquePendingIntentPerReminder() {
        val first = reminder("first", triggerAt = 5_000)
        val second = reminder("second", triggerAt = 6_000)

        assertTrue(scheduler.schedule(first))
        assertTrue(scheduler.schedule(second))

        val alarms = shadowOf(alarmManager).scheduledAlarms
        assertEquals(2, alarms.size)
        val firstAlarm = alarms.single { it.triggerAtTime == 5_000L }
        val secondAlarm = alarms.single { it.triggerAtTime == 6_000L }
        assertEquals(5_000L, requireNotNull(firstAlarm.getAlarmClockInfo()).triggerTime)
        assertEquals(6_000L, requireNotNull(secondAlarm.getAlarmClockInfo()).triggerTime)
        assertNotEquals(firstAlarm.operation, secondAlarm.operation)
        assertNotEquals(
            ReminderScheduler.requestCode(first.id),
            ReminderScheduler.requestCode(second.id),
        )

        // 同一提醒重复调度是替换而不是新增。
        assertTrue(scheduler.schedule(first))
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test @Config(sdk = [33]) fun fallsBackToAllowWhileIdleWithoutExactAlarmPermission() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val alarm = reminder("fallback", triggerAt = 9_000)

        assertFalse(scheduler.schedule(alarm))

        val scheduled = shadowOf(alarmManager).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type)
        assertEquals(9_000L, scheduled.triggerAtTime)
        assertTrue(scheduled.isAllowWhileIdle())
        assertNull(scheduled.getAlarmClockInfo())
    }

    @Test fun rescheduleAllIsIdempotentAndSkipsTerminalOrPastReminders() {
        val reminders = listOf(
            reminder("future", triggerAt = 10_000),
            reminder("past", triggerAt = 1_000),
            reminder("fired", triggerAt = 10_000, status = REMINDER_FIRED),
            reminder("cancelled", triggerAt = 10_000, status = REMINDER_CANCELLED),
        )

        scheduler.rescheduleAll(reminders, now = 5_000)
        assertEquals(listOf(10_000L), shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtTime })

        scheduler.rescheduleAll(reminders, now = 5_000)
        assertEquals(listOf(10_000L), shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtTime })
    }

    @Test fun cancelRemovesOnlyTheRequestedAlarm() {
        scheduler.schedule(reminder("keep", triggerAt = 5_000))
        scheduler.schedule(reminder("drop", triggerAt = 6_000))

        scheduler.cancel("drop")

        assertEquals(listOf(5_000L), shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtTime })
    }

    @Test fun alarmIntentCarriesReminderIdInExtraAndAuthority() {
        scheduler.schedule(reminder("reminder-x", triggerAt = 5_000))

        val intent = shadowOf(shadowOf(alarmManager).scheduledAlarms.single().operation).savedIntent

        assertEquals(ReminderScheduler.ACTION_REMINDER, intent.action)
        assertEquals("reminder-x", intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID))
        assertEquals("reminder-x", intent.data?.authority)
    }

    @Test fun reminderIdFallsBackToDataWhenExtraMissing() {
        val legacy = Intent().setData(Uri.parse("${ReminderScheduler.REMINDER_URI_PREFIX}legacy-id"))

        assertEquals("legacy-id", ReminderScheduler.reminderIdOf(legacy))
        assertNull(ReminderScheduler.reminderIdOf(Intent()))
    }

    private fun reminder(id: String, triggerAt: Long, status: String = REMINDER_SCHEDULED) =
        ReminderRecord(id = id, sessionId = "s", triggerAt = triggerAt, createdAt = 1, updatedAt = 1, status = status)
}