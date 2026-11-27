# Proposal

## Why

笔记内容涉及用户私密信息。应用只需在本机完成记录、整理与提醒，不发起任何外部调用、不保存任何服务凭据，从而降低隐私与安全暴露面。

## What Changes

- 会话整理 SHALL 始终使用本地代码生成，不发起网络请求，不读取任何服务配置或凭据。
- 对话气泡只保留由本地规则识别的日程提醒，不提供远程分析、纠错或历史搜索建议。
- 设置页只保留局域网同步与法律文档入口，不提供服务配置、连接测试或凭据管理。
- 应用 MUST NOT 保存第三方服务凭据；升级时清理旧版本遗留的配置、凭据与同步设置。
- 局域网同步只交换笔记、附件、整理文档与删除信息。

## Capabilities

### New Capabilities

- `local-only-privacy`: 应用不接入外部服务、不保存凭据，并清理历史遗留配置。
- `local-session-organization`: 会话整理只使用本地代码生成文档，不发起网络请求。
- `local-message-assistance`: 对话气泡只提供本地规则识别的日程提醒，不提供纠错或历史搜索建议。

### Modified Capabilities

<!-- 现有主规范未包含远程服务相关能力，本次以新能力描述目标行为，无需修改既有主规范。 -->

## Impact

- 代码：移除远程服务相关的包、客户端与处理服务；调整 `ChatRepository`、`ChatViewModel`、`ChatScreen`、`SettingsScreen`、`Snapshot`、`RecordRepository`、`RecordDatabase`、`LanSyncManager`、`MainActivity` 与 `build.gradle.kts`。
- 数据：清理 Room 索引与记录文件中的旧配置字段值，旧字段保留只读兼容；删除安全存储中的历史凭据；保留历史整理文档与提醒数据。
- 同步：局域网同步不再交换设置，仅同步笔记、附件、整理文档与删除信息。
- 测试：删除远程客户端与解析测试，新增本地时间解析、纯本地整理、凭据清理与同步不再包含设置的测试。
- 依赖：移除仅用于外部调用的网络依赖与整理功能构建开关。