# Proposal

## Why

整理结果由消息正文直接拼接生成，丢失了气泡已有的粗体、斜体、删除线和颜色格式；用户发言还会统一带上“用户”标签。导出的 Markdown、PDF、PNG 因此既缺少原有排版，又把 Markdown 标记当作普通文字绘制，观感较差。

## What Changes

- 生成整理文档时 SHALL 读取每条消息的气泡格式（消息级格式或合并消息的分段格式），并转换为 Markdown：粗体、斜体、删除线使用标准标记，颜色使用内联 `<span style="color:…">`。
- 用户发言 MUST NOT 显示“用户”角色标签；助手与系统仍保留“助手”“系统”标签。
- 整理结果预览 SHALL 按 Markdown 渲染标题、引用、列表、粗体、斜体、删除线与颜色，而不是显示原始标记。
- 导出的 PDF 与 PNG SHALL 渲染 Markdown 样式；导出的 Markdown 文件保持 Markdown 源码，供支持 Markdown 的编辑器渲染。
- 会话整理提示词 SHALL 要求保留输入中的 Markdown 格式，并禁止给用户发言添加“用户”标签。

## Capabilities

### New Capabilities

- `document-markdown-formatting`：整理文档保留气泡格式、以 Markdown 渲染预览与 PDF/PNG、隐藏“用户”标签。

## Impact

- 代码：`BubbleFormat`（颜色值）、新增消息 Markdown 格式化、`SessionDocumentService`/`LocalSessionDocumentComposer`、`ChatRepository`（传入气泡格式）、`ChatScreen`（预览渲染）、`DocumentExport`（Markdown 排版）、新增 Markdown 解析/渲染工具。
- 数据：不改变记录文件与 Room 结构；重新整理后生成的新文档包含 Markdown 格式。
- 测试：更新整理文档断言，新增气泡格式、字体样式、颜色与“用户”标签用例。
- 验证：`testDebugUnitTest`、`lintDebug`、`assembleDebug`；模拟器查看预览与导出效果。
