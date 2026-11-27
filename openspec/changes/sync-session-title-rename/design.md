# Design

## Context

局域网同步用 `SyncConversation` 投影对话，字段包含 `id`、`title`、`createdAt`、`updatedAt`、消息、编辑历史、引用、表情、合并关系与整理文档。`SessionFilePayload.syncContent()` 在决定是否推进同步修订时只归一化打开时间、处理状态、AI 请求、中间结果和整理阅读状态，`title` 与 `updatedAt` 参与比较。`SyncConversation.digest()` 对含 `title` 的规范序列化做 SHA-256，因此标题变化会改变摘要。

`ChatSessionService.rename` 通过 `RecordRepository.update` 写入 `title` 与 `updatedAt`，写入提交时按设备分量推进同步版本。接收端 `SyncLibrary.receive` 把远端 `SyncConversation.title` 映射为 `SessionRecord.title`，提交后重建 Room；`ChatViewModel` 订阅会话流并更新 `sessions` 与 `activeTitle`。

## Goals / Non-Goals

- 目标是让“只改名称”的变更具备与内容变更相同的同步保证，并有自动化证据。
- 不引入字段级合并：两端同时修改同一对话时仍按既有版本向量与冲突副本规则处理。
- 不改变协议版本、附件传输、删除传播和记录文件格式。

## Decisions

### 复用现有差异与修订机制

标题已是同步投影的一部分，标题变更会同时改变摘要与发起设备的版本分量；接收端按“摘要不同且版本为后继”接受。实现上不新增字段或事件，只在测试中固定这一行为，避免为单一字段引入第二套同步通道。

### 界面刷新沿用会话流

接收端提交后重建 Room 索引，`ChatViewModel` 的会话流把新标题写入 `sessions`，并把当前打开对话的 `activeTitle` 更新为新名称。测试通过在仓库层直接发出改名后的会话列表，验证同步写入后的界面状态路径，无需改动界面代码。

### 边界

- 若两端在未看到对方修改时分别改名，仍按并发冲突规则产生主版和冲突副本，名称带来源设备标记。
- 仅修改打开时间、阅读状态等本机字段不推进同步版本，不会误触发标题同步。

## Risks / Trade-offs

- [双设备真机验证依赖局域网与配对状态] → 用仓库级双端协议测试覆盖两个方向的仅标题同步；真机无法执行的项在实施记录中如实说明。
- [标题与消息并发修改产生冲突副本] → 属于既有设计，本变更不改变语义，只在文档中说明。

## Migration Plan

无需迁移。已有记录目录继续使用原有同步版本与摘要，标题变更在下一轮同步自动传播。

## References

- `openspec/changes/add-lan-note-sync/specs/lan-note-sync/spec.md`
- `app/src/main/java/com/chatnotebook/app/data/sync/SyncRecords.kt`
- `app/src/main/java/com/chatnotebook/app/data/RecordRepository.kt`
