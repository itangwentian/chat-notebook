# Tasks

## 1. 图片定位与共享结构

- [x] 1.1 新增结果文档构造：按本地整理同样的规则重建保留消息，把文档解析为“文字块 + 图片块”；本地整理按 `『HH:mm』`（兼容 `**HH:mm**`）时间点内联，AI 整理在末尾按时间附图，仅取 `image` 附件。
- [x] 1.2 为定位逻辑补充单元测试：单图、同消息多图、删除消息不附图、同分钟多条消息按顺序消费、AI 文档末尾附图、旧格式时间行兼容。

## 2. 结果页展示

- [x] 2.1 结果页按块列表渲染：文字沿用 Markdown 渲染，图片限宽内联、按顺序排列，点击打开原图预览。
- [x] 2.2 沿用附件加载态：解码完成前显示底色不显示“附件不可用”，文件缺失或目录不可用时显示占位；`ChatViewModel` 为结果会话提供消息流。

## 3. 导出

- [x] 3.1 扩展 `DocumentExporter`：Markdown 以 base64 data URI 内嵌图片（缩放压缩、体积过大时提示改用 PDF），PDF 按页宽绘制图片并分页，PNG 保持单图并沿用高度上限提示。
- [x] 3.2 导出测试覆盖含图片的 Markdown 与 PNG，以及无图片时行为不变；PDF 含图导出因 Robolectric 不支持 `PdfDocument` 无法单测，留待设备验证。

## 4. 验证

- [x] 4.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，执行 `npx --yes @fission-ai/openspec@2.0.0 validate show-images-in-organized-document --strict --no-interactive`，记录结果。
- [ ] 4.2 在模拟器用含图片的对话完成本地整理，确认结果页图片位置与点击预览、三种导出包含图片；AI 整理与跨设备场景无法执行时如实记录。

## 实施记录

- 2026-11-23：新增 `data/SessionDocumentBlocks.kt`，按本地整理相同规则重建保留消息，把文档解析为文字块与图片块；本地文档按 `『HH:mm』`（兼容 `**HH:mm**`）顺序绑定同时间消息，模型文档在末尾按时间附图，仅取 `kind == "image"`。
- `LocalSessionDocumentComposer` 抽出 `keepDocumentMessages` 共用去重与排序规则；`ChatUiState` 增加 `resultMessages`，`ChatViewModel` 订阅结果会话消息流。
- 结果页 `OrganizationResultsScreen` 按块渲染：文字沿用 Markdown，图片限宽内联、按路径缓存解码、点击打开原图预览；解码前只显示底色，失败才显示“附件不可用”。
- `DocumentExporter` 支持块列表：Markdown 以 base64 data URI 内嵌（最长边 1280、JPEG 0.8，含透明用 PNG，单图超 4MB 提示改用 PDF）；PDF 按页宽绘制图片并按行分页、图片始终缩放到页面内；PNG 在同图绘制图片，超出高度沿用改用 PDF/Markdown 的提示。
- 测试：新增 `SessionDocumentBlocksTest`（单图、同分钟顺序消费、删除与去重不附图、模型文档末尾附图、旧格式兼容、无匹配）；`DocumentExportTest` 覆盖含图片的 Markdown 与 PNG 及无图片时内容不变；`ChatScreenTest` 新增结果页内联图片展示。
- 未执行/限制：PDF 含图导出因 Robolectric 不支持 `PdfDocument` 无法单测，留待设备验证；模拟器上的图片位置、预览与三格式导出检查（4.2）未执行；AI 整理与跨设备同步场景未验证。
