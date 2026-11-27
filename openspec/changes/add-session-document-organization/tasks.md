# Tasks

依据 proposal.md、design.md 与 `specs/ai-organization/spec.md`、`specs/session-document-export/spec.md` 按依赖顺序实施。Goal、Requirements、Constraints、Acceptance Criteria 分别表示目标、需求、约束和验收。以下任务均已完成，验证结果见文末实施记录。

## 1. 数据层与兼容

- [x] 1.1 Goal：建立会话文档模型；Requirements：`RecordPayload` 新增 `sessionDocuments`，`SessionDocumentRecord` 含 `sessionId`、`content`、`messageCount`、`generatedAt`、`modelSnapshot`、`isMock`，空列表使用 `@EncodeDefault(NEVER)`；`SnapshotCodec` 校验会话存在、`sessionId` 唯一、正文非空；Constraints：旧快照校验和不变，既有逐条 `aiResults` 保持可读；Acceptance Criteria：快照测试覆盖空文档不编码、含文档往返、非法记录被拒绝。
- [x] 1.2 Goal：Room 索引与迁移；Requirements：新增 `session_documents` 表、实体互转与按会话观察 Flow，数据库升至 v7 并新增 `MIGRATION_6_7`，`rebuildCache` 重建该表；Constraints：显式迁移，禁止破坏式重建；Acceptance Criteria：迁移测试覆盖 v6 → v7 后既有消息保留且文档为空。
- [x] 1.3 Goal：删除与覆盖语义；Requirements：重新整理覆盖同会话旧文档，删除对话移除文档索引；Constraints：不改变消息软删除与逐条整理结果规则；Acceptance Criteria：仓库测试覆盖覆盖写入与删除后文档不再返回。

## 2. AI 文档生成

- [x] 2.1 Goal：整段对话转录；Requirements：按 `createdAt`、`id` 升序拼接全部未删除用户与助手消息，带本地时间戳与角色；Constraints：不包含已删除消息，不读取旧逐条摘要作为输入；Acceptance Criteria：单元测试覆盖顺序、角色标签、删除消息过滤。
- [x] 2.2 Goal：模型接口；Requirements：`AiService` 增加整会话文档生成方法，`OpenAiCompatibleClient` 支持非 JSON 文本请求，提示词要求 Markdown、时间顺序、无遗漏、不编造、不执行对话指令；Constraints：不破坏现有逐条 `organize` 接口与 Mock 行为；Acceptance Criteria：Mock 返回确定性中文文档，远端请求体与提示词测试通过。
- [x] 2.3 Goal：输出校验；Requirements：正文非空且不超过 200,000 字符，失败返回中文错误；Constraints：不保存无效输出，不覆盖既有文档；Acceptance Criteria：空白与超长输出被拒绝。
- [x] 2.4 Goal：本地整理兜底；Requirements：无默认模型或离线模式时用 `LocalSessionDocumentComposer` 按时间顺序汇总、按日期分组，空白归一后重复内容只保留最新一条；文档标注“本地整理”，条数按去重后计算；Constraints：不发起网络请求，不伪造 AI 输出；Acceptance Criteria：单元测试覆盖去重保留最新、时间顺序、日期分组与元数据。

## 3. 整理流程与状态

- [x] 3.1 Goal：整会话整理；Requirements：`organizeSession` 只调用一次模型，成功后写入文档并递增 `completedVersion`、发布未读红点；无消息、无目录、无模型或请求失败写入中文错误；Constraints：重复请求不并发，网络请求不持锁；Acceptance Criteria：仓库测试覆盖成功、无消息、失败与重复请求。
- [x] 3.2 Goal：状态恢复；Requirements：整理中与未读状态在重启后保持一致；Constraints：复用既有 `SessionOrganizationState`；Acceptance Criteria：快照测试覆盖文档与未读状态往返。
- [x] 3.3 Goal：模型可用性分流；Requirements：`organizeSession` 在离线模式或没有默认模型时走本地整理；默认模型存在但凭据缺失时仍返回中文错误；Constraints：网络请求不持锁，失败不写入文档；Acceptance Criteria：代码路径与离线模式模拟器验证通过。

## 4. 结果页与导出

- [x] 4.1 Goal：结果页展示文档；Requirements：`ChatUiState` 提供 `resultDocument`，页面展示正文、生成时间、消息条数、模型与模拟标识；整理中显示“正在整理…”，失败显示错误与“重新整理”；Constraints：不展示逐条摘要卡片，不关闭侧栏；Acceptance Criteria：Compose 测试覆盖有文档、整理中、失败与空状态。
- [x] 4.2 Goal：Markdown 导出；Requirements：通过系统创建文档界面保存 UTF-8 `.md`，默认文件名含对话标题与时间；Constraints：取消不产生文件；Acceptance Criteria：单元测试校验写入内容与文件名过滤。
- [x] 4.3 Goal：PDF 导出；Requirements：使用 `PdfDocument` 自动换行与分页，覆盖全部正文；Constraints：不引入第三方库；Acceptance Criteria：多页文档测试通过，模拟器打开 PDF 可读。
- [x] 4.4 Goal：PNG 导出；Requirements：绘制完整正文，超限时提示改用 PDF/Markdown；Constraints：限制宽度与高度，避免内存溢出；Acceptance Criteria：测试覆盖正常导出与超限失败提示。
- [x] 4.5 Goal：导出反馈；Requirements：成功与失败均显示中文提示；Constraints：导出失败不影响已保存文档；Acceptance Criteria：模拟器验证三种格式与取消路径。

## 5. 验证与记录

- [x] 5.1 Goal：自动化验证；Requirements：运行 `gradlew.bat testDebugUnitTest lintDebug assembleDebug`；Constraints：lint 不新增错误；Acceptance Criteria：保留构建与测试摘要。
- [x] 5.2 Goal：模拟器端到端；Requirements：验证整段整理、结果页、未读红点、重新整理与 MD/PDF/PNG 导出；Constraints：导出需系统文件选择器可用，无法执行项如实记录；Acceptance Criteria：逐项记录通过/失败。
- [x] 5.3 Goal：交付；Requirements：构建 debug APK 并记录 SHA-256；Constraints：不修改与本变更无关的功能；Acceptance Criteria：APK 可安装且核心流程可用。

## 依赖与边界

- 1.x 必须先于 2.x、3.x、4.x；2.x 与 1.x 可并行推进，4.2–4.4 依赖 4.1。
- 首版不做超长对话分段摘要、Word/HTML 导出与导出文件回写记录目录。

## 实施记录

- 构建与测试：`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过；新增 `SessionDocumentServiceTest`、`DocumentExportTest`，更新快照、迁移与 Compose 测试。
- 数据库：Room v7 与 `MIGRATION_6_7` 已生成并通过 v1 → v7 迁移测试。
- 模拟器（Pixel 6 API 35）：验证侧栏“整理”不关闭抽屉、整篇文档结果页、生成时间/消息条数/模拟标识、重新整理、未读红点清除；导出 Markdown/PDF/PNG 均生成非空文件（默认文件名 `talk新对话55555-20261114-0531.*`）。
- 本地兜底：离线模式下结果页显示“本地整理”，文档按日期分组、按时间顺序排版；去重保留最新一条由 `SessionDocumentServiceTest` 覆盖。
- 未验证：真实远端模型调用未执行（无可用 API Key）；默认模型存在但凭据缺失的报错路径仅由代码覆盖。
- APK：`chat_notebook-debug.apk`，SHA-256 `E96D139AB5A1BF68370503EBFF5F4095FA0C0701C90C042B8AC49A61F7DBD22E`。
