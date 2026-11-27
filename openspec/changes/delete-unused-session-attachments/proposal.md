# Proposal

## Why

删除对话后，该对话的 JSON 文件会被移除，但其中附件在记录目录的 `attachments/` 下继续占用空间。转发消息会在其他对话复用同一附件路径，因此清理必须先确认其他未删除对话仍未引用该文件。

## What Changes

- 删除对话并成功保存记录后，系统 SHALL 删除仅被该对话引用的附件文件。
- 清理 SHALL 按附件相对路径判断引用；若任一其他未删除对话仍引用该路径，则 MUST 保留文件。
- 其他对话中的消息即使已软删除，只要其引用仍保存在对话记录中，也 SHALL 视为有效引用。
- 单条消息软删除、编辑、合并及草稿附件移除的既有行为保持不变。
- 附件文件删除失败时 SHALL 明确报告错误，不得误报清理成功。

## Capabilities

### New Capabilities

- `attachment-lifecycle`: 定义消息附件在对话删除、跨对话复用及保留引用时的文件生命周期。

### Modified Capabilities

无。当前 `openspec/specs/` 尚无附件能力的主规范；既有 `message-attachments` 规范仍位于进行中变更的 delta 中。

## Impact

- 代码：`PersistentChatRepository.deleteSession`、`AttachmentStore` 使用处及附件清理相关数据层测试。
- 数据：不改变快照格式、Room schema 或附件路径；仅删除已无存活对话引用的文件。
- 失败语义：对话快照保存成功后再清理附件；若文件删除失败，对话删除仍已持久化，调用方必须获知清理失败，避免无提示地遗留文件。
