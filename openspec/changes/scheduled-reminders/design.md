# Design

## Context

动机见 `proposal.md`，行为契约见 `specs/scheduled-reminders/spec.md` 与 `specs/lan-note-sync/spec.md`。

当前实现的关键约束：

- 记录以文件为准：`Snapshot.kt` 的 `RecordPayload` 对应 `records-*.json` 快照，`SessionFileCodec` 把每个对话拆成 `session-*.json`；`RecordRepository.restore()` 从文件重建 Room 索引。任何新增记录都必须先落文件。
- 快照与会话文件都带 SHA-256 校验，校验基于 JSON 序列化文本，因此新增字段必须保证旧文件重新编码后字节不变。
- 局域网同步以 `SyncConversation` 为传输单位，摘要基于其规范 JSON；冲突合并由 `SyncConflicts.mergeConversations` 做并集。
- 单 Activity（`MainActivity`）+ Compose，`ChatViewModel` 维护当前对话；`PersistentChatRepository` 是单例。
- `minSdk = 26`、`targetSdk = 35`，未引入 WorkManager 与推送 SDK。

## Goals / Non-Goals

**Goals:**

- 提醒数据随对话记录持久化，可恢复、可同步、可测试。
- 到点触发尽可能准时，权限受限时给出可用降级与明确提示。
- 重启、改时间、重装后提醒仍可触发，且不重复通知。

**Non-Goals:**

- 不实现周期重复提醒、贪睡、自定义铃声。
- 不接入服务端推送、FCM、厂商通道或第三方推送 SDK。
- 不保证用户在系统设置中“强制停止”应用后仍能被闹钟唤醒，这是 Android 平台限制，只做说明与引导。
- 不为每个厂商 ROM 实现自动跳转自启动设置页，仅提供文字指引。

## Decisions

### 1. 数据模型：提醒作为对话的附属记录

新增 `ReminderRecord(id, sessionId, messageId?, triggerAt, createdAt, updatedAt, status)`，`status` 取 `scheduled` / `fired` / `cancelled`。加入 `RecordPayload` 与 `SessionFilePayload`，由 `SessionFileCodec.split/combine` 随对话归属。

- 新字段声明在类末尾并使用 `@EncodeDefault(EncodeDefault.Mode.NEVER)` + 默认空列表，旧文件解码后为空、重新编码时省略该字段，SHA-256 校验不变。
- `SnapshotCodec` 与 `SessionFileCodec.validateSession` 增加校验：`sessionId` 必须属于该会话、`messageId` 存在时必须属于同一会话、`triggerAt > 0`、状态取值合法、同一会话内 `id` 唯一。
- Room 新增 `reminders` 表与 `MIGRATION_8_9`，与现有表一样仅作可重建索引，写入顺序仍是“先文件、后索引”。

替代方案：单独的 `reminders-*.json`。放弃原因是需要扩展 `SnapshotStore` 的文件名白名单与 `restore()` 扫描逻辑，还要在同步之外单独处理恢复，复杂度高于把提醒挂在会话上。

替代方案：只存 Room/SharedPreferences。放弃原因是违反“记录文件在用户目录中可恢复”的项目约束。

### 2. 调度：AlarmManager 精确闹钟 + 广播接收器

`ReminderScheduler` 使用 `AlarmManager.setAlarmClock()` 安排一次性闹钟：

- `setAlarmClock` 不受 Doze 抑制，最接近用户预期，代价是状态栏出现闹钟图标。
- Android 12+ 需要 `SCHEDULE_EXACT_ALARM`；先调用 `canScheduleExactAlarms()`，不可用时降级为 `setAndAllowWhileIdle()` 的不精确调度，并返回“已降级”结果供界面提示。
- `PendingIntent.getBroadcast` 的 `requestCode` 由提醒 id 稳定派生，`FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`，重复调度时先 `cancel` 再 `set`，天然幂等。
- 只调度 `status == scheduled && triggerAt > now` 的记录。

替代方案：WorkManager。放弃原因是周期任务最小间隔 15 分钟且无法保证准点，一次性任务同样受 Doze 批处理影响。

### 3. 触发与状态流转

`ReminderReceiver` 收到广播后：

1. 用 `goAsync()` 在 IO 协程中读取提醒，若状态已不是 `scheduled` 或时间未到则直接结束，避免重复通知。
2. 构建通知并发出（通知渠道 `reminders`，重要性 HIGH）。
3. 把状态更新为 `fired` 并写入记录文件，再更新 Room 索引。
4. 文件写入失败时仍保留已发出的通知，但记录状态保持 `scheduled`，下次重排会再次触发；用“先查状态再通知”保证同一时刻不会重复弹两次。

取消提醒直接把状态改为 `cancelled` 并取消对应闹钟；`fired` 与 `cancelled` 都是终态，不再排入闹钟。

过期清理：`rescheduleReminders()` 在重排前先做一次幂等清理，把 `status == scheduled` 且 `triggerAt` 已过去超过 5 分钟的提醒改为 `cancelled` 并撤销闹钟。宽限期用于避免与正在派发的到点广播竞争——正常到点的提醒会先写成 `fired`，剩下的 `scheduled` 只可能是真正错过的记录。提醒面板只列出 `triggerAt` 晚于当前时间的待触发提醒。

### 4. 重排时机

以下入口调用同一个幂等 `rescheduleAll()`：

- `MainActivity.onStart` / 记录目录打开完成后；
- `BOOT_COMPLETED`、`TIME_SET`、`TIMEZONE_CHANGED` 广播接收器；
- 同步应用合并结果后（提醒可能来自另一设备）。

重排会先取消该提醒已注册的 PendingIntent 再重新注册，重复调用不会产生多条闹钟。

### 5. 通知与跳转

通知正文：对话标题 + 绑定消息的 `currentContent` 摘要（截断）；消息已删除或未绑定消息时用通用文案。`contentIntent` 指向 `MainActivity`，附带 `EXTRA_SESSION_ID` 与（消息仍存在时的）`EXTRA_MESSAGE_ID`。`MainActivity` 设为 `singleTop`，在 `onCreate` 与 `onNewIntent` 中读取这两个 extra 并调用 `ChatViewModel.openSessionFromNotification(sessionId, messageId)`；读取后清除 extra，避免旋转或返回时重复跳转。

消息定位与闪烁：`ChatViewModel` 复用既有的 `PendingScroll` 机制滚动到目标消息（会话切换时等消息加载完成再滚动），并在状态中写入 `highlightMessageId`，5 秒后自动清空，避免再次进入消息列表时重复闪烁；气泡用无限往返动画在正常底色与高亮色之间插值。已删除或未绑定消息时只切对话，不定位不闪烁。

替代方案：独立的提醒详情 Activity。放弃原因是会复制对话加载与权限门槛逻辑，且与现有单 Activity 导航冲突。

### 6. 权限流程

- 通知：Android 13+ 首次设置提醒时通过 `ActivityResultContracts.RequestPermission` 请求 `POST_NOTIFICATIONS`；拒绝后弹说明，提醒照常保存。
- 精确闹钟：`canScheduleExactAlarms()` 为 false 时先说明“可能延迟”，用户确认后跳转 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`，返回前台时重新检查并重排。
- 不静默失败：每次保存提醒都返回调度结果（精确 / 降级 / 无通知权限），由界面用 Snackbar 或对话框说明。
- 国产 ROM 后台限制只在提示文案中说明“允许自启动、关闭电池优化”，不做厂商判断与自动跳转。

### 7. 界面入口

- 消息操作菜单新增“提醒”，默认带上该消息；对话抽屉的会话菜单不提供提醒入口。
- 提醒面板使用 Material 3 的日期选择器，时分改用上下滚轮（`LazyColumn` + 吸附到整行），避免表盘在小屏上拖动不便；只接受未来时间；提供“1 小时后 / 明天 09:00 / 后天 09:00”快捷选项，同一对话的待触发提醒在面板中列出，可取消。
- 待触发提醒的消息只用底色提示：`ChatUiState` 从当前对话的提醒流派生 `reminderMessageIds`（仅 `status == scheduled` 且绑定消息的提醒，同消息多条只保留一个 id），`MessageBubble` 在 `hasReminder` 时把常态底色改为 `lerp(baseColor, errorContainer, 0.5f)`，略微偏红且不改变气泡尺寸与文字换行；通知定位闪烁时在偏红底色与高亮色之间插值，避免动画结束时跳色。取消、触发或过期后提醒流更新，偏红底色随之消失。不在气泡上绘制闹钟角标，避免遮挡内容。
- 界面只通过 `ChatRepository` 的新方法读写，不直接持有调度器；调度与通知在数据层和 Android 组件层完成。

### 8. 同步合并

`SyncConversation` 增加 `reminders`，`toSyncConversation` / `toSession` 双向转换；`mergeConversations` 用与消息相同的并集策略，按 `id` 取 `updatedAt` 较新者，相同时按序列化内容确定性选择。`SyncConversation.canonical()` 对提醒按 `id` 排序，保证两端摘要一致。

`LAN_SYNC_VERSION` 从 1 提升到 2：新旧版本对 `SyncConversation` 的 JSON 结构理解不同，混合配对必须明确失败，不能静默丢提醒。

### 9. 提示强度：渠道升级、亮屏与全屏意图

- 通知渠道的声音、震动、重要性在创建后不可修改，旧渠道 `reminders`、`reminders_v2` 升级为 `reminders_v3`（`IMPORTANCE_HIGH`、默认提示音、震动模式、`VISIBILITY_PUBLIC`），创建新渠道时删除全部旧渠道；已安装设备上残留的无声/低重要性渠道只能靠更换渠道 id 恢复默认行为。
- 屏幕熄灭时用 `PowerManager` 短时亮屏锁点亮屏幕：`SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`（`WakeLock` 没有 `setTurnScreenOn`，该 API 属于 `Activity`），10 秒后自动释放，不长期持有；需要 `WAKE_LOCK` 权限。锁屏之上的跳转由 `MainActivity` 的 `setShowWhenLocked` / `setTurnScreenOn` 负责。
- 声明 `USE_FULL_SCREEN_INTENT`；Android 14+ 用 `canUseFullScreenIntent()` 判断，已授权时附带全屏意图，未授权时只保留高优先级横幅通知，不阻断提醒。
- 由提醒打开的 `MainActivity` 设置 `setShowWhenLocked` / `setTurnScreenOn`，跳转到目标对话后清除，避免应用之后长期停留在锁屏之上。
- 静音、勿扰、用户关闭渠道时仍可能无声无震动，这是系统策略，应用不绕过，只在记录中标记已触发。

## Risks / Trade-offs

- [国产 ROM 清理后台导致闹钟不触发] → 使用 `setAlarmClock` 提高存活率；设置提醒时提示开启自启动、关闭电池优化；在验证记录中如实说明平台限制。
- [用户拒绝精确闹钟权限] → 降级为非精确调度并明确提示延迟；不阻止保存。
- [通知权限被拒后提醒“静默”] → 保存时返回调度结果并提示；权限恢复后仍会到点通知。
- [进程被杀后广播重建仓库较慢] → 接收器只读单条提醒所需的最新快照，避免全量同步；超时则放弃本次通知，状态保持 `scheduled`。
- [文件写入失败造成状态未更新] → 通知已发出、状态保持 `scheduled`，重排会重试；接收器内先用状态与时间做幂等判断。
- [同步版本升级后旧设备无法配对] → 版本号显式提升，失败时提示两端升级；不兼容旧版本是预期行为。
- [提醒随对话同步可能在不同设备各响一次] → 任一端触发后 `fired` 状态会通过同步传播，另一端重排时跳过；网络不同步期间可能各响一次，作为已知取舍。

## Migration Plan

- 文件格式：新增字段可缺省，旧快照与会话文件保持可读、校验值不变；新版本写出的文件旧版本无法解析新增字段（`ignoreUnknownKeys = false`），因此升级是单向的。
- 数据库：Room 版本 8 → 9，仅新增 `reminders` 表；迁移失败时沿用现有“从文件重建索引”的恢复路径。
- 同步：`LAN_SYNC_VERSION` 升至 2；发布说明要求两端同时升级。
- 回滚：应用回滚后提醒文件字段会被旧版本视为未知字段而拒绝加载，需先导出或接受提醒数据不可读；数据库回滚依赖重建索引，不保留提醒。