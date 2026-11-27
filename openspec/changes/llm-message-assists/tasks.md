# Tasks

## 1. 建议分析链路

- [x] 1.1 新增 `MessageAssist` 结果模型与 `MessageAssistPrompt`（系统提示词、当前时间注入、`correction` / `reminder` 的 JSON 契约），并实现解析与校验：单条建议最多 2000 字符、`changes` 最多 10 条、`triggerAt` 必须带偏移且在未来 10 秒到 1 年之间。验证：单测覆盖两项均为 `null`、仅命中一项、时间非法、字段超长、`correctedContent` 与原文相同。
- [x] 1.2 `AiService` 增加 `assist` 方法：`MockAiService` 直接返回 `null`，`RemoteAiService` 复用 `OpenAiCompatibleClient` 的 JSON 调用，不新增网络栈。验证：用假客户端断言 mock 模式与解析失败时都不会产生界面可见结果。
- [x] 1.3 `ChatRepository.analyzeMessage(messageId)`：仅在非 mock、存在启用的默认配置、消息角色为 `user` 且无语音附件时发起；按 `(messageId, contentVersion)` 去重，返回时比对当前 `contentVersion`，不一致即丢弃。验证：单测覆盖四种不满足条件的情况，以及请求期间消息被编辑后结果被丢弃。
- [x] 1.4 变更定位：按 `changes` 顺序在 `correctedContent` 中做子串定位得到高亮区间，定位失败直接跳过，不让模型返回字符下标。验证：单测覆盖顺序查找、重复词、定位失败三种情况。

## 2. 内存态与触发

- [x] 2.1 `ChatUiState` 增加 `assists: Map<String, MessageAssist>`，`ChatViewModel` 提供写入、移除与清理入口；不写入会话文件、快照与同步载荷。验证：单测断言采纳与忽略后条目被移除，且记录文件内容不变。
- [x] 2.2 `sendMessage` 成功落盘后异步发起分析，失败静默；消息被编辑或删除时移除对应建议。验证：单测断言编辑后建议消失、删除消息后不再展示建议。

## 3. 建议气泡界面

- [x] 3.1 新增 `AssistBubble`：左对齐、复用非 `user` 气泡的圆角与配色；修正建议展示整段文本并高亮改动片段（超过 4 行折叠），提醒建议展示时间文案与原文片段；两类同时命中时分段展示。验证：Compose 测试断言文本、高亮与两段内容同时出现。
- [x] 3.2 按钮采用视觉收缩但保留 48dp 命中区，并带稳定 `testTag`（`assist_apply_correction`、`assist_ignore_correction`、`assist_apply_reminder`、`assist_ignore_reminder`）。验证：Compose 测试断言按钮可点击且点击回调被触发。
- [x] 3.3 在 `MessageList` 中为目标消息之后插入独立 item，不进入 `MessageRecord` 渲染路径；无建议时不占位。验证：Compose 测试断言仅有建议的消息后面出现气泡，其余消息布局不变。
- [x] 3.4 建议气泡迟到到达时列表自动贴底，避免气泡出现在可视区之外被误判为「没有反应」。验证：模拟器发送含错别字消息，2–4 秒后无需手动滚动即可看到气泡。

## 4. 采纳动作

- [x] 4.1 采纳修正调用 `ChatRepository.editMessage`，复用既有编辑链路。验证：单测与界面测试断言消息内容更新、修改历史新增一条、`contentVersion` 递增、气泡消失，且不会再次触发建议分析。
- [x] 4.2 点击气泡上的「创建日程」打开既有提醒面板并预填识别出的时间，用户可修改后确认，确认后走既有 `saveReminder` 流程；Android 13+ 缺少通知权限时先请求授权，保存结果按精确 / 降级 / 无权限分别提示。验证：界面测试断言点击只打开面板且预填时间正确，确认后提醒进入待触发列表；消息已删除或时间已过期时不打开面板。

## 5. 集成验证

- [x] 5.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；验证：构建成功、测试全部通过。
- [x] 5.2 运行 `npx --yes @fission-ai/openspec@2.0.0 validate llm-message-assists --strict --no-interactive` 与 `validate --specs --strict`；验证：全部通过。
- [x] 5.3 模拟器人工验证：在设置中填入已确认可用的 OpenAI 兼容配置，逐项确认错别字修正与提醒建议的真实输出、采纳后的修改历史与提醒、断网时的静默失败、重启后建议不恢复。验证：逐项记录实际结果，未完成项如实标注。

  实际结果（Android 15 模拟器，真实 LLM 配置）：
  - 错别字修正：发送含拼写错误的英文消息后出现左侧建议气泡，整段修正文本与高亮改动可见；采纳后消息更新为修正文本，`查看原文与历史` 显示「版本 2」，气泡消失。
  - 提醒建议：含未来时间的消息同时给出修正与提醒两段；`创建日程` 打开既有提醒面板并预填识别时间，用户可修改后确认创建（会话文件 `reminders` 中新增 `scheduled` 记录）。
  - 通知权限：撤销 `POST_NOTIFICATIONS` 后采纳提醒，Android 15 弹出系统授权对话框，允许后提醒正常创建、气泡移除。
  - 断网静默失败：飞行模式下发送消息，消息正常落盘，无建议气泡、无错误提示，应用未崩溃。
  - 重启不恢复：带未处理建议时强制停止并重启应用，消息仍在但建议气泡消失。
  - 未覆盖项：模拟器无法通过 `adb input` 输入中文，中文错别字的端到端表现仅由单测覆盖；模拟器时区为 GMT，提醒时间按设备时区换算显示（如 +08:00 的 15:00 显示为 07:00），中国时区设备上显示正确。

## Workflow follow-up

- 实现完成并验证后归档该变更。