# Spec Delta

## Purpose

让整理后的会话文档离开应用，保存为用户可长期保存、转发与打印的本地文件。

## ADDED Requirements

### Requirement: 导出格式与保存位置

系统 SHALL 在整理结果页提供 Markdown、PDF、PNG 三种导出格式，并在导出前通过系统“创建文档”界面让用户选择保存位置与文件名。默认文件名 SHALL 由对话标题与生成时间组成，并移除路径分隔符等非法字符。用户取消保存 SHALL 不产生文件、不提示失败。

#### Scenario: 选择保存位置
- **WHEN** 用户点击“导出 PDF”
- **THEN** 系统打开创建文档界面，默认文件名为 `<对话标题>-<时间>.pdf`

### Requirement: 导出内容完整性

Markdown SHALL 以 UTF-8 写入完整文档正文；PDF SHALL 按页面宽度自动换行并分页，覆盖全部正文；PNG SHALL 绘制完整正文，若在可读字号下超出单张图片高度上限，SHALL 明确提示改用 PDF 或 Markdown，不得静默截断。任一格式写入失败 SHALL 显示中文错误，不破坏已保存的文档记录。

#### Scenario: 长文档导出 PDF
- **WHEN** 文档正文超过一页
- **THEN** PDF 自动分页并包含全部正文

#### Scenario: 过长文档导出 PNG
- **WHEN** 文档在最小可读字号下仍超过 PNG 高度上限
- **THEN** 系统提示导出失败并建议改用 PDF 或 Markdown
