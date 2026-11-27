# 项目协作约定

本项目使用 OpenSpec 管理需求和变更，配置位于 `openspec/config.yaml`。

版本管理使用本地 SVN。当前目录是 trunk 工作副本，使用 `svn status`、`svn diff`、`svn update` 和 `svn commit` 管理变更。不要执行 Git 初始化或使用 Git 提交。仓库位置与维护命令见 README.md。

- 新功能、架构调整和行为变更先形成 proposal、specs、design 和 tasks，再按任务实施。
- 小型修复和文档修正按实际需要直接处理，避免创建无意义的变更。
- 用户主动打开文本编辑或新增表单后，界面就绪时必须自动聚焦目标输入框并请求显示软键盘，确保无需再点击输入框即可键入。多字段表单优先聚焦本次操作的首要字段。焦点请求只在进入编辑时执行，避免输入或重组时抢焦点。验证时从实际入口触发，在模拟器或设备上确认键盘可见且能直接输入。
- 当前规范放在 `openspec/specs/`，进行中的变更放在 `openspec/changes/`，完成后归档到 `openspec/changes/archive/`。
- 需求和说明使用简体中文，保留 OpenSpec 结构标题以及 SHALL/MUST 等规范关键字。
- 技术栈已确定为 Kotlin 原生 Android、Compose、Material 3、轻量 ViewModel + Repository、Coroutines/Flow；计划引入 Room、Retrofit/OkHttp、Kotlin Serialization。当前骨架可用 `gradlew.bat assembleDebug`、`testDebugUnitTest` 和 `lintDebug` 验证。区分已实现的占位界面与 OpenSpec 中尚未实施的持久化能力。
- Codex 工作流位于 `.agents/skills/openspec-*/SKILL.md`；执行相应工作流前阅读其说明。
- 完成变更后运行相关检查及 OpenSpec 校验，并如实记录尚未完成的验证。
