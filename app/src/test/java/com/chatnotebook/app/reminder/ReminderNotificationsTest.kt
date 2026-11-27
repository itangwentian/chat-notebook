package com.chatnotebook.app.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import com.chatnotebook.app.MainActivity
import com.chatnotebook.app.data.ReminderDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ReminderNotificationsTest {
    private val delivery = ReminderDelivery("r1", "s1", "旅行", "m1", "到点了")
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @Test fun postedNotificationOpensConversationAndConsumesExtraOnlyOnce() {
        ReminderNotifications.show(context, delivery)

        val posted = shadowOf(manager).getNotification(delivery.reminderId.hashCode())
        assertEquals("旅行", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("到点了", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        val intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("s1", intent.getStringExtra(ReminderNotifications.EXTRA_SESSION_ID))
        assertEquals("m1", intent.getStringExtra(ReminderNotifications.EXTRA_MESSAGE_ID))

        assertEquals(ReminderNotifications.Route("s1", "m1"), ReminderNotifications.consumeRoute(intent))
        assertNull(ReminderNotifications.consumeRoute(intent))
        assertNull(ReminderNotifications.consumeRoute(null))
    }

    @Test fun notificationWithoutMessageOnlyRoutesToConversation() {
        ReminderNotifications.show(context, delivery.copy(messageId = null))

        val posted = shadowOf(manager).getNotification(delivery.reminderId.hashCode())
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertNull(intent.getStringExtra(ReminderNotifications.EXTRA_MESSAGE_ID))
        assertEquals(ReminderNotifications.Route("s1", null), ReminderNotifications.consumeRoute(intent))
    }

    @Test fun channelIsCreatedWithHighImportance() {
        ReminderNotifications.show(context, delivery)

        val channel = shadowOf(manager).notificationChannels.single()
        assertEquals(ReminderNotifications.CHANNEL_ID, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), channel.sound)
        assertTrue(channel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
    }

    /** 渠道设置创建后不可修改，升级版本必须换新 id，否则用户设备会一直保持旧的无声配置。 */
    @Test fun channelUpgradeDeletesLegacyChannels() {
        manager.createNotificationChannel(
            NotificationChannel("reminders", "旧渠道", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel("reminders_v2", "旧渠道", NotificationManager.IMPORTANCE_HIGH),
        )

        ReminderNotifications.ensureChannel(context)

        assertEquals(
            listOf(ReminderNotifications.CHANNEL_ID),
            shadowOf(manager).notificationChannels.map { it.id },
        )
    }

    @Test fun disabledNotificationsSkipPosting() {
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(ReminderNotifications.allowed(context))
        ReminderNotifications.show(context, delivery)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}