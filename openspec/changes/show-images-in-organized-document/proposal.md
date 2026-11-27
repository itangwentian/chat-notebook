# Proposal

## Why

整理结果页目前只展示文字：生成文档时图片附件被丢弃，用户回看整理结果看不到当时发过的图片。需要在“整理对话 → 查看结果 → 导出”的链路中保留图片，同时避免把本机附件路径写进会参与同步的文档正文。

## What Changes

- 整理结果页在对应正文位置显示该对话的图片附件：本地整理按时间点内联到对应 `『HH:mm』` 正文下方；AI 整理在文档末尾按时间先后统一附图。
- 图片限宽内联显示，按消息顺序排列，点击可查看原图；加载中不误报“附件不可用”，文件缺失或记录目录不可用时显示占位且不影响文字。
- Markdown、PDF、PNG 三种导出同样包含图片：Markdown 使用自包含的 base64 data URI，PDF/PNG 嵌入位图。
- 文档正文保持纯文本，不写入本机附件相对路径；图片在查看与导出时按当前对话消息实时解析，保证同步到对端后仍能显示。
- 音频附件不在本变更范围。

## Capabilities

### New Capabilities

- `organized-document-images`: 整理结果在查看与导出时展示图片附件的定位、渲染、导出与缺失处理规则。

### Modified Capabilities

（无）

## Impact

- 代码：`OrganizationResultsScreen` 的文档渲染、`ChatViewModel` 为结果会话提供消息与图片解析、`DocumentExporter` 的 Markdown/PDF/PNG 输出、可复用的附件解码与预览组件。
- 依赖：沿用现有 `AttachmentStore` / `attachmentUri` 与位图采样解码，不新增第三方库。
- 兼容性：`SessionDocumentRecord.content` 与同步协议不变；旧文档按当前消息时间点匹配显示图片，匹配不到时不显示。
