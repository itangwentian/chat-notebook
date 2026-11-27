package com.chatnotebook.app.data

/** 保存提醒后的调度结果，用于界面提示精确调度、降级与通知权限状态。 */
data class ReminderSaveOutcome(
    val reminder: ReminderRecord,
    val exact: Boolean,
    val notificationsAllowed: Boolean,
)

/** 主界面提醒预览：时间、跳转目标与单行展示文本。 */
data class ReminderPreview(
    val id: String,
    val sessionId: String,
    /** 绑定消息仍存在时的消息 id；已删除或未绑定时为空，跳转只打开会话。 */
    val messageId: String?,
    val triggerAt: Long,
    /** 已裁剪的展示文本：消息内容优先，为空时退化为会话标题。 */
    val text: String,
    val sessionTitle: String,
)

/** 到点触发时通知所需的最小数据。 */
data class ReminderDelivery(
    val reminderId: String,
    val sessionId: String,
    val sessionTitle: String,
    /** 绑定消息仍存在时的消息 id；消息已删除或未绑定时为空。 */
    val messageId: String?,
    val body: String,
)

internal const val DEFAULT_REMINDER_BODY = "你设置的提醒时间到了"

/** 过期宽限：留出余量，避免抢在到点广播派发之前把提醒取消掉。 */
internal const val REMINDER_EXPIRY_GRACE_MS = 5 * 60_000L

private const val DELIVERY_EARLY_TOLERANCE_MS = 5_000L
private const val REMINDER_BODY_LIMIT = 120

/** 主界面提醒预览的正文上限。 */
internal const val REMINDER_PREVIEW_LIMIT = 80
private val WHITESPACE = Regex("\\s+")

/** 提醒预览文本：压缩空白后截断，内容为空时使用会话标题。 */
internal fun reminderPreviewText(content: String?, sessionTitle: String): String {
    val normalized = content?.replace(WHITESPACE, " ")?.trim().orEmpty()
    return if (normalized.isEmpty()) sessionTitle else normalized.take(REMINDER_PREVIEW_LIMIT)
}

/**
 * 只对“仍待触发且已到点”的提醒返回通知内容；已触发、已取消、未到点或对话已删除都返回 null，
 * 从而让重复投递自然变成空操作。
 */
internal fun reminderDeliveryOf(
    payload: RecordPayload,
    reminderId: String,
    now: Long,
): ReminderDelivery? {
    val reminder = payload.reminders.firstOrNull { it.id == reminderId } ?: return null
    if (reminder.status != REMINDER_SCHEDULED || reminder.triggerAt > now + DELIVERY_EARLY_TOLERANCE_MS) {
        return null
    }
    val session = payload.sessions.firstOrNull { it.id == reminder.sessionId && !it.isDeleted } ?: return null
    val message = reminder.messageId?.let { id ->
        payload.messages.firstOrNull { it.id == id && !it.isDeleted }
    }
    val body = message?.currentContent
        ?.replace(WHITESPACE, " ")
        ?.trim()
        ?.take(REMINDER_BODY_LIMIT)
        ?.takeIf { it.isNotEmpty() }
        ?: DEFAULT_REMINDER_BODY
    return ReminderDelivery(reminder.id, session.id, session.title, message?.id, body)
}

/** 过期移除的结果：更新后的提醒集合，以及需要撤销闹钟的提醒 id。 */
internal data class ReminderExpiry(
    val reminders: List<ReminderRecord>,
    val expiredIds: List<String>,
)

/**
 * 已过点且仍待触发的提醒视为过期，状态改为已取消并从待触发列表中移除；
 * 到点触发过的提醒保持已触发。超出 [REMINDER_EXPIRY_GRACE_MS] 才算过期。
 */
internal fun expirePastDueReminders(
    reminders: List<ReminderRecord>,
    now: Long,
): ReminderExpiry {
    val expired = reminders.filter {
        it.status == REMINDER_SCHEDULED && it.triggerAt <= now - REMINDER_EXPIRY_GRACE_MS
    }
    if (expired.isEmpty()) return ReminderExpiry(reminders, emptyList())
    val ids = expired.map { it.id }.toSet()
    return ReminderExpiry(
        reminders = reminders.map {
            if (it.id in ids) it.copy(status = REMINDER_CANCELLED, updatedAt = now) else it
        },
        expiredIds = expired.map { it.id },
    )
}