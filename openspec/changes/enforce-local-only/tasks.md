# Tasks

## 1. 移除远程服务与配置

- [x] 1.1 删除远程服务相关的包：服务接口、客户端、错别字修正解析、历史搜索意图解析、整理结果解析、提示词与对应测试。
- [x] 1.2 精简 `ChatRepository` 接口与实现，移除消息分析、逐条处理、历史搜索、服务配置、模拟模式与默认配置方法。
- [x] 1.3 精简 `ChatUiState`、`ChatViewModel` 与 `MainActivity` 中的相关状态和接线。
- [x] 1.4 删除整理功能构建开关，整理入口、未读圆点与结果页在所有构建类型下可用。

## 2. 整理固定本地实现

- [x] 2.1 `SessionDocumentService` 只保留 `generateLocal`，移除远程整理分支与模拟模式。
- [x] 2.2 保留 `LocalSessionDocumentComposer` 的分日分组、时间升序与去重逻辑，文档来源固定为“本地整理”。
- [x] 2.3 更新整理结果页文案，只展示本地生成的时间与消息统计。

## 3. 本地日程时间解析

- [x] 3.1 新增 `LocalReminderSuggestion` 与 `LocalReminderParser`，纯 Kotlin 实现，不依赖 Android 或网络。
- [x] 3.2 支持相对分钟/小时/天、今天/明天/后天/大后天、周几、月日、年份顺延、`HH:mm`、中文数字与时段换算。
- [x] 3.3 约束只识别包含时刻的表达，结果必须晚于当前时间且不超过一年。
- [x] 3.4 新增 `LocalReminderParserTest` 覆盖上述规则与边界。
- [x] 3.5 `ChatRepository.detectReminder` 在本机解析消息并返回建议，失败返回 null。

## 4. 气泡接入与界面

- [x] 4.1 `ChatUiState` 使用 `reminderSuggestions: Map<String, LocalReminderSuggestion>` 保存建议，并随 `contentVersion` 失效。
- [x] 4.2 发送成功后由 `ChatViewModel` 请求一次解析；气泡展示日程短语与“创建日程”入口。
- [x] 4.3 点击“创建日程”复用 `ReminderTarget.suggestedAt` 打开提醒面板，用户确认后才写入提醒。
- [x] 4.4 更新 `ChatScreenTest` 与 `ChatViewModelTest`，验证建议气泡与提醒面板行为。

## 5. 数据与升级清理

- [x] 5.1 序列化结构保留旧字段以读取历史文件；新写入的整理结果、消息处理状态与配置列表为空。
- [x] 5.2 新增升级清理器：清空凭据偏好、删除 AndroidKeyStore 别名、清空旧配置墓碑偏好、移除 `record_environment` 中的旧键。
- [x] 5.3 在 `ChatRepository.open()` 恢复目录后执行清理，并用空配置重写设置文件、刷新 Room 索引。
- [x] 5.4 清理失败只记录日志，不阻断目录打开，下次启动重复执行。
- [x] 5.5 新增清理测试，并更新 `RecordRepositoryTest` 覆盖旧设置文件兼容。

## 6. 同步与设置

- [x] 6.1 移除 `SyncSettings`、`SyncSettingsStore`、`RecordSyncSettingsStore` 与配置墓碑。
- [x] 6.2 移除 `LanSyncProtocol` 的设置帧、`SyncLibrary.applySettings` 与 `LibrarySnapshot.settings`。
- [x] 6.3 `LAN_SYNC_VERSION` 提升到 3，协议不一致时拒绝同步并提示升级。
- [x] 6.4 `SettingsScreen` 只保留局域网同步与法律文档入口，删除 `SettingsViewModel` 与服务配置界面。
- [x] 6.5 更新同步测试，验证设置不再交换且协议版本为 3。

## 7. 文档与验证

- [x] 7.1 更新界面文案与法律文档，说明应用不接入外部服务、不保存凭据。
- [x] 7.2 更新 README、ABOUT 与 OpenSpec 规范，移除远程能力描述。
- [x] 7.3 运行 `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`。
- [x] 7.4 运行 `npx --yes @fission-ai/openspec@2.0.0 validate --all --strict --no-interactive`。