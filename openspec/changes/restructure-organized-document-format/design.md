# Design

## Context

整理文档有两种来源：未配置模型或离线时由 `LocalSessionDocumentComposer` 生成，配置模型时由 `SessionDocumentService` 组装转录并调用 `SessionDocumentPrompt`。当前本地格式为：

```
# 对话名

> 本地整理 · 共 N 条消息 · 2026年11月23日 09:00-10:00

## 2026年11月23日

**09:00**

正文

**09:05 · 助手**

正文
```

时间单独成行并加粗，助手与系统消息再附角色标签。结果页用 `markdownAnnotatedString` 渲染，PDF/PNG 导出复用同一渲染，Markdown 导出保留源码。已保存文档存放在会话级 `sessionDocuments` 中。

## Goals / Non-Goals

- 只调整整理文档的排版与角色标注，保持标题层级为“对话名 → 日期 → 正文”。
- 本地整理与 AI 提示词使用同一结构；不新增后处理或格式转换层。
- 不迁移历史文档，不改变结果页组件、导出实现和同步协议。

## Decisions

### 新的本地排版

每条消息输出为一段：`『${HH:mm}』${正文首行}`，正文其余行原样跟随；去掉独立的时间行与 `roleLabel`。日期小标题仅在日期变化时输出一次，保持现有跨天分组；顶部引用行（本地整理说明）保持不变，它不属于标题层级。

输出示例：

```
# 对话名

> 本地整理 · 共 3 条消息 · 2026年11月23日 09:00-09:20

## 2026年11月23日

『09:00』第一条正文

『09:05』第二条正文
```

`『』` 直接连接正文，不加空格、不加粗；时间使用现有 `HH:mm` 24 小时格式。正文中的粗体、斜体、删除线、颜色 span 与分段格式保持原样。

### 角色标注

整理文档不输出任何角色标签，删除本地整理的 `roleLabel`。发给模型的转录仍保留“助手：”“系统：”前缀（用户消息本就没有前缀），用于模型区分说话方；该前缀只作为输入，不写入文档。提示词明确禁止模型输出角色标签。

### AI 提示词

在系统提示词中补充结构要求：一级标题为对话名、按日期使用 `yyyy年M月d日` 的二级标题、每条消息以 `『HH:mm』` 开头并与正文同段、不输出角色标签；保留现有“按时间顺序、覆盖要点、保留 Markdown 格式”等约束。

### 范围与兼容

- 已保存文档不迁移；重新整理覆盖该对话旧文档。
- 模拟模型 `MockAiService.composeSession` 不属于结果页的正常路径（离线时走本地整理），本次不调整，避免扩大范围。
- AI 输出由模型决定，本变更只保证提示词要求；不做事后重排，避免引入与用户手工编辑冲突的自动改写。

## Risks / Trade-offs

- [模型未严格遵守结构] → 提示词写明结构，并在测试中断言提示词包含编号要求；实际输出质量依赖模型，真机验证时记录。
- [隐藏全部角色后助手内容不再有来源提示] → 属用户选择；正文本身通常能区分对话轮次。
- [正文多段落时只有首行带时间] → 与“时间只在开头出现一次”的阅读预期一致。

## Migration Plan

无需数据迁移。新旧文档并存；导出使用文档当前内容。

## References

- `app/src/main/java/com/chatnotebook/app/data/LocalSessionDocumentComposer.kt`
- `app/src/main/java/com/chatnotebook/app/data/SessionDocumentService.kt`
- `app/src/main/java/com/chatnotebook/app/ai/SessionDocument.kt`
- `openspec/specs/document-markdown-formatting/spec.md`
