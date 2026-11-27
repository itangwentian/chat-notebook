# Design

## Context

`LocalSessionDocumentComposer` 只用 `message.currentContent` 拼接 Markdown，`SessionDocumentService.generate` 传入的 transcript 同样忽略格式。气泡格式保存在 `reactions`（`emoji` 为 `BubbleFormat.id`），合并消息的分段格式保存在 `MessageRecord.formatSegments[].formatId`。`DocumentExporter` 的 PDF/PNG 目前逐行绘制原始文本，`#`、`**` 等标记会直接出现在导出文件中。

## Decisions

### 1. 气泡格式到 Markdown

在数据层提供统一的格式化函数：

- 消息存在 `formatSegments` 时，逐段按 `formatId` 应用格式，段间以换行连接；
- 否则使用消息级 `BubbleFormat`（来自 `reactions`，由 `ChatRepository` 构建 `messageId -> BubbleFormat`）；
- 粗体 `**文本**`、斜体 `*文本*`、删除线 `~~文本~~`；
- 颜色使用 `<span style="color:#RRGGBB">…</span>`，与界面颜色一致：红 `#C62828`、黄 `#B26A00`、绿 `#2E7D32`；
- 样式与颜色可叠加，颜色包裹样式标记。

`ChatRepository.organizeSession` 从当前记录的 `reactions` 中筛出可解析为 `BubbleFormat` 的项并传入文档服务；本地整理都使用带格式的 transcript。

### 2. 角色标签

本地文档与 transcript 中，用户消息只保留时间，不输出“用户”；助手输出“助手”，系统输出“系统”。整理约束同步要求“用户发言不标注角色”。

### 3. Markdown 渲染

新增轻量 Markdown 解析器，识别：

- `#`/`##`/`###` 标题；
- `>` 引用；
- `-`/`*` 列表项；
- 行内粗体、斜体、删除线与颜色 span。

为两种目标分别构建：

- Compose `AnnotatedString`：用于整理结果预览，标题使用更大字号；
- Android `Spanned`：用于 PDF/PNG，通过 `StaticLayout` 排版后绘制。PDF 按页裁剪并平移绘制；PNG 在超出最大高度时逐步缩小字号后重排。

Markdown 文件直接写入原始 Markdown 源码，不做二次转义。

## Risks / Trade-offs

- [颜色依赖内联 HTML] → 标准 Markdown 无颜色语法；多数编辑器支持内联 span，PDF/PNG 由自有渲染器保证生效。
- [Markdown 解析为轻量实现] → 仅覆盖本项目生成与常见 Markdown 语法，不追求完整 CommonMark；无法识别的标记按原文显示。
- [整理输出可能不完全遵循格式] → 提示词与 transcript 同时提供格式；本地整理路径完全可控。
- [旧文档不回溯] → 仅重新整理后生成的新文档带格式，避免修改历史内容。
