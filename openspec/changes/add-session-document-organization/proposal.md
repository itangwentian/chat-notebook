# Proposal

## Why

现有整理功能逐条调用模型，只处理用户消息，结果页按消息堆叠卡片，无法回答“这段对话完整讲了什么”。用户需要把当前对话的全部历史一次性交给已配置的模型，整理成一篇无遗漏、按时间顺序的中文文档，并能保存到本地长期保存。

## What Changes

- 整理范围：一次整理 SHALL 读取所选对话的全部未删除消息（用户与助手），按 `createdAt`、`id` 升序拼成带本地时间戳的文本，一次性发送给当前默认模型。
- 输出形态：模型 SHALL 输出一篇 Markdown 中文文档，要求按时间顺序、覆盖全部要点、不编造、不遗漏；系统不再为每条消息生成独立的摘要卡片。
- 本地兜底：未配置默认模型、凭据不可用或处于离线模式时，SHALL 由本地代码直接汇总：按时间顺序、按日期分组排版，空白归一后内容重复的消息只保留最新一条，仍生成可查看、可导出的文档，并标注“本地整理”。
- 结果页：整理结果页 SHALL 展示整篇文档，包括生成时间、消息条数、模型标识与模拟标识；整理中显示进度，失败显示中文错误并可重新整理。
- 导出：结果页 SHALL 支持将文档保存到本地，格式为 Markdown（`.md`）、PDF（`.pdf`）和 PNG（`.png`），通过系统“创建文档”界面选择保存位置。
- 持久化：整篇文档作为会话级派生记录写入既有版本化快照；空值不参与编码以保持旧快照校验和可解析；Room 增加 `session_documents` 表并显式迁移 v6 → v7。
- 通知：整理成功 SHALL 继续复用现有未读红点与“查看整理结果”入口；未配置模型、未选择目录、无消息或请求失败时 SHALL 明确提示，不伪造成功。
- 非目标：不做分段/分页的模型链式摘要，不做多轮追问式整理，不导出 Word/HTML，不把导出文件写回记录目录，不改变消息原文与既有个别消息整理数据结构。

## Capabilities

### New Capabilities

- `session-document-export`：将整理后的会话文档导出为本地 MD/PDF/PNG 文件。

### Modified Capabilities

- `ai-organization`：整理输入由“逐条用户消息”改为“整段会话历史”，整理结果由“逐条派生卡片”改为“单篇时间顺序文档”；先保存再整理、手动触发、未读通知与失败不伪造成功的约束保持不变。

## Impact

- 代码：`Snapshot.kt`、`RecordDatabase.kt`（v7 迁移）、`RecordRepository`、`ChatRepository`、`AiService`/`OpenAiCompatibleClient`、`ChatViewModel`、`ChatScreen`、`MainActivity`，新增会话文档生成与导出辅助。
- 数据：快照新增可选 `sessionDocuments` 字段；Room 新增 `session_documents` 表。既有消息、逐条 `aiResults`、编辑历史、引用、表情与合并关系保持不变。
- 验证：新增快照兼容、迁移、提示词/文档生成、导出与 Compose 测试；模拟器验证整理、结果页与三种导出格式。
