# Tasks

## 1. 自动滚动行为

- [x] 1.1 在 `ChatScreen` 的消息滚动逻辑中记录上一次条目数量，当前对话条目数量增加时滚动到最后一条；会话切换和 `scrollSignal` 的既有处理保持不变，切换时重置记录。
- [x] 1.2 为消息列表增加稳定的测试标记，并在 `ChatScreenTest` 增加用例：向上滚动查看较早消息后追加一条新消息，断言最新消息可见；编辑已有消息文字但数量不变时，断言列表停留在原位置。

## 2. 验证

- [x] 2.1 运行 `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`，并执行 `npx --yes @fission-ai/openspec@2.0.0 validate add-chat-auto-scroll-on-sync --strict --no-interactive`，记录测试与构建结果。
- [x] 2.2 在模拟器验证：本地发送仍滚动到新消息；用两台设备或模拟同步写入当前对话时界面自动滚动到新内容；其他对话更新不影响当前列表位置；无法执行的双设备项如实记录。

## 实施记录

- `testDebugUnitTest` 共 162 个用例，0 失败、0 错误；`lintDebug`、`assembleDebug` 通过；`validate --strict` 通过。
- 新增 `ChatScreenTest` 用例：向上滚动到较早消息后追加消息，断言最新消息可见；仅编辑已有消息时列表不跳到底部。两者均通过。
- 模拟器 Pixel 6 API 35 已安装并确认本地发送后新消息可见，原有滚动行为未回归。
- 双设备同步未在模拟器执行：当前只有一套 AVD，且模拟器 NAT 无法保证两台实例互相发现，真实局域网同步滚动依赖 Compose 测试中“消息列表增长”的真实状态更新路径覆盖。建议在实体设备上用局域网同步补测一次。

## Workflow follow-up

- 验证通过后提交 SVN，并按项目约定归档本变更。
