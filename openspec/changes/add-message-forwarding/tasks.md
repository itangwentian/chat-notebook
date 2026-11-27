# Tasks

## 1. 数据与仓库

- [x] 1.1 Goal：转发写入；Requirements：`forwardMessages` 校验源/目标、按时间顺序复制角色与可见正文、生成新 id 与当前时间、更新目标 `updatedAt`、源不变；Constraints：不复制引用/表情/格式/AI 结果，失败不部分写入；Acceptance Criteria：服务与 ViewModel 测试覆盖成功、目标等于源、选中消息失效。
- [x] 1.2 Goal：仓库接线；Requirements：`ChatRepository` 暴露转发方法；Constraints：不改变快照与 Room 结构；Acceptance Criteria：编译与测试通过。

## 2. 多选入口与目标选择

- [x] 2.1 Goal：多选工具栏；Requirements：新增“转发”，选中至少一条可用；Constraints：不改变复制/删除/合并行为；Acceptance Criteria：Compose 测试确认按钮存在并可触发。
- [x] 2.2 Goal：目标选择框；Requirements：只列出非当前、未删除对话；空列表提示新建；取消保留多选；Constraints：遵循现有侧栏排序；Acceptance Criteria：Compose 测试确认当前对话被排除、点击目标回调正确。
- [x] 2.3 Goal：结果反馈；Requirements：成功后退出多选、停留当前对话并提示条数与目标；失败显示中文错误；Constraints：复用现有提示机制；Acceptance Criteria：ViewModel 测试与模拟器验证。

## 3. 验证与交付

- [x] 3.1 Goal：构建与测试；Requirements：运行 `testDebugUnitTest lintDebug assembleDebug`；Constraints：lint 不新增错误；Acceptance Criteria：全部通过。
- [x] 3.2 Goal：模拟器验证；Requirements：验证单条与多条转发、目标排除当前对话、目标内容顺序、修改时间排序上浮；Constraints：使用现有记录目录；Acceptance Criteria：逐项记录。
- [x] 3.3 Goal：APK；Requirements：复制 debug APK 并记录 SHA-256；Constraints：不修改无关功能；Acceptance Criteria：APK 可安装。

## 实施记录

- 构建与测试：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过；新增 `ChatSessionService` 转发复制测试、ViewModel 转发流程测试、Compose 多选入口与目标排除测试。
- 模拟器验证（Pixel 6 API 35）：多选两条消息后工具栏显示“复制/删除/合并/转发”；目标对话框只显示当前对话以外的“新对话”；确认后停留在原对话，目标对话新增两条按原时间顺序排列的消息。
- 多选工具栏文案由“复制选中/删除选中”缩短为“复制/删除”，为“转发”留出空间，四个操作在 1080px 宽度内全部可见。
- APK：`chat_notebook-debug.apk`，SHA-256 `98B1DE17B15E6D882F2777DE7593BF4B99162A4B255770FA6CDAF24DD1A783DE`。
