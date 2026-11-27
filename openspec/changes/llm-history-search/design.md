# Design

## Context

动机见 `proposal.md`，行为契约见 `specs/history-message-search/spec.md`。

当前实现的相关约束：

- 逐条建议链路已存在：`ChatViewModel.send` 落盘成功后调用 `requestAssist`，经 `ChatRepository.analyzeMessage` 与 `MessageAssistCoordinator` 做触发条件、`(messageId, contentVersion)` 去重与陈旧丢弃，再交给 `AiService.assist` → `MessageAssistParser`。
- `MessageAssist` 目前只有 `correction` 与 `reminder`，`MessageAssistPrompt` 的 JSON 契约也只声明这两项；两者都为 `null` 时解析直接返回 `null`。
- 建议气泡由 `ChatScreen.MessageList` 在目标消息之后插入独立 item，`AssistBubble` 按 `correction` / `reminder` 分段渲染，`ChatUiState.assists` 为唯一内存态。
- 跨对话跳转与定位已有入口：`ChatViewModel.openSessionFromNotification(sessionId, messageId)` 会切换对话，加载完成后滚动到消息并高亮约 5 秒。
- `PersistentChatRepository` 内部持有 `RecordRepository`，`current()` 返回包含全部会话与消息的 `RecordPayload`；消息有 `isDeleted`、`currentContent`、`createdAt`、`sessionId`，会话有 `isDeleted`、`title`。
- 主 specs 中没有已归档的 `message-assists` 能力，本次不修改任何既有主 spec 的契约。

## Goals / Non-Goals

**Goals:**

- 只增加一次已有的逐条建议请求成本，不引入新的网络端点或额外提示词请求。
- 检索完全在本地完成，会话内容不出设备。
- 搜索结果气泡与既有建议气泡共用生命周期、防陈旧和跳转机制。
- 命中不确定性（关键词不准、无结果）不产生错误提示，最多表现为「未找到相关消息」。

**Non-Goals:**

- 不做输入时实时搜索、不做专门的搜索页或全局搜索入口。
- 不做检索结果持久化、排序偏好设置或分页。
- 不把整段会话内容或历史消息列表发送给 LLM。
- 不改变错别字修正、提醒建议、同步与导出行为。

## Decisions

### 1. 复用逐条建议链路，扩展 `MessageAssist` 与提示词

在 `MessageAssist` 增加 `search: AssistSearch?`（`keywords: List<String>`），`MessageAssistPrompt` 的输出契约增加：

```json
{
  "correction": null,
  "reminder": null,
  "search": { "keywords": ["报销", "发票"] }
}
```

`search` 仅在消息明确要求查找、回忆过去的对话或历史消息时给出；关键词 1–3 个，用于本地检索，不包含「搜索」「之前」等指令词。解析校验：去重去空白后 1–3 个、单个 1–20 字符；不合法时只丢弃 `search`，不影响另外两项；三项全为 `null` 时返回 `null`。

替代方案：在发送前额外发一次「是否搜索」分类请求。放弃原因：会给每条普通消息增加一次往返延迟，且与「发送后异步建议、不阻塞发送」的既有约束冲突。替代方案：为输入框做防抖搜索端点。放弃原因：每次输入停顿都产生请求，成本与打扰都明显更高，已由需求方排除。

### 2. LLM 只产出关键词，检索在本地执行

新增只读接口 `ChatRepository.searchHistory(keywords, excludeMessageId, limit = 5): List<HistorySearchHit>`：

- 数据源为 `records.current()` 的快照；过滤 `isDeleted` 的会话与消息，消息正文为空或纯附件的跳过。
- 匹配规则：关键词与前缀为「会话标题 + 消息正文」的文本做大小写不敏感的 `contains`，至少命中一个关键词即入选；同一关键词只计一次。
- 排序：命中关键词数量降序，其次 `createdAt` 降序，最后按消息 id 稳定排序；截取前 5 条。
- `HistorySearchHit` 携带 `sessionId`、`sessionTitle`（标题为空时回退「未命名对话」）、`messageId`、`preview`、`createdAt`；`preview` 将换行与连续空白折叠为单空格后截取 60 字符。
- 排除 `excludeMessageId`（当前搜索请求消息本身）。

替代方案：把全部消息标题与摘要交给模型挑选。放弃原因：会话内容会离开设备，token 与延迟随记录量增长，且模型仍无法返回准确的消息 id。

### 3. 状态与生命周期

- `ChatUiState` 增加 `searchHits: Map<String, List<HistorySearchHit>>`，键为触发搜索的消息 id，与 `assists` 同源、同为内存态。
- `requestAssist` 在拿到带 `search` 的 `MessageAssist` 后立即调用 `searchHistory`，把 `assist` 与 `hits` 一次性写入同一个 `_uiState.update`，避免出现「有气泡无结果」的中间帧；检索抛错时按空结果处理。
- 消息被编辑或删除时，既有 `removeAssist` 同时移除 `searchHits` 条目；重启、导出与局域网同步不涉及这两个 map。
- 无命中时仍保留搜索气泡（显示「未找到相关消息」），以便用户知道请求已被识别。

替代方案：把检索结果直接塞进 `MessageAssist`。放弃原因：解析位于 `ai` 层，拿不到会话标题与实时记录；且结果依赖落盘数据，应留在 ViewModel/Repository 侧。

### 4. 气泡展示

`AssistBubble` 增加搜索段，接在错别字与提醒段之后，用 `HorizontalDivider` 分隔：

- 标题行「搜索到如下消息：」；有结果时每条结果独占一行，渲染为「会话标题：摘要」的可点击文本，不使用分隔符，行间留 6dp 间距。
- 链接使用 `buildAnnotatedString` + `LinkAnnotation.Clickable`（Compose BOM 2025.10.00 支持），样式为 `primary` + 下划线，保证可点击语义；每条带 `testTag("assist_search_hit_$index")`。
- 无结果时显示「未找到相关消息」，不渲染链接。
- 链接点击回调 `ChatActions.onOpenSearchHit(sessionId, messageId)`。

替代方案：竖直排列的按钮列表。放弃原因：需求明确是「搜索到如下消息：XXXX、XXX」的行内超链接形态，行内链接更贴近原意且占用高度更小。

### 5. 点击跳转

`ChatViewModel.openSearchHit(sessionId, messageId)`：

- 先用 `repository.messageExists(messageId)` 校验目标仍存在且未删除；会话被删除、消息被删除或校验失败时，提示「该消息已不存在」并返回，不切换对话。
- 通过校验后复用 `selectSession(sessionId, focusMessageId = messageId)`，即以既有 `PendingScroll(highlight = true)` 机制在目标对话加载完成后滚动并闪烁高亮，不新增滚动逻辑。

新增的 `messageExists` 是只读查询，不改变存储结构与写入链路。

### 6. 失败与降级

| 情况 | 行为 |
|---|---|
| mock 模式 / 无默认配置 | 与现有建议一致，不发起请求 |
| 网络错误、超时、HTTP 非 2xx | 静默丢弃，不出现气泡 |
| `search` 字段缺失或关键词非法 | 视为不是搜索意图，其余建议照常展示 |
| 本地检索抛错 | 按空结果显示「未找到相关消息」 |
| 本地检索无命中 | 显示「未找到相关消息」 |
| 结果到达前消息被编辑 | `contentVersion` 不匹配，`assists` 与 `searchHits` 均不展示 |
| 点击时目标已删除 | 提示「该消息已不存在」，不跳转 |

## Risks / Trade-offs

- [关键词召回不准导致漏检] → 提示词要求 1–3 个核心词，匹配采用任一关键词命中的宽松策略；无命中时明确显示「未找到相关消息」，避免用户误以为无响应。
- [子串匹配产生误命中] → 结果按命中关键词数量排序且最多 5 条，用户点击前可凭会话标题与摘要判断。
- [中文无分词] → 采用子串匹配而非分词，短关键词（如「报销」）通常足够；不引入新依赖。
- [结果链接过多撑高气泡] → 最多 5 条、摘要 60 字符，气泡宽度沿用现有 320dp 上限。
- [检索与展示之间存在删除竞态] → 点击时用 `messageExists` 再校验一次，失败给出提示。
- [每条消息仍只多一次已有请求] → 未新增请求次数；本地检索为纯内存遍历，记录量在常规使用规模内开销可忽略。

## Migration Plan

- 不涉及文件格式、Room 版本、同步协议与设置项变更，可直接升级与回滚。
- 回滚后唯一差异是搜索气泡不再出现，用户消息与既有建议行为不受影响。

## Open Questions

无。