# document-markdown-formatting Specification

## Purpose

让整理结果与导出文件保留每条气泡的视觉格式并以 Markdown 呈现，按渲染样式预览与导出 PDF/PNG，同时去掉多余的角色标签并保留可编辑的 Markdown 源码。

## Requirements

### Requirement: 保留气泡格式

生成整理文档时 SHALL 读取消息的气泡格式并写入 Markdown：粗体使用 `**`、斜体使用 `*`、删除线使用 `~~`，颜色使用内联 `<span style="color:#RRGGBB">`。存在分段格式的合并消息 SHALL 逐段保留各自格式；其他消息 SHALL 使用消息级格式。

#### Scenario: 粗体与颜色
- **WHEN** 一条消息被设置为粗体与红色后重新整理
- **THEN** 文档中该条内容为红色且加粗的 Markdown，而不是纯文本

#### Scenario: 合并消息分段格式
- **WHEN** 合并消息的各分段具有不同字体效果
- **THEN** 整理文档按分段分别输出对应的 Markdown 格式

### Requirement: 隐藏用户角色标签

整理文档与整理输入中，用户发言 MUST NOT 显示“用户”标签；助手与系统发言 MAY 保留“助手”“系统”标签。

#### Scenario: 用户发言
- **WHEN** 用户发送消息并生成整理文档
- **THEN** 该条只显示时间与正文，不出现“用户”

#### Scenario: 助手发言
- **WHEN** 对话包含助手回复
- **THEN** 该条保留“助手”标签

### Requirement: Markdown 渲染

整理结果预览 SHALL 按 Markdown 渲染标题、引用、列表、粗体、斜体、删除线与颜色，MUST NOT 直接展示 `#`、`**`、`~~` 等标记。导出的 PDF 与 PNG SHALL 使用同样渲染后的样式排版。导出的 Markdown 文件 SHALL 保留 Markdown 源码。

#### Scenario: 预览
- **WHEN** 用户打开整理结果页
- **THEN** 标题、引用与强调样式按渲染效果显示

#### Scenario: 导出 PDF 或 PNG
- **WHEN** 用户导出 PDF 或 PNG
- **THEN** 文件中的标题、强调、删除线与颜色按样式呈现，不出现 Markdown 标记

#### Scenario: 导出 Markdown
- **WHEN** 用户导出 Markdown
- **THEN** 文件内容保持标准 Markdown 源码，可在 Markdown 编辑器中渲染
