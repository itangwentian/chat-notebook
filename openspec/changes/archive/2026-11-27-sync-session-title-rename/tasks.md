# Tasks

## 1. 回归覆盖

- [x] 1.1 在 `RecordRepositoryTest` 增加双端仅改名用例：发起端改名后对端收到新标题、消息不变；随后对端改名，发起端也收到新标题，两个方向都只交换该对话一次且不产生冲突副本。
- [x] 1.2 在 `ChatViewModelTest` 增加外部同步改名用例：会话流收到新标题时，会话列表与当前打开对话的 `activeTitle` 同步更新。

## 2. 验证

- [x] 2.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 并执行 `npx --yes @fission-ai/openspec@2.0.0 validate sync-session-title-rename --strict --no-interactive`，记录结果。
- [x] 2.2 核对生产代码：`SyncConversation.title` 与双向映射、`ChatSessionService.rename` 的 `updatedAt` 更新、`RecordRepository` 的同步修订推进均已存在，本变更无需修改同步协议或存储格式；模拟器只有一台且未配对，双设备真机验证如实记录为未执行。

## 实施记录

- 新增 `RecordRepositoryTest.renamedSessionTitleReachesPeerWithoutMessageChanges`：先完成一轮同步，再只改标题并同步，断言对端标题更新且仅交换 1 个对话；随后反向改名，断言发起端更新。两次均通过。
- 新增 `ChatViewModelTest.externallyRenamedSessionUpdatesOpenChatTitle`：模拟同步写入后的会话列表变化，断言 `activeTitle` 与列表标题更新。通过。
- 现有实现已满足行为：`SyncConversation` 投影包含 `title`，标题变化会改变 `digest()` 并推进本机同步版本；接收端 `toSession` 映射回 `SessionRecord.title`，提交后重建 Room，`ChatViewModel` 由会话流更新标题。本变更未改动生产代码。
- 未执行：两台设备或两个模拟器的局域网真机验证（当前仅一台模拟器且无配对记录），建议在实体设备补测一次“一端改名、另一端打开同名对话”的场景。
