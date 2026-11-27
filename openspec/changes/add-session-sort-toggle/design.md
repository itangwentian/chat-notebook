# Design

## Context

侧栏会话来自 `ChatRepository.sessions`，Room 查询按 `lastOpenedAt DESC`。`SessionRecord` 已有 `createdAt`、`updatedAt`、`lastOpenedAt`。`ChatSessionService` 的发送、编辑、删除、合并、格式修改此前没有更新会话 `updatedAt`，不能直接用于“有效修改”排序。

## Decisions

### 1. 排序状态与位置

在 `ChatUiState` 增加 `sessionSortOrder: SessionSortOrder`（默认 `Created`），在 ViewModel 内完成排序，Repository 与数据库排序保持不变。`sessions` Flow 每次发射时按当前模式重新排序；切换时立即重排现有列表并显示提示。重启回到默认，不写偏好设置，避免为单一 UI 偏好扩展 Repository。

排序均为降序，使用 `id` 作为并列时的次级键，保证稳定。

### 2. 有效修改语义

在 `ChatSessionService` 中补齐 `SessionRecord.updatedAt`：

- `send`：新消息写入时同步更新所属会话；
- `edit`：正文实际变化时更新；
- `deleteMessages`：按被删除消息所属会话去重后更新；
- `mergeMessages`：更新目标会话；
- `setBubbleFormat`：格式变化时更新；
- `rename`：沿用既有更新。

`markOpened` 只更新 `lastOpenedAt`，不触碰 `updatedAt`。

### 3. 按钮与图标

“历史对话”标题改为 `Row`，标题占权重，右侧放 `FilledTonalIconButton`，尺寸与 Material 默认一致。使用两个自绘线条矢量图标：`ic_sort_created`（时钟 + 向下箭头）与 `ic_sort_modified`（铅笔 + 向下箭头），与项目现有线条图标风格一致；不显示文字。`contentDescription` 说明当前模式与点击结果，切换时用 Toast 给出明确反馈（提示条位于侧栏后方，打开侧栏时不可见，因此不用 Snackbar）。测试标签为 `session_sort`。

## Risks / Trade-offs

- [修改格式是否算修改] → 视为有效修改，符合“内容呈现变化”，并在规范中写明。
- [排序不持久化] → 满足“默认创建时间”，避免额外存储与迁移；如后续需要记忆偏好再加。
- [Room 查询仍按 lastOpenedAt] → 状态列表在 ViewModel 中重排，不影响活动会话选择（仍取最近打开）。
