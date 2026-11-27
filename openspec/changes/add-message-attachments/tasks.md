# Tasks

依据 proposal.md、design.md 与 `specs/message-attachments/spec.md` 按依赖顺序实施；每项完成即验证，不把数据层、图片、录音与布局合成一次提交。Goal、Requirements、Constraints、Acceptance Criteria 分别表示目标、需求、约束和验收。下列均为未实施任务。

## 1. 数据层与兼容

- [x] 1.1 Goal：建立附件数据模型；Requirements：`MessageRecord` 新增 `attachments` 列表，`MessageAttachment` 含 id、kind（image/audio）、relativePath、mimeType、sizeBytes、durationMs、createdAt；空列表使用 `@EncodeDefault(NEVER)` 不写入快照；`SessionFileCodec.validateSession` 校验 kind、相对路径前缀 `attachments/` 与附件 id 唯一（旧 `SnapshotCodec` 保持只读兼容）；Constraints：保持旧快照校验和可解析，不改动既有字段语义；Acceptance Criteria：`SnapshotCodecTest` 覆盖空附件不编码、含附件往返、非法 kind/路径被拒绝，旧快照解码结果附件为空。
- [x] 1.2 Goal：持久化附件引用到 Room；Requirements：`MessageEntity` 增加 `attachmentsJson`，数据库升到 v8 并新增 `MIGRATION_7_8`，实体与记录互转复用 Json 编解码；Constraints：显式迁移，禁止 destructive fallback；Acceptance Criteria：`RecordDatabaseMigrationTest` 覆盖 v7 → v8 后既有消息保留且附件列为空，写入含附件消息后可读回。
- [x] 1.3 Goal：附件文件存储；Requirements：新增 `AttachmentStore`（SAF 与内存实现），在所选记录目录创建 `attachments/` 子目录，按附件 ID 写文件并回读校验大小，支持删除草稿附件；Constraints：附件不进入快照校验和，权限或写入失败必须抛错；Acceptance Criteria：单元测试覆盖写入、读回、删除与权限缺失报错。
- [x] 1.4 Goal：支持附件发送；Requirements：`ChatSessionService.send`、`ChatRepository.sendMessage` 接受附件列表，文字 trim 后可为空但文字与附件不得同时为空；Constraints：发送仍按会话文件一次写入完成，失败不产生半条消息；Acceptance Criteria：`MessageInteractionTest` 覆盖仅附件发送、附件加文字、两者皆空返回 null、写入失败无消息。

## 2. 图片附件

- [x] 2.1 Goal：从本地相册选择图片；Requirements：图片入口启用，使用系统照片选择器一次多选（最多 9 张），选择后逐张复制到 `attachments/` 并生成草稿附件；Constraints：不申请存储权限，不在主线程做文件 IO；Acceptance Criteria：模拟器选择多张图片后草稿区出现对应缩略图，取消选择不新增附件。
- [x] 2.2 Goal：草稿附件区；Requirements：输入栏上方显示草稿缩略图与移除操作，支持多张与录音共存；Constraints：不挤压文字输入行，小屏可横向滚动；Acceptance Criteria：Compose 测试覆盖添加两张、移除其中一张、发送后清空。
- [x] 2.3 Goal：气泡内展示图片；Requirements：缩略图按采样解码显示，点击打开大图，文件缺失显示“附件不可用”占位；Constraints：不在列表滚动时解码原图；Acceptance Criteria：模拟器查看图文消息、打开大图、删除附件文件后重进显示占位。

## 3. 语音附件

- [x] 3.1 Goal：录音能力；Requirements：`AndroidManifest` 增加 `RECORD_AUDIO`，新增 `VoiceRecorder`（AAC/M4A 输出到 cache），首次点击请求权限，再次点击停止；60 秒自动停止，短于 1 秒丢弃；Constraints：权限拒绝、录音异常必须提示且不生成附件；Acceptance Criteria：单元/模拟器验证权限拒绝提示、正常录制生成附件、自动停止与短录音丢弃。
- [x] 3.2 Goal：录音状态与草稿；Requirements：录音中显示红点、已录时长与停止操作，停止后复制到 `attachments/` 并加入草稿区，可移除；Constraints：同一消息最多一段录音，移除与取消都要清理临时文件；Acceptance Criteria：模拟器录制、停止、移除各状态正确，录音中旋转/切后台不产生崩溃或重复录音。
- [x] 3.3 Goal：语音播放；Requirements：气泡语音条目显示时长，支持播放与停止，同一时间仅播放一条；Constraints：页面离开或消息删除后释放播放器；Acceptance Criteria：模拟器播放与停止正常，连续点击不同语音不会叠音。

## 4. 发送与展示整合

- [x] 4.1 Goal：合并发送与气泡布局；Requirements：输入栏在有附件时发送按钮常驻可用，发送携带文字、图片与录音；气泡按文字 → 图片 → 语音顺序展示，纯附件消息不显示空文字行；Constraints：保留既有引用、时间显示、气泡格式与右对齐规则；Acceptance Criteria：Compose/模拟器验证图文语音同一气泡、仅附件消息、未选择目录时禁止发送。
- [x] 4.2 Goal：交互边界；Requirements：复制只复制文字；编辑只改文字并保留附件；合并忽略附件；Constraints：不改变既有编辑历史、软删除与结果失效逻辑；Acceptance Criteria：测试覆盖复制内容、编辑后附件仍在、合并结果不含附件。
- [x] 4.3 Goal：恢复与错误处理；Requirements：重启后附件消息完整恢复；目录权限丢失或附件缺失时给出明确提示/占位；Constraints：不把未写入长期文件的附件显示为已保存；Acceptance Criteria：模拟器重启恢复图文语音消息，移除记录目录授权后发送失败且草稿保留。

## 5. 验证与记录

- [x] 5.1 Goal：自动化验证；Requirements：运行 `./gradlew.bat testDebugUnitTest lintDebug assembleDebug`，新增测试覆盖数据、发送、迁移与 Compose 草稿交互；Constraints：缺环境的设备测试单列未完成；Acceptance Criteria：保留构建与测试摘要，lint 无新增错误。
- [x] 5.2 Goal：模拟器端到端；Requirements：在 Pixel 6 API 35 模拟器检查选图、录音、移除、仅附件发送、图文语音展示、播放、重启恢复、权限拒绝与目录未授权；Constraints：录音需模拟器音频输入可用，不可用时如实记录；Acceptance Criteria：逐项记录通过/失败与修复结果，未执行项不记为通过。
- [x] 5.3 Goal：文档与规范同步；Requirements：更新 tasks 的 Workflow follow-up 记录验证结果；如附件字段或目录结构影响数据策略说明，同步更新 README 或数据说明；Constraints：不修改与本变更无关的需求文档；Acceptance Criteria：后续实施者可按记录复现验证步骤。

## 依赖与边界

- 1.x 必须先于 2.x、3.x；4.1 依赖 2.2 与 3.2；4.2、4.3 可在 4.1 后并行。
- 附件不参与 AI 整理输入与消息合并；语音不做转写；首版允许草稿阶段产生未引用的孤立附件文件。

## 实施记录（2026-11-14）

### 自动化验证
- `.\gradlew.bat :app:testDebugUnitTest`：113 个测试全部通过，0 失败。
- `.\gradlew.bat :app:lintDebug`：0 Error、22 Warning（既有模式，无新增错误）。
- `.\gradlew.bat :app:assembleDebug`：成功；APK 复制为根目录 `chat_notebook-debug.apk`，SHA-256 `EC690084896C49F77BCE010463B169543780C2BB16EE3ED0F693F3E0EFB25963`。
- Room schema 导出到 `app/schemas/.../8.json`。
- 新增覆盖：`SnapshotCodecTest`（空附件不编码、会话文件附件往返、非法 kind/路径/重复 id）、`RecordDatabaseMigrationTest`（v7→v8 且附件列为空）、`AttachmentStoreTest`（写入/读回/删除/SAF 无权限）、`MessageInteractionTest`（仅附件、附件加文字、两者皆空、写入失败、编辑保留附件、合并忽略附件）、`ChatViewModelTest`（两张草稿、移除、仅附件发送、失败保留草稿、切换会话清空）、`ChatScreenTest`（草稿附件、常驻录音入口、复制只含文字）。

### 模拟器端到端（Pixel 6 API 35，emulator-5554）
通过：
- 系统照片选择器选图 → 草稿缩略图 → 与文字同气泡发送 → 输入栏清空。
- 已有图片草稿时通过新增的录音入口追加录音，图片与录音同一气泡发送；纯附件消息不显示空文字行。
- 点击气泡图片打开大图预览；删除附件文件并重启后显示“附件不可用”占位。
- 录音权限授予、录音状态条（红点/时长/停止）、停止生成音频草稿、仅音频发送、播放与停止。
- 60 秒自动停止并提示；短于 1 秒录音丢弃并提示；拒绝录音权限提示；录音中旋转不崩溃且录音被取消。
- 重启后图文与语音消息完整恢复；移除草稿附件同时删除 `attachments/` 下对应文件。
- 多选模式下点击气泡图片或语音只切换消息选中状态，不打开大图也不播放语音。
- 多选转发含图片/语音的消息时，附件元数据随消息复制到目标对话，图片缩略图与语音条目正常显示。

未在设备上执行（不记为通过）：
- 系统照片选择器一次多选两张以上图片（`ChatScreenTest`/`ChatViewModelTest` 覆盖两张草稿渲染、移除与计数）。
- 撤销记录目录授权后的发送失败与草稿保留（`ChatViewModelTest.failedSendKeepsDraftAttachments` 与 `MessageInteractionTest.failedWriteLeavesNoAttachmentMessage` 覆盖数据层行为）；设备上仅验证了正常目录下的发送。

