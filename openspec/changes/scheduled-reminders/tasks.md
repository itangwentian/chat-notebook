# Tasks

## 1. 提醒数据与持久化

- [x] 1.1 新增 `ReminderRecord`（`scheduled`/`fired`/`cancelled`）并加入 `RecordPayload`、`SessionFilePayload`，字段放在末尾并使用 `@EncodeDefault(NEVER)`；验证：快照与会话文件编解码往返一致，且不含提醒的旧文件解码后重新编码的 SHA-256 不变。
- [x] 1.2 在 `SnapshotCodec` 与 `SessionFileCodec` 校验提醒归属与状态；验证：单测覆盖跨会话 `messageId`、非法状态、重复 id 被拒绝。
- [x] 1.3 在 `RecordDatabase` 增加 `reminders` 索引表与 `MIGRATION_8_9`，`RecordRepository.restore()` 从文件重建；验证：迁移测试通过，重建后提醒数量与状态和文件一致。
- [x] 1.4 `ChatRepository` 增加保存、修改、取消提醒与观察待触发提醒的接口，写入顺序仍是先文件后索引；验证：单测覆盖保存后可读、取消后为 `cancelled`、文件写入失败时索引不提前变更。

## 2. 局域网同步

- [x] 2.1 `SyncConversation` 增加 `reminders`，补齐 `toSyncConversation`/`toSession`/`canonical()`，`LAN_SYNC_VERSION` 升至 2；验证：同步编解码往返测试通过，提醒顺序不影响摘要。
- [x] 2.2 `mergeConversations` 按 `id` 取 `updatedAt` 较新的提醒，`fired`/`cancelled` 覆盖旧状态；验证：单测覆盖两端各设提醒、一端取消、一端已触发三种场景，且两端合并结果相同。

## 3. 调度与通知

- [x] 3.1 `AndroidManifest.xml` 增加 `POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`、`ReminderReceiver` 与重排接收器，`MainActivity` 设为 `singleTop`；验证：`assembleDebug` 后合并清单包含这些声明。
- [x] 3.2 实现 `ReminderScheduler`：`setAlarmClock` 精确调度，无精确闹钟权限时降级 `setAndAllowWhileIdle` 并返回降级结果；验证：Robolectric 断言闹钟类型、触发时间与 PendingIntent 唯一性。
- [x] 3.3 实现 `ReminderReceiver`：先用状态与时间做幂等判断，再发通知、写 `fired`；验证：单测断言已取消/已触发不通知，正常触发只通知一次并更新文件。
- [x] 3.4 实现 `rescheduleAll()` 并在应用启动、`BOOT_COMPLETED`、`TIME_SET`、`TIMEZONE_CHANGED` 与同步完成后调用；验证：重复调用不产生额外闹钟，已触发与已取消的提醒不被排入。
- [x] 3.5 通知渠道 `reminders` 与点击跳转：附带会话 id，`MainActivity` 在 `onCreate`/`onNewIntent` 选中对话并清除 extra；验证：单测或 Robolectric 断言点击后当前对话切换，重复进入不重复跳转。

## 4. 界面与权限

- [x] 4.1 仅在消息操作菜单增加“提醒”，对话列表菜单不提供提醒入口；验证：打开消息提醒面板并带入该消息，对话菜单中不存在提醒项。
- [x] 4.2 提醒面板采用底部弹出样式（参考微信）：提供“1 小时后 / 明天 09:00 / 后天 09:00”快捷选项与 Material 3 日期选择器，时分使用上下滚轮选择，只接受未来时间，列出并允许取消待触发提醒；验证：快捷选项与自定义时间都能设置提醒，滚轮可上下拖动并吸附到整行，选择过去时间被拒绝并提示，取消后列表移除且闹钟被取消。
- [x] 4.3 通知权限与精确闹钟权限说明、请求与降级提示；验证：拒绝通知权限、拒绝精确闹钟两种情况下提醒仍保存，并出现对应提示文案。
- [x] 4.4 过期提醒自动移除：`rescheduleReminders()` 先把过点超过 5 分钟仍未触发的提醒标记为已取消并撤销闹钟，提醒面板只列出未来的待触发提醒；验证：`ReminderExpiryTest` 覆盖过期、宽限期内、已触发、已取消四种情况，`ChatScreenTest` 覆盖面板隐藏过期提醒、且仅剩过期提醒时整段待触发区域不出现。

## 5. 集成验证

- [x] 5.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；验证：构建成功、测试全部通过。
- [x] 5.2 运行 `npx --yes @fission-ai/openspec@2.0.0 validate scheduled-reminders --strict --no-interactive` 与 `validate --specs --strict`；验证：全部通过。
- [ ] 5.3 模拟器人工验证：设置 1 分钟后的消息提醒并退到后台，确认通知出现且点击回到该对话；重启设备后确认未触发提醒仍会响；拒绝通知权限与精确闹钟权限各验证一次降级提示。验证：逐项记录实际结果，未完成项如实标注。

  验证备注：模拟器 `chat_notebook_api35` 上已验证通过「到点发出 `reminders` 渠道通知、提醒状态落盘为 `fired`」（通知记录 `channel=reminders importance=4`，会话文件新 generation 中 `"status":"fired"`）。模拟器未授予精确闹钟，闹钟以 `setAndAllowWhileIdle` 降级调度，实际触发比设定时间晚约 1 分钟。尚未验证：点击通知回到对应对话、重启设备后的重排、以及拒绝通知/精确闹钟权限时的降级提示文案，保留待验证。

  本次定位结论：接收器原先从 `intent.data.lastPathSegment` 取提醒 id，而 data URI 为 `chatnotebook-reminder://<id>`，id 位于 authority，导致取到空值后直接返回、不发通知。现已改为随 Intent 传 extra，并按 extra → authority → path 的顺序解析，补充 `ReminderSchedulerTest` 覆盖。

## 6. 提示强度与亮屏

- [x] 6.1 通知渠道升级为 `reminders_v3`：默认提示音、震动模式、锁屏可见，创建时删除全部旧渠道（`reminders`、`reminders_v2`）；验证：`dumpsys notification` 显示新渠道 sound 与 vibration 已启用。

  验证结果（模拟器 `chat_notebook_api35`）：`dumpsys notification` 中 `mId='reminders_v3'` 为 `mImportance=4`、`mSound=content://settings/system/notification_sound`、`mVibrationEnabled=true`、`mVibrationPattern=[0, 500, 250, 500]`、`mAudioAttributes usage=USAGE_NOTIFICATION`；旧渠道 `reminders` 与 `reminders_v2` 均显示 `mDeleted=true`。渠道的声音/震动在创建后不可修改，已安装设备上残留的旧渠道只能通过更换渠道 id 恢复默认行为。新增单测断言新渠道的声音、震动与公开可见性，以及升级时删除旧渠道。
- [x] 6.2 熄屏时点亮屏幕：声明 `WAKE_LOCK` 并在发出通知前获取短时亮屏唤醒锁（`SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`）；提醒打开的 `MainActivity` 设置 `setShowWhenLocked` / `setTurnScreenOn`，跳转后清除；验证：熄屏后触发提醒，屏幕点亮并显示通知。

  验证结果：`adb shell input keyevent 26` 熄屏后 `dumpsys power` 为 `mWakefulness=Asleep`，把系统时间推进到提醒前 1 分钟后到点，`dumpsys power` 变为 `mWakefulness=Awake`，截图显示桌面顶部弹出「新对话 / reminder-test」横幅通知。实现修正：`WakeLock` 没有 `setTurnScreenOn`（该方法在 `Activity` 上），改为 `SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP`，10 秒超时自动释放。之后用 `locksettings set-disabled false` 启用锁屏复验：熄屏到点后屏幕点亮，锁屏上显示「新对话 / I recived teh files yesterday」通知内容；锁屏截图在屏幕再次休眠后需按电源键点亮查看。真机若锁屏不显示内容，需检查系统「锁屏通知」开关或「隐藏通知内容」设置。
- [x] 6.3 声明 `USE_FULL_SCREEN_INTENT`，Android 14+ 仅在已授权时附带全屏意图，未授权时保留横幅通知；验证：合并清单包含权限，未授权时提醒仍正常展示。

  验证结果：合并清单包含 `USE_FULL_SCREEN_INTENT`；模拟器上 `appops get com.chatnotebook.app USE_FULL_SCREEN_INTENT` 为 `default`，通知记录中仍带 `fullscreenIntent`，但系统记录了 `rejectTime`（拒绝全屏启动），最终以高优先级横幅展示并点亮屏幕，未阻断提醒。

## 7. 通知点击定位消息

- [x] 7.1 通知意图携带消息 id：`ReminderDelivery` 增加 `messageId`，`ReminderNotifications` 增加 `EXTRA_MESSAGE_ID` 与一次性读取的路由对象；验证：Robolectric 断言通知意图包含会话与消息 id，读取一次后清空。

  验证结果：`ReminderNotificationsTest` 断言 `contentIntent` 携带 `EXTRA_SESSION_ID`/`EXTRA_MESSAGE_ID`，`consumeRoute` 读取一次后再读为 null；`notificationWithoutMessageOnlyRoutesToConversation` 覆盖未绑定消息时不写消息 extra。`ReminderDeliveryTest` 覆盖消息被删除时 `messageId` 为空。
- [x] 7.2 会话切换后滚动到消息并闪烁背景 5 秒：`ChatUiState` 增加 `highlightMessageId`，`openSessionFromNotification` 传入消息 id，复用 `PendingScroll` 滚动，5 秒后清空高亮；`MessageBubble` 用动画插值气泡底色；验证：单测覆盖消息已加载与未加载两种入口、高亮到时清空，消息已删除时只切对话。

  验证结果：`ChatViewModelTest` 新增 `notificationWithMessageScrollsToBubbleAndClearsHighlight`（切换会话后 `scrollTargetId`/`highlightMessageId` 指向目标消息，`advanceUntilIdle` 后清空）与 `notificationInActiveSessionFocusesMessageImmediately`（消息已加载时直接定位）；消息已删除时 `ReminderDelivery.messageId` 为空，只切对话。
- [x] 7.3 模拟器验证：点击提醒通知后切换到对应对话、滚动到绑定消息并闪烁约 5 秒。

  验证结果：模拟器 `chat_notebook_api35` 上通过「长按消息 → 提醒 → 1 小时后」创建绑定消息的提醒（`dumpsys alarm` 确认精确闹钟按点排入）并把系统时间推进到到点，点击通知后应用打开对应对话并纵向定位到该消息；连续抓帧（0～8 秒）比对同一气泡像素，目标气泡在 `(234,221,255)`（正常底色）与 `(255,216,228)`（高亮色）之间来回变化，第 1～5 秒持续闪烁，约第 5 秒恢复常态底色并保持不变，相邻气泡颜色全程不变。

## 8. 消息气泡提醒底色

- [x] 8.1 `ChatUiState` 从当前对话提醒流派生 `reminderMessageIds`（仅 `scheduled` 且绑定消息，同消息多条去重），`MessageBubble` 增加 `hasReminder` 并把常态底色向 `errorContainer` 混合 50% 呈略微偏红；验证：单测断言设置提醒后集合包含消息 id，取消后移除。

  验证结果：`ChatViewModelTest.reminderMarkerTracksMessageUntilReminderIsCancelled` 断言设置提醒后 `reminderMessageIds` 含该消息 id、取消后清空；`assembleDebug`、`testDebugUnitTest`、`lintDebug` 全部通过。
- [x] 8.2 气泡上不绘制闹钟角标：移除叠加角标、`ic_alarm.xml` 与对应无障碍文案，避免遮挡消息内容；验证：设置提醒后气泡仅底色偏红，尺寸、文字换行与附件布局不变。

  验证结果：气泡改为只靠底色提示，无任何覆盖内容；有提醒的气泡常态底色为 `(242,222,238)`，普通气泡为 `(234,221,255)`；取消提醒后恢复 `(234,221,255)`。
- [x] 8.3 模拟器验证：设置提醒后气泡底色偏红且不遮挡内容，取消提醒后恢复普通底色，到点触发后恢复普通底色。

  验证结果（模拟器 `chat_notebook_api35`）：设置提醒后目标气泡底色偏粉红且文字完整可读；在提醒面板点“取消提醒”后底色恢复为普通气泡颜色；此前已在同一气泡验证到点触发后提醒流更新、提示随状态移除。

## Workflow follow-up

- 实现完成并验证后归档该变更。