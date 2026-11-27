# Design

## 背景

`differences()` 用每台设备的版本向量判断先后；两端在各自设备上并发修改同一对话时得到 `VersionOrder.Concurrent`，由 `resolveConflict()` 处理。当前实现选定“主版”，把另一版改写成新 id 的副本对话（标题带「同步冲突」）。两端都要独立算出同一结果，因此任何规则都必须可交换、确定。

## 决策

### 合并而不是副本

`resolveConflict()` 返回单个合并后的 `ConversationRevision`，对话 id 保持原 id：

- `versions` 取双方逐设备最大值（与 `MergeVersions` 一致），保证合并版本严格晚于本机版本，满足 `applySyncRevisions` 的“后继版本”校验。
- `author` 取两端 `author` 的较小值，保证两端结果一致。
- 合并后对话摘要由内容重新计算，两端的清单条目因此完全相同。

### 合并规则

- 对话：`createdAt` 取较小、`updatedAt` 取较大。
- 标题：取会话 `updatedAt` 较新一侧；时间相同则取序列化摘要较小的一侧，保证可交换。
- 消息：按 id 并集；同 id 不同内容时依次比较 `updatedAt`、`contentVersion`、消息序列化内容，取较大者（严格确定）。消息按 `createdAt`、`id` 排序后写入文件，界面读取时本就按该顺序。
- 编辑历史：按 `(messageId, fromVersion, toVersion)` 并集；同键取 `editedAt` 较新者，再按序列化内容兜底。
- 引用：按 `messageId` 并集，同键取“首选会话”一侧（见下）。
- 表情：按 `(messageId, emoji)` 并集。
- 消息合并关系：按 `id` 并集，同键取首选会话一侧。
- 消息合并记录：按 `messageId` 并集，同键取首选会话一侧。
- 整理文档：按 `generatedAt` 取较新者，仍相同则取首选会话一侧。

“首选会话”= 会话 `updatedAt` 较大的一侧；时间相同则比较内容摘要，取较小摘要对应的一侧。该定义与入参顺序无关，两端一致。

### 删除冲突保持现状

一端对话已删除（清单 `digest == null`）而另一端并发编辑时没有可并集的内容，保留原行为：`primary` 为删除墓碑，`copy` 为被编辑内容的副本。`ConflictResolution.copy` 因此允许为 `null`，`LanSyncProtocol` 只应用非空结果。

## 风险

- 冲突解决后消息量增大，需注意长对话的摘要计算开销；冲突属于罕见路径，可接受。
- 合并会连附件并集一起引入，接收端通过现有 `SyncAttachments.prepare` 从对端拉取缺失附件；若对端同时缺少该附件仍会按现有逻辑报错。
- 旧版本客户端不认识该行为（协议版本未变），混用时仍产生副本；升级两端后一致。