# Tasks

依据根目录需求基线及本变更 specs/design，按 Phase 顺序逐项实施；每项完成即验证，不把所有阶段合为一次大任务。下列均为未实施任务。Goal、Requirements、Constraints、Acceptance Criteria 分别表示目标、需求、约束和验收。

## 1. Phase 0：Android 工程与规范

- [x] 1.1 Goal：建立可复现的原生工程；Requirements：创建单 app 模块、Gradle Kotlin DSL、Wrapper、Kotlin/Compose/Material 3 依赖；Constraints：检查本机 JDK/Android SDK 并核对官方兼容表后固定版本，保留全部现有文件，不使用 Git；Acceptance Criteria：`./gradlew.bat assembleDebug` 成功，缺失环境时记录具体阻塞而不伪报通过。
- [x] 1.2 Goal：建立轻量应用骨架；Requirements：手动依赖注入、ViewModel/Repository、Coroutines/Flow、中文主题与导航；Constraints：单模块，不增加重型框架；Acceptance Criteria：启动应用直接显示聊天占位页且输入框未聚焦，可从左缘展开功能栏并进入设置，单元测试及 lint 通过。
- [x] 1.3 Goal：记录实际工程规则；Requirements：更新 README、AGENTS、OpenSpec context 的已确定技术栈与构建测试命令，添加 SVN 的 `.gradle`、`local.properties`、签名与产物忽略规则；Constraints：不提交密钥或机器路径配置；Acceptance Criteria：按文档可构建，`svn status --no-ignore` 核对本地文件未纳管，保留 Gradle Wrapper 和 Room schema 可跟踪。

## 2. Phase 1：持久化与聊天基础体验

- [x] 2.1 Goal：建立可靠消息数据层；Requirements：用户选定目录的版本化 JSON 快照、完整性校验与恢复，Room Session/Message/AIResult 可重建缓存、DAO、Repository、稳定时间排序和版本字段；Constraints：原文不可变，文件不含密钥，导出 Room schema，禁止破坏式迁移；Acceptance Criteria：测试验证双 Session 隔离、重启恢复、损坏快照回退、权限丢失时不伪报保存、外键及同时间排序，README 记录数据策略。
- [x] 2.2 Goal：多 Session 日常记录；Requirements：侧边栏新建对话按钮、Session 创建命名、列表切换、长按历史项重命名及二次确认软删除、文本发送和空白校验；Constraints：先写入长期记录文件再反馈成功，不依赖网络；Acceptance Criteria：ViewModel 测试覆盖按钮创建独立空白对话、标题重命名、删除取消与确认、空白输入与重复状态更新，设备重启应用后可恢复两个独立 Session。
- [x] 2.3 Goal：内容优先的聊天 UI；Requirements：启动直达聊天、初始不弹键盘、消息上下滚动、左缘右滑展开及关闭侧边栏、齿轮设置入口、历史标题列表、中文资源、角色对齐、日期分组、按需时间、Insets/IME、稳定列表 key 和禁用语音图片入口；Constraints：不实现语音图片业务，用户上翻不被后台更新强制拉底；Acceptance Criteria：Compose 测试验证首页默认输入状态、侧边栏手势与入口、时间切换与入口禁用，手机小屏及横屏键盘手工检查无输入框遮挡。
- [x] 2.4 Goal：提供可测试的 AI 整理契约；Requirements：AIService、结果 schema、中文提示约束与可重复 Mock、关联结果卡片；Constraints：模拟标识明确，原文与结果独立；Acceptance Criteria：单元测试验证有效结果、空数组、无效 JSON/字段拒绝，离线 Mock 端到端可查看原文及派生结果，记录 Mock 使用方法。
- [x] 2.5 Goal：保证处理状态可恢复；Requirements：先保存再请求、请求标识、版本守卫、失败重试、进程中止恢复；Constraints：不自动重发付费请求；Acceptance Criteria：测试覆盖请求失败、乱序完成、进程恢复及重复重试，失败不丢消息；运行本阶段构建和测试，验证卸载重装后重新选择目录可恢复两个对话，并记录结果。
- [x] 2.6 Goal：交付 Grok 风格输入栏并随软键盘贴合；Requirements：两层圆角面板上方为文字输入，下方左侧紧凑禁用附件图标、右侧在语音占位与发送按钮间切换；点击输入框后输入栏紧贴键盘上沿，收起后回到底部，消息列表随可用高度变化并保持可滚动；Constraints：启动时不自动聚焦，不让图片和语音占位挤压输入行，避免重复消费窗口、Scaffold 与 IME Insets 造成空白带或遮挡；Acceptance Criteria：Compose 测试覆盖未聚焦、占位禁用、输入后发送按钮切换；在设备或模拟器上检查竖屏、横屏、小屏及手势/三键导航，验证键盘弹起与收起时布局、输入栏可见性和消息滚动；运行相关 UI 测试、lint 与 debug 构建，未执行的设备检查如实记录。
- [x] 2.7 Goal：将自动逐条整理改为会话手动整理；Requirements：发送消息只保存原文，长按左侧对话列表提供“整理”和“查看整理结果”，选择“整理”立即启动该对话的后台处理并短暂提示“后台整理中”，结果和失败重试集中在独立整理页，完成后在该对话列表项显示可点击的未读红点，查看后清除且重启后状态一致；Constraints：聊天页不显示整理卡片或常驻状态，同一对话不得并发重复请求，进程中断不自动重发付费请求，原文与结果分离并保留版本校验；Acceptance Criteria：ViewModel/Repository/UI 测试覆盖发送不触发模型、选定对话后立即整理、切换对话不中断、重复点击不重复调用、短暂提示、完成红点、点击查看并清除、长按重看、失败重试与重启恢复；通过相关构建测试，并在有设备时核对实际交互。

## 3. Phase 2：消息交互与一致性

- [x] 3.1 Goal：角色正确的长按菜单；Requirements：复制、编辑、删除、引用、表情、多选入口及编辑取消；Constraints：AI 消息无编辑操作；Acceptance Criteria：Compose 测试验证两类菜单权限与剪贴板内容，操作文档说明权限。
- [x] 3.2 Goal：可追溯编辑；Requirements：EditHistory、有效内容版本、原文/历史查看、旧 AI 结果过期反馈；Constraints：编辑与失效在同一记录快照事务，按实际 schema 版本添加迁移；Acceptance Criteria：Room/Repository 测试验证多次编辑历史及取消不写入、旧响应迟到不生效，迁移测试保留原数据。
- [x] 3.3 Goal：安全删除与稳定引用；Requirements：软删除确认、引用预览与关系、删除占位；Constraints：删除不级联丢失引用、排除后续 AI 输入；Acceptance Criteria：测试覆盖删除取消/确认、编辑引用源、删除引用源与处理中删除，引用不为空且旧结果不有效。
- [x] 3.4 Goal：Emoji 标记；Requirements：选择器、表情/计数显示、添加切换取消和持久化；Constraints：单用户同表情不重复计数；Acceptance Criteria：DAO 与 UI 测试验证重复点击、不同表情切换及重启恢复，包含必要 schema 迁移。
- [x] 3.5 Goal：批量管理消息；Requirements：多选态和数量、按顺序复制、确认后原子批量软删除；Constraints：不实现整理为笔记，多选点击不触发时间切换；Acceptance Criteria：测试覆盖取消、确认、数量、复制顺序及事务失败无部分删除，更新操作说明并通过本阶段构建测试。

## 4. Phase 3：模型配置与真实文本调用

- [x] 4.1 Goal：配置多模型；Requirements：配置实体/DAO、字段校验、中文设置页、新增编辑删除启用及默认引用；Constraints：Room 不保存明文凭据，协议类型与 Provider 类型分离；Acceptance Criteria：Repository/UI 测试覆盖两个配置恢复、默认切换与默认禁用后清空，schema 升级测试通过。
- [x] 4.2 Goal：保护自备密钥；Requirements：Keystore AES-GCM CredentialStore、脱敏 UI、凭据引用生命周期、删除失败可重试和孤立项清理、备份排除；Constraints：日志/崩溃信息/配置无明文密钥；Acceptance Criteria：设备测试验证加解密、删除、失效提示与失败补偿，使用测试密钥检查持久文件和日志无明文，文档说明 BYOK 边界。
- [x] 4.3 Goal：统一文本协议；Requirements：LLMProvider、OpenAI Compatible Retrofit/OkHttp 适配器、可配置 URL/model/timeout、能力声明；Constraints：HTTPS，无业务层供应商条件分支、无自动收费重试、保留 Mock 注入；Acceptance Criteria：协议 fixture/MockWebServer 测试覆盖 URL 拼接、认证、成功响应、超时、限流、协议错误、不支持能力和凭据脱敏，不依赖真实付费请求。
- [x] 4.4 Goal：连接测试与切换；Requirements：中文连接状态、默认模型快照及真实 AIService 接入；Constraints：无默认模型仍可保存消息，失败不转模拟成功；Acceptance Criteria：测试覆盖 A 请求中切换 B、默认删除、未配置模型、失败重试和 schema 无效响应；在用户提供配置后可选执行真实烟测，未执行必须注明。
- [x] 4.5 Goal：完成设置交付；Requirements：记录 Base URL 语义、供应商类型/协议支持边界、凭据重新输入及错误说明；Constraints：示例不用真实密钥或永久固定模型名；Acceptance Criteria：按文档新增自定义配置，运行本阶段单元测试、设备凭据测试、lint 和 debug 构建，记录结果。

## 5. Phase 4：当前对话内的消息合并

- [x] 5.1 Goal：建立可追溯消息合并数据；Requirements：同一 Session 的至少两条未删除消息按时间和 ID 稳定排序，创建一条合并消息，保留被替换消息和编辑历史并记录来源 ID；Constraints：原消息软删除，AI 结果过期，引用显示删除占位，混合角色注明来源，旧 SessionMergeRecord 保持可读取；Acceptance Criteria：Repository 测试覆盖排序、原文与当前内容、角色标记、引用、来源与重启恢复。
- [x] 5.2 Goal：确保原子替换；Requirements：合并消息、来源关系、原消息软删除及 AI 结果失效在同一文件快照更新中提交；Constraints：不自动调用模型，不留下半成品；Acceptance Criteria：注入写入错误证明全部回滚，测试重复点击与编辑并发后的版本一致性。
- [x] 5.3 Goal：交付消息多选合并入口；Requirements：长按气泡进入多选，操作栏同时提供复制、删除、合并；至少两条才可合并，确认页显示数量和内容预览；左侧对话列表移除跨 Session 合并入口；Constraints：取消零写入，合并后普通聊天只显示新消息，原消息在文件中可追溯；Acceptance Criteria：Compose/设备测试验证入口、禁用态、取消/确认、显示位置、重启恢复，并更新使用说明与构建测试。

## 6. 跨阶段集成验收

- [ ] 6.1 Goal：验证完整记录链路；Requirements：创建两 Session、编辑/引用/气泡格式、同一对话多选复制/删除/合并、手动会话整理与红点查看、模型切换、重启及卸载重装恢复；Constraints：原文与派生结果始终分离，聊天页不承载整理结果，不测试基线排除功能；Acceptance Criteria：端到端检查清单对应五份 specs 场景，记录设备/系统/测试模式及失败修复结果。
- [x] 6.2 Goal：确认交付可复现；Requirements：运行 `./gradlew.bat testDebugUnitTest lintDebug assembleDebug`、设备可用时运行 `./gradlew.bat connectedDebugAndroidTest`、OpenSpec 严格校验；Constraints：缺环境的设备测试单列未完成，不记成功；Acceptance Criteria：保留构建/测试摘要和剩余限制，SVN diff 无意外文件、凭据或不相关改动。

## Workflow follow-up

- Phase 0–1 已实施：工程骨架、版本化快照数据层、多会话聊天、目录选择、Mock 整理与处理状态。
- Phase 2–4 已实施：消息编辑/历史/引用/气泡格式/批量多选、模型配置与 Keystore 凭据、OpenAI Compatible 真实适配器。旧版跨 Session 合并实现保留仅供历史数据兼容，当前对话内的消息合并已实施。
- 自动化验证（最近一次）：`testDebugUnitTest` 68 项通过；`lintDebug` 0 errors / 11 warnings；`assembleDebug` 成功，产物 `app/build/outputs/apk/debug/app-debug.apk`；`openspec validate add-ai-chat-notes-mvp --strict` 早前通过，本次环境未提供 `openspec` CLI，未重跑校验。
- 未执行的设备项：真机 SAF 卸载重装恢复、Keystore 设备加解密、小屏/横屏输入栏与键盘贴合、真实付费 API 烟测、`connectedDebugAndroidTest`；无设备时不计为通过。
- 模拟器环境：已安装 Android Emulator 与 API 35 x86_64 镜像，创建 AVD `chat_notebook_api35`；在模拟器上完成目录授权、发送消息、Mock 整理卡片与快照恢复冒烟验证，并补齐清单中的 `INTERNET` 权限。
- 2.6 已实施：X 中 Grok 风格两层输入面板；Pixel 6 API 35 模拟器检查 1080×2400 竖屏、720×1280 小屏、1280×720 横屏及三键/手势导航，键盘打开时输入栏可见并贴近键盘、消息列表仍可滚动；`ChatScreenTest` 8 项通过，`lintDebug` 与 `assembleDebug` 成功。真实设备尚未验证。
- 2.7 手动会话整理与独立结果页已实现并通过现有测试；模拟器长按对话列表可见“整理/查看整理结果”，整理完成后出现未读红点，查看结果页展示原文与模拟结果且红点清除，强制停止重启后仍未读状态一致。
- 自动滚动修复：发送前记录会话与消息数，待该条消息落库后的消息流更新时递增一次性 `scrollSignal`（避免信号先于列表更新的竞态）；切换会话同样递增。`ChatScreen` 据此滚动到最新气泡，保留“用户上翻不被后台更新拉底”的规则。模拟器上先上翻列表、在键盘展开时连续发送 `race-test-1`、`race-test-2`，新消息均进入可视区；`ChatViewModelTest` 覆盖发送成功递增、空白输入不递增、发送失败不递增与切换会话递增四项测试。
- 5.1–5.3 已实施：同一对话内长按气泡进入多选，顶部提供复制/删除/合并；确认后新消息替换所选气泡，来源软删除并保留历史，单快照事务写入。Robolectric/Compose 测试覆盖禁用态、确认取消、编辑后合并、重复操作、写入失败回滚与快照恢复；Pixel 6 API 35 模拟器重启后仍显示合并气泡。
- 合并修复：预览与结果统一使用 `buildMergedContent`，按单换行拼接并折叠消息内的空行，预览只渲染这一条合并文本；合并完成后滚动到新消息位置，避免结果落在可视区外。模拟器验证预览与结果均为单换行，`MessageInteractionTest` 与 `ChatViewModelTest` 覆盖空行折叠与合并后滚动信号。
- 气泡格式：长按菜单改为飞书式底部面板——顶部横排“Aa”格式范本，字体效果（粗、斜、删除线）互斥，颜色（红、黄、绿）互斥，两组可叠加为“红色粗体”等组合；选中项高亮，“无”清除全部格式；中部放引用/复制/编辑/多选卡片，底部为历史/删除列表；点选格式后保留面板以便继续调整，操作卡片仍会关闭面板。格式以 `效果+颜色` 组合 id 存入 reactions 记录，无需 schema 迁移；`MessageInteractionTest` 覆盖叠加、互斥与非法组合，`MergeServiceTest` 同步更新。
- 合并保留分段格式：合并消息按原气泡边界保存 `formatSegments`（文本＋格式 id），预览与结果逐段渲染；Room 升级到 v6 并新增迁移，编辑合并消息时清空分段格式。新字段使用 `@EncodeDefault(NEVER)`，空值不写入快照，保证旧快照校验和仍可解析（否则会回退到旧备份）；`SnapshotCodecTest`、`RecordDatabaseMigrationTest` 与 `MessageInteractionTest` 覆盖该行为。模拟器验证将“黄色加大”与默认消息合并后，气泡与预览均分段保留样式。
- 跨阶段 6.1 端到端验收仍待有设备时执行；完成后使用 openspec-archive-change 归档，阶段性完成不能将整项变更标为已完成。




