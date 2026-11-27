# Design

## Context

模型配置保存在记录目录的 settings 文件（`SettingsFilePayload.llmConfigs`），API Key 由 `CredentialStore`（Android Keystore）单独加密保存。局域网同步只在 `SyncLibrary` 中读取会话文件，`SyncManifest` 只包含对话修订；settings 文件不参与交换。同步会话通过固定证书的 TLS 传输，`SyncEpoch` 的代次变化触发交换。

## Goals / Non-Goals

- 已配对设备之间同步模型配置与 API Key，新设备无需重复配置。
- 合并结果稳定、可重复，不产生同步回环。
- 不改变对话同步协议版本与记录文件结构；默认模型等偏好仍留本机。

## Decisions

### 同步载荷与合并

新增可序列化的设置载荷：

```
SyncSettings(configs: List<SyncLlmConfig>, deleted: Map<String, Long>)
SyncLlmConfig(record: LlmConfigRecord, apiKey: String?)
```

合并规则：

- `deleted` 取两端按 id 的较晚时间戳合并。
- 配置按 id 合并：两端都有时取 `updatedAt` 较大者，相同则保留本机；不同 id 全部保留。
- 若配置的 `updatedAt` 早于其删除时间戳，则从结果中移除并删除对应 Key。
- Key 跟随被选中的配置；本机有 Key 而对端没有时保留本机 Key。

### 传输位置

在 `LanSyncProtocol.exchange` 完成清单交换后、对话拉取前插入设置交换：发起方先写后读，接收方先读后写。载荷很小，避免额外往返，也不会与现有请求/响应流程互相阻塞。收到后立即合并并应用；只有结果变化时才写入并提升代次，因此对端下一轮会收到相同结果并停止。

### 删除墓碑

`ChatRepository.deleteLlmConfig` 删除配置时，把 `id -> now` 写入本机偏好（JSON）。墓碑随设置载荷同步；合并时墓碑新于对端配置则移除对端配置及其 Key。墓碑时间戳只用于合并，不写入记录目录。

### 应用与密钥

`RecordRepository` 增加应用合并结果的方法：在一次 `update` 中替换 `llmConfigs`（仅当有差异），并返回是否变化。API Key 由拥有 `CredentialStore` 的上层在应用后写入或删除，使用本机 Keystore 重新加密。明文 Key 只存在于内存与 TLS 流中，不写记录目录、不日志、不进入同步清单摘要。

### 触发与稳定

设置变化经由现有 `RecordRepository.update` 提升代次，下一次连接满足 `epochs != last` 时交换。收到相同设置时合并不产生差异，不写文件、不提升代次。

## Risks / Trade-offs

- [已配对设备持有 API Key] → 属用户选择的便利性取舍；移除设备后不再接收后续设置，但已获取的 Key 无法远程擦除，界面需提示。
- [无版本向量，按 `updatedAt` 决胜] → 设备时钟偏差可能导致旧配置覆盖新配置；同一 id 的并发编辑以时间较晚者为准，属可接受简化。
- [协议内新增交换] → 未升级的一端会把设置帧当作同步请求解析而失败，表现为同步失败；两端需同时升级。不提升 `LAN_SYNC_VERSION`，避免已有同步目录因版本检查而无法读取。
- [墓碑长期增长] → 仅在删除时新增，数量有限；后续可加清理策略。

## Migration Plan

无需数据迁移。首次同步时若本机无墓碑记录，仅合并现有配置；旧版对端升级后自动开始交换。

## References

- `app/src/main/java/com/chatnotebook/app/data/Snapshot.kt`（`LlmConfigRecord`、`SettingsFilePayload`）
- `app/src/main/java/com/chatnotebook/app/data/sync/SyncLibrary.kt`
- `app/src/main/java/com/chatnotebook/app/data/sync/LanSyncProtocol.kt`
- `app/src/main/java/com/chatnotebook/app/security/CredentialStore.kt`
- `app/src/main/java/com/chatnotebook/app/data/ChatRepository.kt`（保存与删除配置）
