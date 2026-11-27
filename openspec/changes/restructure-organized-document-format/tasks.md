# Tasks

## 1. 本地整理排版

- [x] 1.1 调整 `LocalSessionDocumentComposer`：删除单独的时间行与 `roleLabel`，每条消息输出 `『HH:mm』` + 正文首行（其余行原样跟随），保持 `# 对话名`、`## yyyy年M月d日` 日期分组与顶部本地整理说明行。
- [x] 1.2 确认多段正文、合并消息分段格式、去重取最新与跨天分组的既有行为不回归。

## 2. 整理约束

- [x] 2.1 更新 `SessionDocumentPrompt.SYSTEM`：要求一级标题为对话名、日期使用 `## yyyy年M月d日`、每条消息以 `『HH:mm』` 开头并与正文同段、禁止输出角色标签，同时保留时间顺序、要点覆盖与 Markdown 格式要求。

## 3. 测试与兼容

- [x] 3.1 更新 `SessionDocumentServiceTest` 中的本地格式断言，覆盖：同日多条、跨天日期小标题、多行正文仅首行带时间、正文不再出现“助手/系统/用户”。
- [x] 3.2 增加提示词断言：系统提示词包含标题、日期、`『HH:mm』` 与禁止角色标签的结构要求；确认整理输入仍保留“助手/系统”前缀。
- [x] 3.3 确认结果页渲染、Markdown/PDF/PNG 导出直接使用新文档内容，不迁移已保存的旧文档。

## 4. 验证

- [x] 4.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，执行 `npx --yes @fission-ai/openspec@2.0.0 validate restructure-organized-document-format --strict --no-interactive`，记录结果。
- [ ] 4.2 在模拟器整理一段跨天对话，确认结果页显示“对话名 → 日期 → 『时间』正文”且无角色标签；导出 Markdown 检查结构与渲染；无法执行的会话整理项如实记录。

## 实施记录

- 2026-11-23：`LocalSessionDocumentComposer` 改为每条消息输出 `『HH:mm』` + 正文（其余行原样跟随），删除独立时间行与 `roleLabel`；保留 `# 对话名`、`## yyyy年M月d日` 与顶部本地整理说明。
- `SessionDocumentPrompt.SYSTEM` 改为三级结构要求：`# 对话名`、`## yyyy年M月d日`、`『HH:mm』正文`，并明确禁止角色标签；转录仍保留“助手：”“系统：”前缀。
- 测试：更新 `SessionDocumentServiceTest`，新增跨天分组、多行正文仅首行带时间、无角色标签断言与提示词结构断言；`testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过，`openspec validate ... --strict` 通过。
- 未执行：模拟器上的人工排版与导出检查（4.2）；会话整理输出是否符合结构仅在单元测试中验证，未做真机检查。
