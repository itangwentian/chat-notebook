# Proposal

## Why

局域网同步按对话差异交换记录，但标题变更缺少端到端验证：同步协议投影中虽然包含 `title`，现有测试只覆盖正文、删除和附件的传播，没有断言“仅修改对话名称”也会推进同步版本、传到对端，并刷新对端当前打开的对话标题。用户在一台设备重命名对话时，无法确认另一台设备会得到同一个名称。

## What Changes

- 明确约定：任意一端修改对话名称后，下一次同步 SHALL 把新名称传播到自己的另一台设备，无需同时修改消息内容。
- 对端收到名称变更后，会话列表和当前打开的对话标题 SHALL 使用新名称；消息、附件和删除状态保持原有行为。
- 不改变同步协议、字段投影和附件传输；不引入按字段合并或新的冲突规则。
- 增补自动化回归：只在发起端重命名、只在对端重命名两个方向，以及同步写入后界面标题刷新。

## Capabilities

### New Capabilities

- `session-title-sync`: 局域网同步中仅修改对话名称时的传播、版本推进与对端界面刷新行为。

### Modified Capabilities

（无）

## Impact

- 代码：无生产逻辑改动，现有 `SyncConversation.title`、`RecordRepository` 的同步修订推进和 `ChatViewModel` 的会话流已满足该行为。
- 测试：`app/src/test/java/com/chatnotebook/app/data/RecordRepositoryTest.kt` 增加双向“仅改标题”同步用例；`app/src/test/java/com/chatnotebook/app/ui/ChatViewModelTest.kt` 增加外部同步改名后当前对话标题更新的用例。
- 兼容性：不改变同步协议版本、记录文件格式或已有同步结果。
