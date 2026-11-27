# Tasks

## 1. 默认对话

- [x] 1.1 `ChatSessionService` 增加 `seedDefaultConversations()`，空记录时创建“极其重要”“草稿”并标记“草稿”为最近打开。
- [x] 1.2 `PersistentChatRepository.open()` 在目录为空时调用 `seedDefaultConversations()`，目录含任何文件时跳过。

## 2. 移除分组

- [x] 2.1 删除 `SessionGroupStore` 及 `ChatViewModel` 中的分组状态、动作与清理逻辑。
- [x] 2.2 移除 `ChatScreen` 的分组标签、列表过滤、“移到另一分组”菜单与相关动作，`MainActivity` 恢复简单工厂调用。
- [x] 2.3 删除 `conversation-groups` 主规范，新增 `default-conversations` 主规范。

## 3. 测试与验证

- [x] 3.1 新增默认对话单测，移除三个分组用例。
- [x] 3.2 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- [x] 3.3 运行 `npx --yes @fission-ai/openspec@2.0.0 validate default-conversations --strict --no-interactive` 与 `validate --specs --strict`。

## 实施记录

- 3.1 新增 2 个用例：空记录创建“极其重要”“草稿”且“草稿”最近打开；已有对话时不创建。
- 3.2 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，32 个测试套件、208 个测试全部通过。
- 3.3 `validate --type change default-conversations --strict` 与 `validate --specs --strict` 均通过（5 个规范）。
- 已在模拟器（chat_notebook_api35）用全新安装验证：接受隐私政策、选择空目录后对话列表出现“极其重要”“草稿”，且默认打开“草稿”；切到“极其重要”后强制停止并重启，仍打开“极其重要”。
- 首次验证发现两个对话分两次写入会让界面先选中“极其重要”，已改为一次快照写入两个对话，复测通过。
- 目录判空改为在写入前用 `DocumentFile.listFiles()` 检测，移除安装级偏好标记。
- 已在模拟器验证：含 `note.txt` 的目录被选中后不创建默认对话，抽屉显示“暂无对话”；空目录仍会创建两个默认对话。卸载重装只清除偏好，判定完全依赖目录文件，因此重装后选择有笔记的目录不会创建默认对话。
- 未在模拟器上逐步验证“重命名或删除默认对话后再重装”的完整操作链。