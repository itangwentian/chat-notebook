# Tasks

## 1. 搜索意图与解析

- [x] 1.1 在 `ai/MessageAssist.kt` 增加 `AssistSearch(keywords: List<String>)`，`MessageAssist` 增加 `search: AssistSearch?`；扩展 `MessageAssistParser`，关键词去重去空白后要求 1–3 个、单个 1–20 字符，非法只丢弃 `search`，三项全空仍返回 `null`。验证：扩展 `MessageAssistParserTest`，覆盖仅命中搜索、关键词为空/超数量/超长、与修正或提醒共存、三项全空。
- [x] 1.2 扩展 `MessageAssistPrompt` 的系统提示词与示例 JSON，加入 `search` 字段、触发语义与关键词约束，并保持「只输出 JSON」等既有约束不变。验证：用 `OpenAiCompatibleClientTest` 或新增解析用例断言旧的两项输出仍能解析，新字段可按契约解析。

## 2. 跨对话本地检索

- [x] 2.1 新增 `HistorySearchHit(sessionId, sessionTitle, messageId, preview, createdAt)`，并在 `ChatRepository` 增加只读方法 `searchHistory(keywords, excludeMessageId, limit = 5)`；基于 `records.current()` 过滤已删除会话与消息，按关键词对「会话标题 + 正文」做大小写不敏感匹配，至少命中一个关键词，排除请求消息，按命中数降序、时间降序、id 稳定排序并截取前 5。验证：新增单测覆盖跨会话命中、排除请求消息与已删除内容、多关键词命中数排序、时间排序、上限与空结果。
- [x] 2.2 `preview` 将换行与连续空白折叠为单空格后截取 60 字符，标题为空时回退「未命名对话」。验证：单测断言长消息与多行消息的摘要格式及空标题回退。

## 3. 状态与跳转动作

- [x] 3.1 `ChatUiState` 增加 `searchHits: Map<String, List<HistorySearchHit>>`；`requestAssist` 在 `assist.search != null` 时调用 `searchHistory`，并把 `assists` 与 `searchHits` 放在同一次 `_uiState.update` 写入，检索异常按空结果处理。验证：`ChatViewModelTest` 断言命中搜索时两者同时出现，无命中时 `searchHits` 为空但仍保留 `assists` 条目。
- [x] 3.2 `removeAssist` 同步移除 `searchHits`，消息编辑与删除的既有入口据此让结果失效。验证：单测断言编辑或删除请求消息后搜索结果消失。
- [x] 3.3 `ChatRepository` 增加只读 `messageExists(messageId)`；`ChatViewModel.openSearchHit(sessionId, messageId)` 先校验目标存在，再复用 `openSessionFromNotification(sessionId, messageId)` 完成切对话、滚动与闪烁；校验失败时提示「该消息已不存在」。验证：单测覆盖目标存在时切换并设置 `scrollTargetId` 与 `highlightMessageId`，目标不存在时显示提示且不切换。

## 4. 搜索结果气泡

- [x] 4.1 `AssistBubble` 增加搜索段：有结果时显示「搜索到如下消息：」，每条结果独占一行，以「会话标题：摘要」渲染可点击文本（`primary` 色 + 下划线），不使用分隔符；无结果显示「未找到相关消息」；每条带 `testTag("assist_search_hit_$index")`。验证：`ChatScreenTest` 断言标题、链接文本、无结果文案与 testTag 出现。
- [x] 4.2 `ChatActions` 增加 `onOpenSearchHit(sessionId, messageId)`，`MainActivity` 接到 `ChatViewModel.openSearchHit`；`MessageList` 把 `searchHits[message.id]` 传给 `AssistBubble`。验证：Compose 测试点击某一结果链接后回调携带正确的会话与消息 id。

## 5. 集成验证

- [x] 5.1 运行 `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；验证：构建成功、单元测试全部通过、lint 无新增错误。
- [x] 5.2 运行 `npx --yes @fission-ai/openspec@2.0.0 validate llm-history-search --strict --no-interactive` 与 `validate --specs --strict --no-interactive`；验证：全部通过。
- [x] 5.3 在模拟器用真实 LLM 配置人工验证：发送「帮我找一下之前说过的报销」后出现结果气泡；点击结果跳到对应对话并高亮目标气泡；无命中时显示「未找到相关消息」；断网时静默；重启后结果消失。验证：逐项记录实际结果，未完成项如实标注。
  - 验证记录（2026-11-27，emulator-5554，真实模型，mock 关闭）：发送 “find the message about carol” 得到「搜索到如下消息：」及两条可点击结果，点击后切到目标对话并高亮目标气泡；发送无命中查询显示「未找到相关消息」；开启飞行模式后发送不出现气泡、无错误提示；重启应用后结果气泡消失、消息仍在。

## Workflow follow-up

- 实现完成并验证后归档该变更。