# Tasks

## 1. 气泡格式

- [x] 1.1 Goal：格式到 Markdown；Requirements：粗体/斜体/删除线/颜色映射，合并消息分段格式优先；Constraints：不改变气泡格式存储；Acceptance Criteria：单元测试覆盖四种格式与分段。
- [x] 1.2 Goal：整理流程传入格式；Requirements：从 `reactions` 构建消息格式映射并传给本地与 AI 整理；Constraints：只读取当前记录的格式；Acceptance Criteria：重新整理后文档包含格式。

## 2. 角色标签

- [x] 2.1 Goal：隐藏“用户”；Requirements：本地文档与 transcript 不输出“用户”，助手/系统保留；AI 提示词同步约束；Constraints：时间信息保留；Acceptance Criteria：测试断言无“用户”。

## 3. Markdown 渲染

- [x] 3.1 Goal：解析与预览；Requirements：标题、引用、列表与行内样式解析为 Compose `AnnotatedString`；Constraints：无法识别内容按原文显示；Acceptance Criteria：结果页不显示 Markdown 标记。
- [x] 3.2 Goal：PDF/PNG 排版；Requirements：使用 `Spanned` + `StaticLayout` 绘制，分页与超长 PNG 缩放；Constraints：保持现有文件格式与尺寸限制；Acceptance Criteria：导出文件样式正确、无标记。

## 4. 验证与交付

- [x] 4.1 Goal：测试与构建；Requirements：更新并新增单元测试，运行 `testDebugUnitTest lintDebug assembleDebug`；Constraints：lint 不新增错误；Acceptance Criteria：全部通过。
- [x] 4.2 Goal：模拟器验证；Requirements：本地整理后查看预览、导出 MD/PDF/PNG；Constraints：使用现有记录目录；Acceptance Criteria：逐项记录。
- [x] 4.3 Goal：APK；Requirements：复制 debug APK 并记录 SHA-256；Constraints：不修改无关功能；Acceptance Criteria：APK 可安装。

## 实施记录

- 构建与测试：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过；新增 `MarkdownRenderingTest`，更新整理文档测试覆盖粗体、斜体、删除线、颜色、分段格式与“用户”标签。
- 模拟器验证（Pixel 6 API 35）：
  - 将 `John` 设为粗体、`HDD` 保持绿色后重新整理，预览中 `John` 加粗、`HDD` 绿色，多行正文按段落紧凑排版。
  - 预览不显示 `#`、`**`、`~~` 等标记，也不显示“用户”。
  - 导出 MD 内容含 `**John**` 与 `<span style="color:#2E7D32">HDD</span>`，可直接由 Markdown 编辑器渲染。
  - 导出 PNG 中标题放大、引用为灰色斜体、`John` 加粗、`HDD` 绿色，无 Markdown 标记。
  - 导出 PDF 生成成功（约 74 KB），首页与次页标题、加粗、绿色文字均按样式绘制；按行分页，第二页从完整行开始，无重复或截断行。
- 说明：颜色使用内联 `<span style="color:…">`，取决于 Markdown 查看器是否允许内联样式；PDF/PNG 由内置渲染器保证颜色生效。历史文档需重新整理后才会包含气泡格式。
- APK：`chat_notebook-debug.apk`，SHA-256 `99D69C31ACABE6610DD0EB29D385410410C6B6A67603DD90C57D9C451283EAA1`。
