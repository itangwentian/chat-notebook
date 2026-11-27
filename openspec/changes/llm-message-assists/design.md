# Design

## Context

动机见 `proposal.md`，行为契约见 `specs/message-assists/spec.md`。

当前实现的关键约束：

- `AiService` 只有整会话能力（`organize`、`composeSession`），由 `OrganizationPrompt` 产出 `cleanedContent` / `summary` / `keywords` / `structuredData`，其中 `structuredData.times` 只是整理用的时间片段，没有「最小修正文本」，也不带时区。
- `AiProcessingService.processMessage` 与 `ChatRepository.processMessage` 已实现逐条处理与防陈旧写入，但**没有任何调用方**；`ChatUiState.aiResults` 被收集却无人消费，且测试 `derivedResultIsNotShownBesideOriginalMessage` 明确禁止在气泡旁显示派生结果。
- `ChatSessionService.edit` 要求角色为 `user`，递增 `contentVersion`、写入 `EditRecord`、重置格式化片段，并把该消息的 AI 结果置为过期。
- `ChatRepository.saveReminder(sessionId, messageId, triggerAt)` 已被提醒面板使用，返回调度结果；通知权限由界面侧 `ActivityResultContracts.RequestPermission` 处理。
- `MessageBubble` 已支持非 `user` 角色左对齐（`surfaceVariant`），但没有任何代码写入 `role = assistant` 的消息。
- `activeAiService()` 在 mock 关闭且无默认配置时抛异常；mock 模式默认为 true。

## Goals / Non-Goals

**Goals:**

- 建议只在真正命中时出现，且打扰成本接近零。
- 采纳后立即生效，并复用已有的编辑与提醒链路，不引入新的持久化结构。
- 建议失败、超时或模型输出异常时，对话界面与现有功能完全不受影响。

**Non-Goals:**

- 不把建议写成 assistant 消息，不进入会话文件、快照、导出或局域网同步。
- 不做语音转写（见决策 8）。
- 不做多轮追问、不做会话级总结，也不改造现有的手动「整理」。
- 不做建议的持久化与历史回溯。

## Decisions

### 1. 独立的建议链路，不复用 `aiResults`

新增独立的 `MessageAssist` 内存态，不复用 `AiResultRecord`。原因有两点：`aiResults` 的语义是「NLP 整理结果」，写入路径带 `processingStatus` 状态机，会污染会话文件；同时已有测试禁止把 `aiResults` 显示在原消息旁，复用会与既有约束冲突。

`ChatUiState` 增加 `assists: Map<String, MessageAssist>`（键为消息 id），只在进程内存中存在。消息被编辑或删除时由 `ChatViewModel` 移除对应条目。

### 2. 提示词与输出契约

新增 `MessageAssistPrompt` 与 `MessageAssist` 数据模型，请求一次 `/chat/completions`，`response_format = json_object`，输出：

```json
{
  "correction": {
    "correctedContent": "最小修正后的完整原文",
    "changes": [{ "original": "区", "corrected": "去" }]
  },
  "reminder": { "triggerAt": "2026-11-26T15:00:00+08:00", "phrase": "下午3点提醒我交周报" }
}
```

任意一项不适用时为 `null`。请求体中同时给出当前时间（带时区偏移），让模型输出带偏移的绝对时间，避免「明天」这类相对表达在客户端二次解析。

模型 MUST NOT 返回字符下标：高亮区间由本地在 `correctedContent` 中按 `changes` 顺序做子串定位得出，定位失败的条目直接跳过。校验规则：`correctedContent` 非空且不超过 2000 字符、必须与原文不同；`changes` 最多 10 条、每条不超过 100 字符；`triggerAt` 必须可解析为带偏移的时间且晚于当前时间 10 秒、早于当前时间 1 年。任一项校验失败只丢弃该项，不影响另一项。

替代方案：扩展 `OrganizationPrompt` 增加字段。放弃原因是一次请求要同时兼容整会话整理与逐条建议，字段上限校验（20000/2000/20/32/50/200）互相牵制，且改动会影响已完成的整理链路。

### 3. 触发与防陈旧

`sendMessage` 成功落盘后，`ChatViewModel` 发起 `ChatRepository.analyzeMessage(messageId)`：

- 仅当 `AiService.mock == false` 且存在启用的默认配置时才发起；取服务失败直接返回 `null`，不弹任何提示。
- 只处理 `role == user` 且没有语音附件的消息。
- 同一 `(messageId, contentVersion)` 只请求一次，重复触发直接复用结果或忽略。
- 返回时比对消息当前 `contentVersion`，不一致则丢弃结果，避免把基于旧内容算出的修正套用到新内容上。

不显示「分析中」占位：气泡只在结果到达且命中时出现，避免每次发送都闪一下界面。

### 4. 气泡形态与位置

在 `MessageList` 的 `LazyColumn` 中，为用户消息之后插入一个独立 item，渲染 `AssistBubble`：

- 左对齐、宽度不超过消息气泡上限，视觉上复用 `MessageBubble` 非 user 分支的圆角与 `surfaceVariant` 配色，但不进入 `MessageRecord` 渲染路径。
- 修正建议：显示 `correctedContent`，`changes` 命中的片段用 `tertiary` 背景高亮；文本超过 4 行时折叠。
- 提醒建议：显示一行时间文案（如「明天 09:00 提醒你」）与原文片段。
- 两类建议同时命中时，同一个气泡内分两段展示，各自带一组按钮。
- 按钮使用 `TextButton` + 小内边距做视觉收缩，依赖 Material 3 的最小交互尺寸保证 48dp 命中区，满足「小巧但容易点击」。
- 每个按钮带稳定的 `testTag`（`assist_apply_correction`、`assist_ignore_correction`、`assist_apply_reminder`、`assist_ignore_reminder`）。
- 气泡到达时列表若有新增 item（消息或建议气泡），自动滚动到底部，保证迟到数秒的建议气泡直接可见；用户已上滑查看历史时同样贴底一次。

### 5. 采纳错别字

直接调用 `ChatRepository.editMessage(messageId, correctedContent)`，复用 `ChatSessionService.edit` 的既有语义：递增 `contentVersion`、写入 `EditRecord`、重置格式化片段、把旧 AI 结果置为过期。因此「更新用户内容」与「写入修改历史」由现有链路天然满足，不新增代码路径。成功后从 `assists` 中移除该条目。

编辑不会触发新的建议分析，避免「修正 → 再修正」的循环。

### 6. 采纳提醒

点击气泡上的「创建日程」不直接创建，而是打开既有的提醒创建面板（与消息菜单中的「提醒」共用），把识别出的 `triggerAt` 作为预填时间；用户可在面板中改日期/时间、选预设或取消，确认后仍走既有的 `saveReminder` 流程。Android 13+ 未授予 `POST_NOTIFICATIONS` 时先发起权限请求，再保存；保存结果（精确 / 降级 / 无通知权限）沿用现有的 Snackbar 提示。消息已删除或时间已过期则不打开面板，只移除气泡。

### 7. 失败与降级

| 情况 | 行为 |
|---|---|
| mock 模式 / 无默认配置 | 不发起请求，无任何界面变化 |
| 网络错误、超时、HTTP 非 2xx | 静默丢弃，消息与其他功能不受影响；超时上限沿用配置中的 `timeoutSeconds` |
| 返回非 JSON 或字段缺失 | 视为未命中，不显示气泡 |
| 校验未通过的单条建议 | 只丢弃该条，另一条照常展示 |
| 请求返回时会话已切换 | 结果仍按消息 id 缓存，切回时展示 |

### 8. 语音转写不纳入本次范围

对提供的 OpenAI 兼容配置做了实测：`/compatible-mode/v1` 下 `response_format = json_object` 正常，逐条建议的 JSON 输出符合契约；但把 wav 以 `input_audio` 内容部件提交时，服务端返回 **HTTP 200 且静默丢弃音频**，模型回复「没有收到任何音频」，无法通过错误码判断不兼容；`/audio/transcriptions` 返回 404。因此「复用主模型多模态、失败就不处理」在本配置下会静默失效，语音转写不纳入本次变更，等有确定的转写通道后再单独立项。

## Risks / Trade-offs

- [每条消息额外一次请求] → 只在非 mock 且配置可用时触发；单条建议的提示词只包含一条消息文本，token 开销可控；不提供开关之外的额外策略。
- [推理型模型延迟] → 实测同一配置下逐条建议耗时 1.7–3.4 秒；因为不显示占位，延迟只表现为气泡稍后出现。
- [模型改写超出错别字范围] → 提示词明确要求只替换错别字，并把 `changes` 与 `correctedContent` 一并返回，用户可逐条判断；校验要求 `correctedContent` 与原文不同且长度受限。
- [重复建议] → 按 `(messageId, contentVersion)` 去重，编辑后版本变化使旧建议失效。
- [内存态丢失] → 进程结束或重启后建议消失，这是「不落盘」的预期代价；采纳产生的修改、修改历史与提醒均已持久化。

## Migration Plan

- 不涉及文件格式、Room 版本与同步协议变更，可直接升级与回滚。
- 回滚时唯一残留是用户已采纳并写入的修改历史与提醒，两者都是既有能力产生的正常数据。
- 无需发布说明要求两端同时升级。