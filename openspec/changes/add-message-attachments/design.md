# Design

## Context

动机见 proposal.md。当前输入栏在 `ChatScreen.kt` 的 `Composer` 中：左侧附件图标调用为空且禁用，右侧在输入为空时显示禁用语音按钮、有文字时显示发送按钮。消息模型 `MessageRecord` 只有文本字段；写入通过 `RecordRepository.update` 按对话对比差异，只重写变化的 `session-<sessionId>-<generation>.json`，`SafSnapshotStore` 在所选目录写入并回读校验字节。Room schema 当前为 v7（含 `session_documents`），`MessageEntity` 已用 `formatSegmentsJson` 存文本分段格式。AI 整理只消费 `currentContent` 文本。

## Goals / Non-Goals

**Goals:** 在既有单快照事务与 SAF 目录模型上补齐图片、语音附件；附件与文字合并为一个气泡展示；旧快照、旧数据库与现有测试保持可用；录音与播放可脱离网络验证。

**Non-Goals:** 不做语音识别、转写或字幕；不做图片编辑、压缩策略调优与云端同步；附件不进入 AI 整理输入，也不参与消息合并的文本分段；不引入图片加载第三方库。

## Decisions

### 1. 附件文件存放与生命周期

在所选记录目录（`SafSnapshotStore` 的 tree URI）下建立 `attachments/` 子目录，文件名使用 `附件ID + 扩展名`（如 `<uuid>.jpg`、`<uuid>.m4a`），不覆盖快照文件，不进入快照校验和。新增 `AttachmentStore` 抽象（SAF 实现 + 测试内存实现）：SAF 实现由 tree URI 定位或创建 `attachments/` 子目录，按名字写文件、回读校验长度并支持按名字删除；读取时用 `DocumentFile` 子文件 URI 交给图片/音频解码。删除草稿附件时删除文件；发送后的附件随消息长期保留，消息软删除时不删除文件以保留可追溯性。选择图片或停止录音时即复制到该目录并生成附件记录，草稿被移除或应用退出未发送时可能留下无引用文件，启动恢复时不对其做破坏性清理（首版允许孤立文件，记录在风险中）。

理由：把二进制放进快照 JSON 会让单文件无限膨胀并破坏现有校验/恢复模型；立即落盘比暂存 cache 再在发送时复制更简单，发送事务不需要同时处理大文件 IO。

### 2. 数据模型与兼容

`MessageRecord` 新增 `attachments: List<MessageAttachment> = emptyList()`，使用 `@EncodeDefault(EncodeDefault.Mode.NEVER)`，空列表不写入快照，旧快照校验和与读取不受影响。`MessageAttachment` 字段：`id`、`kind`（`image` / `audio`）、`relativePath`、`mimeType`、`sizeBytes`、`durationMs`（图片为 0）、`createdAt`。`SessionFileCodec.validateSession` 增加校验：kind 合法、relativePath 非空且以 `attachments/` 开头、`attachments` 内 id 不重复；旧格式的 `SnapshotCodec` 只读，不新增字段校验。

Room：`MessageEntity` 增加 `attachmentsJson`（默认空串），数据库升到 v8，新增 `MIGRATION_7_8`（`ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT ''`），`toEntity`/`toRecord` 复用与 `formatSegmentsJson` 相同的 Json 编解码方式。

### 3. 发送规则

`ChatSessionService.send(sessionId, text, attachments, quotedMessageId)`：文字 trim 后可为空，只要附件非空即可创建消息；两者都为空返回 `null` 且不写入。附件元数据随 `MessageRecord` 写入当前对话的会话文件。`ChatRepository.sendMessage` 增加附件参数。`ChatViewModel.send` 在编辑态仍只更新文字（保留既有附件）；普通发送时把草稿附件一次性带入并在成功后清空草稿。

### 4. 图片选择与展示

使用 `ActivityResultContracts.PickMultipleVisualMedia`（最多 9 张，系统照片选择器，无需存储权限）；回调后逐张读取流写入 `attachments/`，生成 `image` 附件。展示时用 `BitmapFactory` 按采样解码缩略图，点击打开全屏 `Dialog` 查看原图；文件不存在时显示“附件不可用”占位。移除草稿附件调用 `AttachmentStore.delete`。

### 5. 录音与播放

新增 `VoiceRecorder`（`MediaRecorder`，AAC/M4A，输出到应用 cache 临时文件）。首次点击语音按钮请求 `RECORD_AUDIO` 运行时权限；授予后开始录音并显示录音状态条（红点、已录时长、停止按钮），再次点击停止；停止后将临时文件复制到 `attachments/` 生成 `audio` 附件并删除临时文件。取消/移除草稿录音时删除对应文件。录音上限 60 秒，到时自动停止；短于 1 秒的录音丢弃并提示。播放使用 `MediaPlayer` 读取 SAF URI，同一时间只播放一条，播放结束或页面离开时释放。`AndroidManifest` 增加 `android.permission.RECORD_AUDIO`。

### 6. 输入栏与气泡

输入栏保持两层圆角结构：文字区上方新增草稿附件条（横向缩略图 + 删除角标，语音显示为带时长的条目），录音进行中时以录音状态条替换草稿区；有附件时右侧发送按钮常驻可用（文字为空也可发送）。附件图标和语音按钮在未录音时启用。气泡内布局为文字（可为空）→ 图片缩略图行 → 语音条目；用户消息右对齐、AI 消息左对齐的既有规则不变。现有 `formatTextStyle`、引用、历史、复制等交互保持：复制只复制文字；编辑只改文字且保留附件；合并仅合并文字，忽略附件。

## Risks / Trade-offs

- [SAF 写入/读取失败或权限丢失] → 附件操作抛出明确错误并保留草稿文本，不伪造发送成功；发送前再次要求目录就绪。
- [大图内存压力] → 缩略图采样解码，原图只在查看时解码；首版不做压缩转码。
- [MediaRecorder 在不同设备编码差异] → 固定 AAC/M4A 与明确时长上限，异常时提示并允许重录；模拟器与真机结果分别记录。
- [孤立附件文件] → 首版允许存在但不在启动时自动删除，避免误删已发送附件；后续可按引用关系清理。
- [快照兼容性] → 新字段空值不编码，新增校验只针对非空附件；迁移测试覆盖 v7 → v8。
- [复制/编辑/合并语义] → 复制仅文字、编辑保留附件、合并忽略附件，作为明确边界写入规范与任务验收。

## Migration Plan

1. 先加数据模型与迁移，跑既有测试确认旧快照与旧数据库可读。
2. 接入附件存储与图片流程，再接入录音与播放，最后调整输入栏与气泡布局。
3. 每步运行 `testDebugUnitTest`、`lintDebug`、`assembleDebug`；模拟器检查权限拒绝、目录未授权、录音中断与重启恢复。无法执行的设备项单独记录，不记为通过。
