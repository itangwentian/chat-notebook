# Design

## Context

当前 `ChatViewModel` 初始化时 `directoryReady` 为 `false`，同时在 `init` 中异步调用 `restoreSavedDirectory()`；`ChatScreen` 直接根据 `directoryReady` 展示目录提示，因此已保存目录的用户会先看到“选择目录”再看到笔记。

提醒相关能力已存在：`ReminderRecord` 存于本地文件并在 Room 中有 `reminders` 索引表，`RecordDao` 已有 `observeScheduledReminders()`；`ReminderDialog` 使用 `ReminderTimeWheelDialog` 的 `LazyColumn` 滚轮；提醒目前只按当前对话订阅，主界面没有全局入口。

## Goals / Non-Goals

**Goals:**

- 已保存目录启动时只展示加载态，恢复完成后直接显示笔记。
- 时、分滚轮可首尾循环且保持吸附选择。
- 新建提醒默认 10 分钟后，日程建议优先。
- 主界面可查看、取消、跳转全部待触发提醒。

**Non-Goals:**

- 不实现重复提醒（用户明确不需要每天/每周/每月循环）。
- 不修改提醒文件格式、同步协议或 Room 表结构。
- 不改动通知调度方式（仍为一次性精确闹钟，失败时降级）。

## Decisions

### 1. 启动用显式“恢复中”状态代替三态目录枚举

在 `ChatRepository` 增加同步的 `hasSavedDirectory(): Boolean`（读取既有 `tree_uri` 偏好），`ChatUiState` 增加 `restoringDirectory`，初始值为该方法的返回值。`init` 中 `restoreSavedDirectory()` 完成后将其置为 `false`；无保存目录时初始即为 `false`。

界面条件改为“未就绪且未在恢复”才展示目录提示与顶部目录快捷入口；恢复中且消息为空时展示加载指示，而不是“暂无消息/请先选择目录”。

- 选择理由：改动面小，不改变既有 `directoryReady` 语义与通知侧调用。
- 备选方案：把 `directoryReady` 改为 `Loading/Ready/Unavailable` 三态枚举。语义更清晰，但会波及 `LanSyncManager`、通知 Receiver 与多处判断，超出本次范围。

### 2. 滚轮用虚拟长列表实现循环

将 `ReminderWheelColumn` 的 `items(count)` 改为足够长的虚拟条目（如 `count * LOOP_COPIES`），初始定位到中间区段，显示值与选中值均用 `index % count` 映射；吸附、居中高亮逻辑不变。首尾在可见操作范围内不会触及边界，从而表现为无限循环。

- 选择理由：复用现有 `rememberSnapFlingBehavior` 与居中判定，行为稳定，改动集中在索引映射。
- 备选方案：滚到边界时无动画跳回另一端。实现简单但会出现可见跳动；自定义 `scrollable` 取模方案需要重写吸附与手势逻辑，风险更高。

### 3. 默认时间仅在无有效建议时取 10 分钟后

`ReminderDialog` 的 `initialTime` 保持“有效建议时间优先”，否则由 `now.plusMinutes(5)` 改为 `now.plusMinutes(10)` 并清零秒与纳秒；预设行“1 小时后”等仍按原逻辑覆盖。

### 4. 全局提醒列表通过 Room 只读投影查询

在 `RecordDatabase` 增加一个 `Flow<List<ScheduledReminderRow>>` 投影查询，连接 `reminders`、`sessions`、`messages`：仅返回 `status = 'scheduled'`、会话未删除的提醒，并带出会话标题与未删除消息的 `currentContent`。`ChatRepository` 新增 `scheduledReminders(): Flow<List<ReminderPreview>>`，在领域层做空白裁剪与长度截断（如 80 字），UI 侧再用单行省略号兜底。

`ChatViewModel` 订阅该 Flow 存入 `ChatUiState.scheduledReminders`，并新增 `reminderListVisible`。顶部栏在 `directoryReady` 且列表非空时显示闹钟按钮；面板中点击行调用既有的 `openSessionFromNotification(sessionId, messageId)` 并关闭面板，取消复用 `cancelReminder(id)`，Flow 自动刷新按钮与列表。绑定消息已删除或为空时只打开会话，不定位消息。

- 选择理由：查询与界面同源、取消后实时更新；不新增表，无需迁移。
- 备选方案：先用 `observeScheduledReminders()` 再逐条查消息。会产生 N 次查询且跨对话难以同步，弃用。

### 5. 时间过滤放在状态层

列表展示时过滤 `triggerAt > now`，避免已过点但尚未被 `rescheduleReminders()` 取消的提醒短暂出现；提醒触发或取消会写库并触发 Flow 更新。不在 SQL 中写死当前时间，以免时间流逝后 Flow 不重新发射。

## Risks / Trade-offs

- [虚拟长列表仍有理论上限] → 取足够大的倍数，正常手势与快速滑动无法到达边界；若未来需要严格无限，可再替换为取模滚动实现。
- [提醒跨对话标题/内容依赖 Room 索引新鲜度] → 索引由本地文件恢复与每次写入同步维护；目录尚未就绪时不展示入口，避免旧索引误导。
- [启动恢复耗时较长时加载态停留] → 加载态替代空白与错误提示，恢复失败会退出加载并提示重新选择，不会卡死。
- [提醒到期但未触发时列表仍显示] → 打开列表时按当前时间过滤；应用回到前台会重排并清理过期提醒。

## Migration Plan

无数据迁移：不新增字段或表，仅新增只读查询与界面状态。回滚时移除新入口与状态字段即可，既有提醒记录保持兼容。

## Open Questions

无。