package com.chatnotebook.app.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import com.chatnotebook.app.MainActivity
import com.chatnotebook.app.R
import com.chatnotebook.app.data.ReminderDelivery

/** 提醒通知渠道与通知展示；通知权限缺失时静默跳过，不丢记录。 */
object ReminderNotifications {
    const val CHANNEL_ID = "reminders_v3"

    /** 早期渠道的声音/震动/重要性创建后无法修改，创建新渠道时一并删除。 */
    private val LEGACY_CHANNEL_IDS = listOf("reminders", "reminders_v2")
    private const val WAKE_LOCK_TAG = "chatnotebook:reminder-wake"
    private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    const val EXTRA_SESSION_ID = "com.chatnotebook.app.extra.SESSION_ID"
    const val EXTRA_MESSAGE_ID = "com.chatnotebook.app.extra.MESSAGE_ID"

    /** 通知点击后的跳转目标；消息已删除或未绑定时 [messageId] 为空。 */
    data class Route(val sessionId: String, val messageId: String?)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 旧渠道的声音与震动设置无法修改，升级时用新渠道替换。
        LEGACY_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            },
        )
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    /** 读取并清除通知意图中的跳转目标；同一意图二次读取返回 null，避免重复跳转。 */
    fun consumeRoute(intent: Intent?): Route? {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return null
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        intent.removeExtra(EXTRA_SESSION_ID)
        intent.removeExtra(EXTRA_MESSAGE_ID)
        return Route(sessionId, messageId)
    }

    fun show(context: Context, delivery: ReminderDelivery) {
        if (!allowed(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val contentIntent = PendingIntent.getActivity(
            context,
            delivery.reminderId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_SESSION_ID, delivery.sessionId)
                delivery.messageId?.let { putExtra(EXTRA_MESSAGE_ID, it) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(delivery.sessionTitle)
            .setContentText(delivery.body)
            .setStyle(Notification.BigTextStyle().bigText(delivery.body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent()) {
            builder.setFullScreenIntent(contentIntent, true)
        }
        wakeScreen(context)
        manager.notify(delivery.reminderId.hashCode(), builder.build())
    }

    /** 屏幕熄灭时短时点亮，让提醒直接出现在锁屏上；锁在 10 秒后自动释放。 */
    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        // WakeLock 没有 setTurnScreenOn；点亮屏幕只能靠这两种已废弃的亮屏锁。
        @Suppress("DEPRECATION")
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKE_LOCK_TAG,
        )
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }
}