# Proposal

## Why

设置里配置的模型目前只存在于本机：`LlmConfigRecord` 在记录目录的 settings 文件中保存，API Key 由本机 Keystore 单独加密，局域网同步只交换对话与附件。因此在新设备上必须重新配置模型。用户希望已配对设备之间也能同步模型配置。

## What Changes

- 在现有加密局域网会话中增加“模型设置”交换：同步模型名称、协议、Base URL、模型名、超时、启用状态和 API Key。
- 合并规则：按模型 id 合并，同一 id 取 `updatedAt` 较新的版本；两端新增的不同模型都保留；删除通过带时间戳的墓碑传播。
- 接收端使用本机 Keystore 重新加密 API Key；明文 Key 只存在于加密会话内存中，不写入记录目录、不记录日志。
- 默认模型选择、草稿等设备偏好仍留在本机；未变化时不产生写入或同步回环。

## Impact

- 影响 `SyncLibrary`、`LanSyncProtocol`、`RecordRepository`、`ChatRepository.deleteLlmConfig` 与凭据存储。
- 不改变对话同步协议版本与记录文件格式；旧设备忽略新增设置交换（需同时升级才能同步模型）。
- 已配对设备将互相信任并持有 API Key，移除设备后不再接收后续设置。
