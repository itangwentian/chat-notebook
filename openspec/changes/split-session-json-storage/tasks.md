# Tasks

## 1. 存储格式与接口

- [x] 1.1 Goal：按文件名读写的存储接口；Requirements：`SnapshotStore` 提供 list/read/write/delete，写后回读校验；`SafSnapshotStore` 识别 `session-*`、`settings-*` 与旧 `records-*`；Constraints：不改变 SAF 权限流程；Acceptance Criteria：编译通过，写入失败抛 `IOException`。
- [x] 1.2 Goal：会话与设置文件编解码；Requirements：`SessionFilePayload`/`SessionFileEnvelope`/`SettingsFileEnvelope`，含 schemaVersion、generation、createdAt、sha256 与归属校验；Constraints：保留旧 `SnapshotCodec`；Acceptance Criteria：单元测试可往返编解码并拒绝错位数据。

## 2. 仓库读写

- [x] 2.1 Goal：聚合恢复；Requirements：按 sessionId 取最新有效 generation，坏文件回退旧版本并标记警告，全部无效时报错且不改文件；Constraints：Room 重建逻辑不变；Acceptance Criteria：测试覆盖多对话隔离与损坏回退。
- [x] 2.2 Goal：按对话 diff 写入；Requirements：只写新增/变化会话，写成功删旧版本；设置仅在变化时写；已删除会话删除文件；失败保持旧状态；Constraints：消息原始字段不可修改的校验保留；Acceptance Criteria：测试覆盖写入失败与删除清理。
- [x] 2.3 Goal：旧快照迁移；Requirements：仅旧文件存在时拆分写入，全部成功后删除旧文件，失败回滚新文件；Constraints：不读取新格式目录中的旧快照；Acceptance Criteria：测试覆盖成功迁移与失败保留。

## 3. 测试与验证

- [x] 3.1 Goal：更新测试 Store；Requirements：5 个内存 Store 适配新接口；新增每对话文件、迁移、删除与坏文件用例；Constraints：不降低既有覆盖；Acceptance Criteria：`testDebugUnitTest` 通过。
- [x] 3.2 Goal：构建；Requirements：运行 `lintDebug` 与 `assembleDebug`；Constraints：lint 不新增错误；Acceptance Criteria：全部通过。
- [x] 3.3 Goal：模拟器验证；Requirements：检查记录目录每对话一个 JSON、重启恢复、发送/编辑/删除/转发/整理不回归；Constraints：使用现有 Pixel 6 API 35；Acceptance Criteria：逐项记录结果。
- [x] 3.4 Goal：APK；Requirements：复制 debug APK 并记录 SHA-256；Constraints：不修改无关功能；Acceptance Criteria：APK 可安装。

## 实施记录

- 构建与测试：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过；新增每对话文件、修改隔离、删除清理、旧快照迁移、迁移失败保留 5 个用例。
- 模拟器验证（Pixel 6 API 35，`/sdcard/Documents`，旧目录含 271 个 `records-*.json`）：
  - 选择目录后自动迁移，旧快照从 271 个降为 0，生成 2 个 `session-<sessionId>-00000000000000000261.json` 与 1 个 `settings-00000000000000000261.json`；两个对话均恢复。
  - 迁移只读取文件名中 generation 最新的旧快照分组，不再逐条读取全部 271 个文件。
  - 发送消息后仅目标对话生成 generation 262 文件并删除其 261 旧文件；另一对话与设置文件的修改时间保持不变。
  - 会话文件包含 `sessionDocument`、`mergeRelations`，不含 `llmConfigs`；设置独立保存。
  - 强制停止并重启后消息 `storage-test-1` 仍存在，恢复耗时约 8 秒。
  - 删除“新对话”后其会话文件立即消失，另一对话与设置文件不变；重启后列表仅剩 `talk新对话55555`。
- 说明：进程在写入中途被杀死时无法执行回滚，可能留下部分新 generation 文件；正常写入失败会回滚本次新文件。首轮迁移需要写会话文件并逐个删除旧快照，耗时取决于旧文件数量。
- APK：`chat_notebook-debug.apk`，SHA-256 `F19609B5E2D96B9087D0697E30C5626CDC15641F81F580EAC38AEFCB63B21263`。
