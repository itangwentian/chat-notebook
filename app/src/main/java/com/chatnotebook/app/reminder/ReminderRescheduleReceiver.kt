package com.chatnotebook.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chatnotebook.app.data.PersistentChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 重启、改时间或改时区后重建所有待触发提醒的闹钟。 */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = PersistentChatRepository.shared(appContext)
                if (repository.restoreSavedDirectory() == null) return@launch
                repository.rescheduleReminders()
            } catch (_: Exception) {
                // 记录目录不可用时保持现状，等待下次应用启动重排。
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}