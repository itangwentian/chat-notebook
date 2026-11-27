# Proposal

## Why

当前聊天列表只在切换对话或本地发送等显式 `scrollSignal` 变化时滚动到底部。其他设备通过局域网同步把新消息写入当前对话时，消息列表虽然会刷新，但列表位置保持不变，用户看不到刚同步进来的内容，需要手动滑动。

## What Changes

- 当前对话的消息数量增加时，聊天列表自动滚动到最新消息，覆盖本地发送与其他设备同步两种来源。
- 会话切换与查看引用消息的既有定位行为保持不变。
- 非当前对话的更新不触发当前列表滚动；编辑或删除已有消息不强制滚动。
- 不改变同步协议、消息写入与持久化逻辑。

## Capabilities

### New Capabilities

- `chat-auto-scroll`: 当前对话新增消息时自动滚动到最新内容的行为与边界。

### Modified Capabilities

（无）

## Impact

- 代码：`app/src/main/java/com/chatnotebook/app/ui/ChatScreen.kt`。
- 测试：`app/src/test/java/com/chatnotebook/app/ui/ChatScreenTest.kt` 增加新增消息自动滚动与编辑不滚动用例。
- 兼容性：仅影响当前打开的对话界面滚动位置，不改变消息数据、同步结果或导航行为。
