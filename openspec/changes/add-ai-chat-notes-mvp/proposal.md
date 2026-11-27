# Proposal

## Why

用户需要通过聊天随手保存个人信息，并由 AI 整理为可回看的摘要、关键词和结构化信息，同时可靠保留原文。依据根目录 `ai_chat_notes_codex_requirements.md` 及后续对合并语义的明确修正，建立原生 Android MVP 的分阶段交付计划，让消息交互、多模型与同一对话内的消息合并有明确验收边界。

## What Changes

- Phase 0：从零建立 Kotlin、Compose、Material 3、轻量 MVVM + Repository 的 Android 工程与测试基础。
- Phase 1：实现启动即进入聊天、左侧功能栏、历史对话管理、多 Session、新建对话按钮、持久且卸载后保留的本地记录、Mock AI 整理结果、键盘适配与点击显示时间。
- Phase 2：实现消息菜单、版本化编辑历史、软删除、引用、表情、多选复制与删除。
- Phase 3：实现多 LLM 配置、Keystore 保护凭据、连接测试、默认模型及统一文本请求；保留 Mock。
- Phase 4：实现当前对话内的多选消息合并，以新消息替换选中气泡，并保留原文、来源追溯与原子事务。
- 按小任务逐步验收；不将所有阶段视为一次实现或一次提交。
- 不包含真实语音、图片、自动路由、Agent、工具调用、多模型协作、模型评测、后端服务。搜索、通知提醒和独立待办推进仅作为产品方向，基线未细化其交互，本变更不新增这些功能。

## Capabilities

### New Capabilities

- `chat-sessions`：可新建的多 Session、可恢复的本地记录、文本聊天、时间和基础中文交互。
- `message-interactions`：上下文菜单、版本历史、软删除、引用、表情与批量操作。
- `ai-organization`：保留原文的可校验、版本一致的 AI 派生结果及 Mock。
- `llm-configuration`：多供应商配置、凭据保护、默认模型与统一文本协议。
- `message-merge`：同一对话内的事务性消息合并与来源追溯。

### Modified Capabilities

无。仓库尚无主规范或应用实现。

## Impact

新增 Android 应用工程、用户选定目录中的版本化记录文件、可重建的 Room 查询缓存、Compose UI、网络与凭据层及自动化测试。依赖限于需求指定的 Jetpack、Coroutines/Flow、Retrofit/OkHttp、Kotlin Serialization 与必要测试组件。版本管理继续使用本地 SVN，不初始化 Git。需求文档保留原样；没有已有应用数据库需要迁移，但须建立 schema 导出和未来迁移规则。


