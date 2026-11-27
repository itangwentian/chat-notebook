# Design

## Context

整理文档以 Markdown 文本保存在 `SessionDocumentRecord.content`，结果页用 `markdownAnnotatedString` 一次性渲染；图片附件保存在用户记录目录的 `attachments/` 下，消息通过 `MessageAttachment.relativePath` 引用，界面经 `ChatActions.attachmentUri(relativePath)` 取得可读 URI。同步会为对端重新生成附件文件名，因此把本机相对路径写入文档正文会在对端失效。

## Goals / Non-Goals

- 在不改文档正文、持久化格式和同步协议的前提下，查看与导出时补上图片。
- 本地整理按时间点内联，会话整理在末尾附图；图片限宽、可点开大图。
- 不为逐张图片新增数据库表或快照字段。

## Decisions

### 文档正文保持纯文本

`SessionDocumentRecord.content` 不写任何附件路径。查看和导出时根据当前会话消息实时解析图片，接收端使用自己的附件副本，避免路径失效，也无需迁移旧文档。

### 图片与正文的对应关系

- 复用本地整理的筛选规则重建“保留消息”：未删除、正文非空、按归一化正文去重保留最新、按 `createdAt` 与 `id` 升序。
- 本地整理文档：逐块解析 Markdown，遇到以 `『HH:mm』` 开头的段落（兼容旧的 `**HH:mm**` 时间行）时，按顺序绑定下一条时间字符串相同的保留消息，把该消息的图片插入该段之后。时间重复时按出现顺序依次消费，多余图片不显示。
- 非本地整理文档（`modelSnapshot != "本地整理"`）：在正文末尾按消息时间顺序统一附图；同一消息多张图片按附件顺序排列。
- 仅处理 `kind == "image"` 的附件；音频忽略。

### 结果页渲染

结果页把文档拆成“文字块 + 图片块”列表：文字块沿用现有 Markdown 渲染，图片块使用与聊天附件一致的最大宽度、圆角与点击预览。解码沿用 `decodeSampledBitmap`（最长边约 1600）并按路径缓存；沿用 `AttachmentImageState` 的 `loaded` 标记，解码完成前只显示底色，失败才显示“附件不可用”。ViewModel 为结果会话提供消息流，界面通过 `attachmentUri` 解析 URI。

### 导出

- 统一构造一次“块列表”（文字 + 图片位图引用），三种格式复用。
- Markdown：在文字块之间插入 `![图片](data:image/jpeg;base64,...)`；图片先缩放到最长边约 1280、JPEG 质量约 0.8（带透明通道保留 PNG），以控制文件体积；单张或累计体积过大时提示改用 PDF。
- PDF：按现有分页绘制文字块，图片按页面可用宽度等比缩放，必要时分页，不截断图片。
- PNG：在单张长图中绘制图片；超过现有单图高度上限时沿用“改用 PDF 或 Markdown”的提示。
- 导出失败给出中文提示，不影响已保存文档。

### 边界

- 记录目录未授权或文件缺失：文字正常显示，图片位置显示占位。
- 旧文档：按当前消息时间点匹配；匹配不到则不附图。
- 结果会话的消息随时间更新时，图片列表随之更新，文档文字保持生成时的内容。

## Risks / Trade-offs

- [同一分钟内多条消息] → 按文档中出现顺序消费同时间消息，位置可能相邻但不会错位到其他时间点。
- [非本地整理文档末尾集中附图] → 该类文档没有可靠的内联锚点，属已确认的取舍。
- [Markdown 内嵌图片体积大] → 缩放并压缩，必要时提示改用 PDF。

## Migration Plan

无数据迁移。旧文档不重写；重新整理后仍生成纯文本文档，图片在查看与导出时补入。

## References

- `app/src/main/java/com/chatnotebook/app/data/LocalSessionDocumentComposer.kt`
- `app/src/main/java/com/chatnotebook/app/ui/ChatScreen.kt`（整理结果页与附件缩略图）
- `app/src/main/java/com/chatnotebook/app/data/AttachmentStore.kt`
- `openspec/changes/restructure-organized-document-format/`（时间前缀格式）
