# Tasks

## 1. 启动恢复优化

- [x] 1.1 在 `ChatRepository` 及实现中增加同步的 `hasSavedDirectory(): Boolean`（读取已保存的记录目录偏好），运行 `gradlew.bat :app:testDebugUnitTest` 确认既有测试不受影响
- [x] 1.2 `ChatUiState` 增加 `restoringDirectory`（初始值为 `hasSavedDirectory()`），并在 `restoreSavedDirectory()` 结束后置为 false；在 `ChatViewModelTest` 的 Fake 实现中补齐该方法，并新增“有保存目录启动时为恢复中、无保存目录立即非恢复中”的单元测试
- [x] 1.3 调整 `ChatScreen` 的目录提示、紧凑模式目录入口与空态：仅在未就绪且未恢复时展示目录提示，恢复中且无消息时展示加载指示；在模拟器冷启动验证不再闪现“选择记录目录”，且恢复失败时能提示重新选择

## 2. 提醒创建体验

- [x] 2.1 将提醒默认时间计算抽成可测试的纯函数：无有效建议时返回当前时间 10 分钟后（秒与纳秒清零），建议时间晚于当前时优先使用建议；补充单元测试覆盖这两个分支
- [x] 2.2 调整 `ReminderTimeWheelDialog` 的滚轮为虚拟长列表并按 `index % count` 取模显示与选中（小时 24、分钟 60），保留吸附与居中；抽取索引到值的纯函数并补充 23→00、00→59 的单元测试
- [x] 2.3 在模拟器从气泡打开提醒面板，验证默认显示 10 分钟后；从日程建议创建时仍使用建议时间；滚轮正反向循环后点“确定”得到预期时分

## 3. 待触发提醒入口与预览

- [x] 3.1 在 `RecordDatabase` 增加连接 `reminders`、`sessions`、`messages` 的只读投影查询，仅返回待触发且会话未删除的提醒并带出会话标题与未删除消息内容；在测试中验证取消/删除消息后投影结果正确
- [x] 3.2 `ChatRepository` 增加 `scheduledReminders(): Flow<List<ReminderPreview>>`，完成空白裁剪与 80 字截断；更新 `ChatViewModelTest` 的 Fake 实现并补充数据映射测试
- [x] 3.3 `ChatUiState` 增加 `scheduledReminders` 与 `reminderListVisible`，`ChatViewModel` 订阅提醒流并实现打开/关闭列表、快捷取消、跳转到会话与气泡（消息已删除时只打开会话）的动作；补充 ViewModel 单元测试覆盖入口显隐、取消后移除与跳转参数
- [x] 3.4 在 `ChatScreen` 顶部栏按条件展示闹钟按钮，新增提醒预览面板：逐行显示触发时间与内容/会话标题、单行省略、右侧取消按钮、点击行跳转；补充字符串资源并在 `assembleDebug` 后于模拟器验证列表内容、截断、取消与跳转表现
- [x] 3.5 在 `MainActivity` 的 `ChatActions` 接线中暴露打开/关闭提醒列表、取消与跳转动作，确保编译通过并在模拟器实际点击验证

## 4. 集成验证

- [x] 4.1 运行 `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`，确认全部通过并记录结果
- [x] 4.2 在模拟器冷启动验证：已保存目录时直接进入笔记、无目录时仍出现选择入口；提醒列表在提醒触发或全部取消后按钮隐藏
- [x] 4.3 运行 `npx --yes @fission-ai/openspec@2.0.0 validate improve-startup-and-reminders --strict`，确认变更校验通过

## Workflow follow-up

- 使用 `svn status` / `svn diff` 复核改动，按项目约定提交 SVN。
- 实现与验证完成后归档该变更。
