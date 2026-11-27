# Design

## Context

当前会话记录包含用户私密内容，但应用仍保留 OpenAI Compatible 客户端、模型配置、API Key 与逐条消息 AI 分析。即使模型配置默认关闭，代码与密钥存储仍然扩大隐私与攻击面。本次变更移除全部外部 AI 接入，保留的整理与提醒都必须在设备本地完成。

现有数据格式（会话文件、目录快照、设置文件）通过严格 JSON 与校验和读取，直接删除字段会导致旧文件无法解析。设计需要在彻底移除 AI 能力的同时，保证旧记录仍可打开。

## Goals / Non-Goals

- Goal: 应用内不存在外部 AI 调用、模型配置界面、API Key 存储与模型设置同步。
- Goal: 会话整理始终本地执行，对话气泡只保留本地规则识别的日程提醒。
- Goal: 升级后清理本机模型配置、Keystore 中的 Key 与同步设置，旧记录文件仍可读取。
- Non-Goal: 不实现本地语义搜索或本地纠错；这两项能力直接移除。
- Non-Goal: 不改变提醒的创建流程、通知权限与闹钟调度机制。

## Decisions

### 1. 删除 AI 包与远程客户端

删除 `com.chatnotebook.app.ai` 下的服务接口、OpenAI Compatible 客户端、错别字修正解析、历史搜索意图解析、整理结果解析与提示词，以及 `AiProcessingService` 和 `MessageAssistCoordinator`。

`ChatRepository` 不再暴露 `analyzeMessage`、`processMessage`、`searchHistory`、`llmConfigs`、`mockMode`、`defaultConfigId` 与模型配置增删改测方法。`ChatUiState` 移除 `aiResults`、纠错与搜索命中的状态。

替代方案是保留模型配置并默认关闭，但用户明确要求消除隐私风险，保留死代码与密钥仍会触发安全审查，因此不采用。

### 2. 整理固定走本地实现

`SessionDocumentService` 只保留 `generateLocal` 路径，直接复用 `LocalSessionDocumentComposer`：按自然日分组、按时间升序、去重后保留最新消息。整理结果沿用现有 `SessionDocumentRecord`，来源标记为“本地整理”，不再读取模型或模拟模式。

`BuildConfig.AI_ORGANIZE_ENABLED` 删除，整理入口、未读圆点与结果页在任何构建类型下都可用。

### 3. 新增本地日程时间解析器

新增 `reminder/LocalReminderSuggestion.kt` 与 `reminder/LocalReminderParser.kt`，纯 Kotlin 实现，不依赖 Android 或网络。

解析顺序：

1. 相对时长：`N分钟后`、`N小时后`、`N天后`，以消息发送时间 `createdAt` 为基准；天级按本地时区加自然日并保留原时刻。
2. 日期表达：`今天/明天/后天/大后天`、`周X/星期X/礼拜X`、`M月D日/号`、`YYYY年M月D日`。
3. 时刻表达：`HH:mm`、`H点`、`H点半`、`H点MM分`，支持中文数字。
4. 时段修正：`凌晨/早上/上午/中午/下午/傍晚/晚上` 出现在时刻附近时换算为 24 小时制。

规则细节：

- 只有日期没有时刻时不生成建议，避免把普通记事误判为日程。
- 只有时刻没有日期时，取当天；若该时刻已过则取次日。
- 星期表达取最近的未来时刻，已过则顺延一周；月日表达当年已过则取次年。
- 结果必须晚于当前时间且不超过一年，否则丢弃。
- 一条消息存在多个时间时取第一个完整匹配；`phrase` 保留原始匹配片段用于气泡展示。
- 中文数字支持 `零一二三四五六七八九十` 与 `两`，覆盖常见 0-99。

### 4. 日程建议仅存在内存

`ChatRepository` 新增 `detectReminder(messageId): LocalReminderSuggestion?`，读取当前消息后在本地解析，失败返回 null，不抛出。`ChatUiState` 使用 `reminderSuggestions: Map<String, LocalReminderSuggestion>` 保存建议，按 `contentVersion` 与消息匹配。

发送成功后由 `ChatViewModel` 请求一次解析；点击“创建日程”复用现有 `ReminderTarget.suggestedAt` 打开提醒面板，用户确认后才写入提醒。消息编辑后 `contentVersion` 变化，旧建议自动失效。

### 5. 兼容旧文件并清理历史配置

序列化模型保留旧字段以读取历史文件，但不再产生 AI 数据：

- `SessionFilePayload.aiResults` 与消息的 `processingStatus`/`aiRequestId` 保留，解码旧文件时不报错；新写入的 `aiResults` 为空。
- `SettingsFilePayload.llmConfigs` 保留用于解码旧设置文件与校验和验证。
- 记录目录快照中的 `llmConfigs` 保留用于旧 `records-*.json` 迁移，但恢复后不再使用。

清理流程在 `ChatRepository.open()` 完成目录恢复并确保可写后执行：

1. `LegacyLlmCleanup.run(context)` 清空 `credentials` 偏好、删除 AndroidKeyStore 中的 `chat_notebook_credentials` 别名、清空 `llm_config_state` 墓碑，并移除 `record_environment` 中的 `mock_mode` 与 `default_config`。
2. 若当前记录仍带旧模型配置，调用 `RecordRepository.update { it.copy(llmConfigs = emptyList()) }` 重写设置文件并刷新索引，从而删除数据库中的模型行与磁盘上的旧配置。
3. 清理失败只记录日志，不阻断目录打开；下次启动重复执行。

不直接删除 `llmConfigs` 字段，是因为设置文件与快照使用严格 JSON 和内容校验和，删除字段会使旧文件解码或校验失败。保留只读兼容字段不构成外部 AI 接入。

### 6. 移除同步中的设置交换

删除 `SyncSettings`、`SyncSettingsStore`、`RecordSyncSettingsStore` 与 `LlmConfigTombstones`，移除 `LanSyncProtocol` 的设置帧、`SyncLibrary.applySettings` 与 `LibrarySnapshot.settings`。

`LAN_SYNC_VERSION` 从 2 提升到 3。双方协议不一致时按现有逻辑拒绝同步并提示更新设备，避免旧设备等待设置帧导致连接挂起。设置文件仍作为本地记录目录的一部分存在，但只包含空的模型列表，不再参与设备间交换。

### 7. 精简设置页

删除 `SettingsViewModel`，`SettingsScreen` 改为只接收 `onBack` 与 `syncContent`，保留局域网同步内容、隐私政策与用户协议入口。移除模型配置表单、配置卡片、模拟模式开关、默认模型选择与连接测试相关状态与文案。

## Risks / Trade-offs

- 旧版本设备无法与新版本同步：通过协议版本升级明确拒绝，优于静默挂起或继续交换设置。
- 保留兼容字段会让代码中仍出现 `AiResultRecord` 与 `LlmConfigRecord`：它们只用于读取旧文件，不参与运行逻辑、界面或网络传输。
- 本地解析覆盖面低于 LLM：通过限定常见表达与“必须包含时刻”规则控制误报，未识别时不显示建议。
- 清理失败可能暂时保留旧配置：清理可重复执行，且设置页与调用路径已删除，不会重新使用这些配置。

## Migration

1. 升级后首次打开记录目录：清理凭据与偏好，空列表重写设置文件，刷新 Room 索引。
2. 旧会话文件直接读取，`aiResults` 保留但界面不再展示。
3. 旧局域网配对记录保留，但协议版本不一致时需两台设备都升级后重新同步。

## Testing

- 新增 `LocalReminderParserTest`：覆盖今天/明天/后天、周几、月日与年份顺延、HH:mm、中文数字、时段换算、相对分钟/小时/天、已过时间、只有日期、范围上限。
- 新增 `LegacyLlmCleanupTest`：验证凭据偏好、墓碑偏好与 `record_environment` 旧键被清空。
- 更新 `ChatViewModelTest` 与 `ChatScreenTest`：只保留日程建议气泡，验证点击后打开提醒面板而非直接创建。
- 更新 `RecordRepositoryTest`：旧设置文件可读取，清理后配置为空且再次读取正常。
- 更新同步测试：`SyncLibrary` 不再接收或应用设置，协议版本为 3。
- 运行 `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 并执行 OpenSpec 严格校验。