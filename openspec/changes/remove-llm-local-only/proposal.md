# Proposal

## Why

笔记内容涉及用户私密信息，任何外部 LLM 调用都存在内容外发、API Key 泄露与第三方依赖风险。应用需要彻底移除 LLM 能力，只保留可在设备本地完成的整理与提醒，降低隐私与安全暴露面。

## What Changes

- 移除设置页的模型配置、默认模型、模拟模式与测试连接入口，删除 API Key 的保存与读取能力。
- 移除 OpenAI Compatible 网络客户端、逐条消息 AI 摘要、错别字修正建议和历史消息搜索建议。
- 会话整理 SHALL 始终使用本地代码生成，不再根据模型配置选择远程或本地路径。
- 保留对话气泡的日程提醒，但改由本地规则解析常见中文时间表达；点击“创建日程”仍打开现有提醒面板，由用户确认时间后手动创建。
- **BREAKING** 应用不再提供任何外部 AI 整理、纠错或搜索能力，旧模型配置与提示词相关能力全部下线。
- 彻底清理本机已保存的模型配置与 Keystore 中的 API Key，停止局域网同步中的模型设置交换；旧记录文件中的模型字段在读取时忽略。
- 隐私政策与用户协议中关于模型配置、API Key 与远程 AI 的表述同步更新为“无任何外部 AI 接入”。

## Capabilities

### New Capabilities

- `local-only-privacy`: 应用不接入外部 AI、不保存模型凭据，并清理历史模型配置与同步设置。
- `local-session-organization`: 会话整理只使用本地代码生成文档，不发起网络请求。
- `local-message-assistance`: 对话气泡只提供本地规则识别的日程提醒，不提供错别字修正或历史搜索建议。

### Modified Capabilities

<!-- 现有主规范未包含 AI 相关能力，本次以新能力描述目标行为，无需修改既有主规范。 -->

## Impact

- 代码：删除 `com.chatnotebook.app.ai` 包、`AiProcessingService`、LLM 配置与同步设置相关类；调整 `ChatRepository`、`ChatViewModel`、`ChatScreen`、`SettingsScreen`、`SettingsViewModel`、`Snapshot`、`RecordRepository`、`RecordDatabase`、`SyncSettingsStore`、`LanSyncManager`、`MainActivity` 与 `build.gradle.kts`。
- 数据：清理 Room 索引与记录文件中的模型配置，删除 Keystore 凭据；保留历史整理文档与提醒数据。
- 同步：局域网同步不再交换模型配置与 API Key，仅同步笔记、附件、整理文档与删除信息。
- 测试：删除 AI 客户端与解析测试，新增本地时间解析、纯本地整理、凭据清理与同步不再包含设置的测试。
- 依赖：移除仅用于外部 AI 的 OkHttp 依赖与 `AI_ORGANIZE_ENABLED` 构建开关。