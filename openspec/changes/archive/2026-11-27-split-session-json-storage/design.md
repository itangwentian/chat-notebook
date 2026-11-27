# Design

## Context

`RecordRepository` 目前把完整 `RecordPayload` 编码为单个 `records-<generation>-<uuid>.json`。`restore()` 读取目录中全部快照、逐个校验并取最新 generation，`update()` 每有变化就写一个完整新快照。`SafSnapshotStore` 只支持按固定前缀写入，并依靠回读校验保证落盘。

`RecordPayload` 中的实体可按对话归属拆分：

- 直接归属：`SessionRecord`、`MessageRecord.sessionId`、`SessionOrganizationState`、`MessageMergeRecord`、`SessionDocumentRecord`；
- 通过 messageId 归属：`AiResultRecord`、`EditRecord`、`QuoteRecord`、`ReactionRecord`；
- `MergeRelationRecord` 按 `targetSessionId` 归属目标对话，来源对话可以不同；
- 旧服务配置记录 `LlmConfigRecord` 是全局数据，不属于任何对话。

`merge_relations` 没有外键，删除来源对话不会破坏索引；`session_documents` 有会话外键。

## Decisions

### 1. 文件与命名

同一目录下：

- `session-<sessionId>-<20 位 generation>.json`：一个未删除对话的完整数据；
- `settings-<20 位 generation>.json`：全局服务配置；
- `records-*.json`：仅旧版本遗留，用于迁移。

generation 单调递增，用于区分同一对话的多个版本；会话 ID 原样写入文件名，解析时以最后一个 `-<20 位数字>.json` 为 generation，其余为 sessionId。

### 2. 文件结构

会话文件使用独立 envelope：

```text
schemaVersion / generation / createdAt / sessionId / payload / sha256
payload = session, messages, 旧整理结果, editHistory, quotes, reactions,
          mergeRelations, organizationState, messageMerges, sessionDocument
```

设置文件 envelope 复用 `schemaVersion / generation / createdAt / payload(旧配置列表) / sha256`。校验和覆盖 generation、createdAt 与 payload 的序列化内容。解码时校验 sessionId 一致、消息归属、派生记录引用、合并目标与整理文档归属；旧 `SnapshotCodec` 原样保留。

### 3. 写入

`update(transform)` 对变换前后的 `RecordPayload` 做逐对话 diff：

1. 校验会话 ID 唯一、消息与派生记录引用有效；
2. 对新增或内容变化的会话，以 `generation + 1` 编码写入新的会话文件，`SnapshotStore.write` 回读校验；
3. 新文件写成功后删除该会话旧 generation 文件（删除失败仅留垃圾文件，不影响正确性）；
4. 只写设置文件当 `旧配置列表` 变化或尚无设置文件；
5. 已删除或不再存在的会话删除其文件；
6. 全部成功后再更新内存 payload、generation 与 Room 索引；任一写入失败则保持旧状态，已写出的新文件留待下次恢复选取。

打开对话只改变 `lastOpenedAt`，会重写该会话文件，不影响其他对话文件。

### 4. 恢复

- 扫描目录，按 sessionId 分组会话文件，按 generation 降序尝试解码；取第一个有效文件，坏文件记录为警告并回退到更旧版本。
- 设置文件同样取最新有效版本；没有时使用空配置。
- 聚合所有有效会话后一次重建 Room。存在候选文件但没有任何有效会话、也没有有效设置时抛出 `IOException`，不删除或改写任何文件。
- 聚合结果只包含未删除会话；被删除会话即便旧文件存在也不会恢复（其文件通常已在删除时清理）。

### 5. 旧快照迁移

目录中存在旧 `records-*.json` 且不存在任何 `session-*` / `settings-*` 文件时触发迁移：

1. 解码全部旧快照，取 generation 最大者（校验失败则跳过）；
2. 按会话拆分（跳过已删除会话），写入会话文件与设置文件，保持原 generation；
3. 记录本次写入的文件名；任一写入失败时删除本次已写文件并抛出，旧快照保持不变；
4. 全部成功并校验后删除旧快照。

若目录中已存在新格式文件，则直接按新格式恢复，不再读取旧快照。

### 6. 删除与清理

`ChatSessionService.delete` 仍将会话标记为 `isDeleted`、保留消息记录；仓库在写入时跳过已删除会话并删除其文件。应用重启后该会话完全消失，符合“每对话一个文件”的清理语义。

## Risks / Trade-offs

- [打开对话也写文件] → 只为该对话生成新版本，数据量小；可接受。
- [写入中断留下新旧混合] → 每个文件独立校验，恢复时按会话取最新有效版本，不会破坏其他对话。
- [已删除会话消息在重启后消失] → 与“删除会话文件”一致；删除操作本身已不可撤回。
- [旧快照迁移耗时] → 只发生一次，且按对话拆分后后续恢复不再读取整库文件。
- [文件名包含会话 ID] → 会话 ID 为 UUID 或测试 ID，不含路径分隔符；解析以固定后缀 generation 识别。
