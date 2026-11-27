# Proposal

## Why

用户所说的“分组”指对话本身：初次安装后应默认存在“极其重要”和“草稿”两个对话，并默认选中“草稿”。此前误实现为对话分组筛选，新增了分组标签、归属与移动功能，与预期不符。

## What Changes

- 记录目录首次就绪且没有任何对话时，自动创建“极其重要”和“草稿”两个默认对话，并选中“草稿”。
- 之后启动沿用最后一次打开的对话（现有 `lastOpenedAt` 逻辑），不重复创建默认对话。
- 移除对话分组功能：分组标签、分组归属、移动对话菜单、`SessionGroupStore` 与 `conversation-groups` 规范。
- 对话列表恢复为展示全部对话，空列表提示改为“暂无对话”。

## Impact

- 修改 `ChatSessionService`、`PersistentChatRepository`、`ChatViewModel`、`ChatScreen`、`MainActivity`。
- 删除 `SessionGroupStore.kt`，新增 `default-conversations` 规范，移除 `conversation-groups` 规范。
- 不涉及记录文件格式与局域网同步协议。