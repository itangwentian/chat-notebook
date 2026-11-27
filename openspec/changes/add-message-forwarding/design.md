# Design

## Context

多选由 `ChatUiState.multiSelect` 与 `selectedMessages` 驱动，工具栏已有复制、删除、合并。合并通过 `ChatSessionService.mergeMessages` 在目标会话内生成新消息。转发可复用“生成新消息”的写入模式，但目标会话不同且不删除原消息。

## Decisions

### 1. 服务层转发

新增 `ChatSessionService.forwardMessages(sourceSessionId, messageIds, targetSessionId): Int`：

- 校验源、目标存在且未删除，源与目标不同，选中消息全部属于源且未删除；
- 按 `createdAt`、`id` 排序，逐条复制 `role` 与 `currentContent`；
- 每条新消息生成新 `id`，`createdAt/updatedAt = now() + index`，保证在目标末尾且顺序稳定；
- 不复制 `formatSegments`、引用、表情、编辑历史、AI 结果与合并关系；
- 更新目标会话 `updatedAt`，源会话不改；返回复制条数。

选择复制 `currentContent` 而不是 `rawContent`，因为用户转发的是当前看到的内容（含编辑后的文本）。

### 2. 界面流程

`ChatUiState` 增加 `forwardPickerVisible`。多选工具栏增加“转发”文本按钮（`forward_selected`）。点击后由 ViewModel 打开 `ForwardMessagesDialog`，其 `sessions` 过滤当前 `activeSessionId` 并按现有排序展示；每行 `forward_target_<id>`。取消仅关闭选择框，不清除多选；确认后调用 Repository，成功后退出多选并提示。目标为空时只显示新建提示。

### 3. 失败与一致性

所有校验在 `RecordRepository.update` 内基于同一快照完成，选中消息在确认前被删除会整体失败，不产生部分转发。写入沿用单快照事务与版本化文件规则。转发到目标会话后，若目标不是当前会话，依赖 Room/Flow 在下次打开时展示，不主动跳转。

## Risks / Trade-offs

- [不保留气泡格式] → 首版保持转发内容简单、可预测；如需保留可后续扩展 `reactions` 复制。
- [不跳转到目标] → 满足“继续当前工作”，通过提示与侧栏红点外的方式告知结果；目标会话排序会变化。
- [时间戳使用当前时间] → 避免复制消息插入目标历史中间，符合会话追加直觉。
