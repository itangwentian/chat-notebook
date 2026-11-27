package com.chatnotebook.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chatnotebook.app.data.PersistentChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 闹钟触发：先校验状态与时间，发通知后再把提醒记为已触发。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return
        val reminderId = ReminderScheduler.reminderIdOf(intent) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = PersistentChatRepository.shared(appContext)
                if (repository.restoreSavedDirectory() == null) return@launch
                val delivery = repository.reminderDelivery(reminderId) ?: return@launch
                ReminderNotifications.show(appContext, delivery)
                repository.markReminderFired(reminderId)
            } catch (_: Exception) {
                // 保持 scheduled，应用下次启动或重排时再触发。
            } finally {
                pending.finish()
            }
        }
    }
}