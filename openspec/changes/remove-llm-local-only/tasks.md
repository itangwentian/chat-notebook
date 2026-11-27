# Tasks

## 1. 本地日程建议

- [x] 1.1 新增 `reminder/LocalReminderSuggestion.kt` 与 `reminder/LocalReminderParser.kt`，实现相对时长、日期、时刻、时段与中文数字解析；用 `LocalReminderParserTest` 覆盖设计中的全部时间样例
- [x] 1.2 为解析器补充边界测试：已过时间、只有日期、超过一年、非法时分、多时间取首个，并确认越界均返回 null
- [x] 1.3 在 `ChatRepository` 中以 `detectReminder(messageId)` 暴露本地解析，移除旧的 `analyzeMessage` 接口；用仓储或 ViewModel 测试验证解析失败返回 null 且不抛异常

## 2. 移除 AI 调用与数据通路

- [x] 2.1 删除 `com.chatnotebook.app.ai` 包、`AiProcessingService` 与 `MessageAssistCoordinator`；运行 `.\gradlew.bat :app:compileDebugKotlin` 确认无残留引用
- [x] 2.2 删除 `ChatRepository` 中的 AI 分析、搜索、逐条处理、模型配置、模拟模式与默认模型方法及对应状态；运行编译确认接口收缩后唯一的实现类仍可编译
- [x] 2.3 将 `SessionDocumentService` 收敛为本地 `generateLocal`，删除 AI 整理解析路径；运行 `SessionDocumentServiceTest` 与整理相关测试确认输出按日分组、去重
- [x] 2.4 更新 `ChatViewModel`/`ChatScreen` 的气泡状态为 `reminderSuggestions`，删除纠错与搜索命中；用 `ChatViewModelTest` 与 `ChatScreenTest` 验证只显示日程建议
- [x] 2.5 保持“创建日程”打开提醒面板并预填建议时间，补充界面测试确认未确认前不写入提醒

## 3. 设置页与入口

- [x] 3.1 删除 `SettingsViewModel` 与设置页模型配置、模拟模式、默认模型、连接测试相关的状态、表单与文案；编译并运行 `SettingsScreenTest` 确认设置页仍可打开
- [x] 3.2 删除 `BuildConfig.AI_ORGANIZE_ENABLED` 及 `app/build.gradle.kts` 中的构建字段，整理入口、未读圆点与结果页改为始终可用；运行 `.\gradlew.bat :app:assembleDebug` 确认构建成功
- [x] 3.3 更新 `MainActivity`、`ChatScreen` 中依赖该开关的分支和旧气泡回调；用界面测试确认整理入口在 debug 构建可见

## 4. 清理旧配置与同步设置

- [x] 4.1 新增 `LegacyLlmCleanup`，清空 `credentials` 偏好、删除 AndroidKeyStore 中的 `chat_notebook_credentials` 别名、清空 `llm_config_state`，并移除 `record_environment` 中的 `mock_mode` 与 `default_config`；用单元测试验证键被清除
- [x] 4.2 在 `ChatRepository.open()` 恢复目录后调用清理，并在存在旧模型配置时用 `update { it.copy(llmConfigs = emptyList()) }` 重写设置文件；用 `RecordRepositoryTest` 验证旧设置可读取、清理后配置为空且重开正常
- [x] 4.3 删除 `SyncSettings`、`SyncSettingsStore`、`RecordSyncSettingsStore`、`LlmConfigTombstones` 及同步设置帧/应用逻辑；运行同步相关测试确认笔记同步仍正常
- [x] 4.4 将 `LAN_SYNC_VERSION` 提升到 3，确认协议不一致时返回明确错误；更新 `LanSyncTest` 或等价测试覆盖版本拒绝

## 5. 兼容与文案

- [x] 5.1 保留 `SessionFilePayload.aiResults`、消息处理字段与 `SettingsFilePayload.llmConfigs` 仅用于旧文件解码，停止写入新的 AI 数据；用旧格式样本测试解码与校验和通过
- [x] 5.2 更新 `privacy_policy.txt` 与 `user_agreement.txt`，删除模型设置、API Key 与远程 AI 表述，明确应用不接入外部 AI；人工核对文案无模型配置残留

## 6. 集成验证

- [x] 6.1 运行 `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`，确认全部通过
- [x] 6.2 在模拟器上验证：设置页无模型配置、离线可整理、发送“明天下午3点开会”出现日程建议、点击后打开提醒面板、发送错别字与“之前说过”无建议
  - 验证记录：模拟器断网后确认设置页无模型配置、整理结果显示“本地整理”、发送 `meeting 15:00` 出现“提醒建议”并能预填提醒面板；旧消息无纠错或搜索气泡。中文“明天下午三点”等表达由 `LocalReminderParserTest` 覆盖。
- [x] 6.3 运行 OpenSpec 严格校验并修复所有报告问题

## Workflow follow-up

- 在两台设备或两个模拟器上验证升级后旧模型配置不再出现，且同版本设备可完成局域网同步。
- 经用户确认后提交 SVN 变更。