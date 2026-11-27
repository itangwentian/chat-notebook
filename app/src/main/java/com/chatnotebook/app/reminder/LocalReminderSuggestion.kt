package com.chatnotebook.app.reminder

/** 本地规则从消息中识别出的日程建议；只存在于内存，不写入记录文件。 */
data class LocalReminderSuggestion(
    val messageId: String,
    val contentVersion: Int,
    val triggerAt: Long,
    val phrase: String,
)