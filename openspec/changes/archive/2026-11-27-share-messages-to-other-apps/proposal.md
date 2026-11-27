# Proposal

## Why

消息多选可以转发到应用内对话和外部应用。外部转发原先需要先打开目标选择框，再打开 Android 分享面板。用户希望在一张底部面板中直接选定目标。

## What Changes

- “转发”一次打开底部面板，同时展示内容预览、可接收的外部应用图标及其他应用内对话。
- 点选外部应用直接交接选中消息的当前正文、图片和语音。可选目标及实际接收格式由已安装应用决定。
- 保留应用内转发流程及源记录。准备失败时提示原因并保留所选消息。

## Capabilities

### New Capabilities

- `external-message-sharing` 定义选中消息交接给 Android 分享目标的行为。

### Modified Capabilities

无。

## Impact

涉及转发目标选择框、ViewModel、Activity 和应用私有分享缓存。附件通过临时只读 URI 提供。记录文件及数据库格式保持原样。
