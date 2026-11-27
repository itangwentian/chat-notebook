# Proposal

## Why

查看整理结果页时，进入页面会立即重新整理一次，同时仍沿用旧实现的“后台整理中”提示。应用已经没有后台整理这一设定，整理进度只应出现在列表状态与整理结果页内。当前提示会在退出结果页后弹出，误导用户以为仍有后台任务在运行。

## What Changes

- 移除整理启动成功后弹出的“后台整理中”提示。
- 整理进度仍通过对话列表的整理中状态与整理结果页的“正在整理…”展示。
- 整理启动失败时继续提示中文错误。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `session-document-organization`: 整理进度只在界面内展示，不再弹出“后台整理中”提示。

## Impact

- `ChatViewModel`：整理启动成功后不再写入 `notice`。
- 单元测试：更新整理流程断言，确认不会产生该提示。