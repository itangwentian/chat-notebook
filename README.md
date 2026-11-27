# chat_notebook

项目已接入 OpenSpec，并建立 Android 原生工程。当前可构建的界面支持选择记录目录、多会话聊天、消息编辑/引用/表情/批量多选，以及离线 Mock 与真实 OpenAI Compatible 模型两种整理模式。合并操作位于当前对话的消息多选工具栏。

## Android 工程

采用 Kotlin、Jetpack Compose、Material 3、轻量 ViewModel + Repository、Coroutines/Flow 和 Gradle Kotlin DSL。当前单 `app` 模块，Gradle Wrapper 8.10.2、Android Gradle Plugin 8.8.2、Kotlin 2.1.21；编译 SDK 35，最低 SDK 26。已安装 Android SDK 和 JDK 17 或更高版本后，在项目根目录运行：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

本机需要通过环境变量 `ANDROID_HOME` 指向 SDK，或在未纳管的 `local.properties` 中设置 `sdk.dir`。APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`，可直接安装到 Android 8.0（API 26）及以上设备。

## 本地模拟器测试

已安装 Android Emulator 与 API 35（Android 15）x86_64 系统镜像，并创建 AVD `chat_notebook_api35`（Pixel 6 外形、4 GB 内存、硬件键盘）。`ANDROID_HOME` 及 `emulator`、`platform-tools`、`cmdline-tools` 已加入用户 PATH，重新打开终端后可直接使用以下命令：

```powershell
# 启动模拟器（首次启动较慢，保持窗口打开）
emulator -avd chat_notebook_api35 -gpu host

# 等待设备就绪
adb wait-for-device

# 编译、安装并启动应用
.\gradlew.bat :app:installDebug
adb shell am start -n com.chatnotebook.app/.MainActivity

# 在模拟器上运行设备测试
.\gradlew.bat :app:connectedDebugAndroidTest
```

模拟器包含真实 Android Keystore 与 SAF 文档选择器，可用于验证凭据加解密、目录授权、旋转/小屏布局与卸载重装恢复。真实模型调用需要模拟器可访问外网并在设置页配置 HTTPS Base URL。若模拟器提示硬件加速不可用，请在 Windows“启用或关闭 Windows 功能”中确认已启用“虚拟机平台”和“Windows 虚拟机监控程序平台”，或改用 `-accel off` 做纯软件验证。

记录将长期保存在用户选定的本地文档目录，卸载后文件仍在；重装后重新选择目录恢复。当前数据层按对话拆分 UTF-8 JSON：每个对话一个 `session-<sessionId>-<代数>.json`，全局模型配置保存在 `settings-<代数>.json`；文件包含 schema 版本、代数和 SHA-256 内容校验。保存时只重写发生变化的对话，先写新代数并核验成功再删除旧代数；恢复时按对话选择最新有效文件，损坏可回退旧代数。首次打开旧版 `records-*.json` 目录会自动拆分迁移，全部成功后删除旧快照。Room 仅作可重建的应用内查询缓存，写入顺序为“新文件写入并核验 → 更新 Room”。文件中不得包含 API Key。Room schema 当前为 v7，提供显式迁移，覆盖整理请求标识、编辑历史、引用、表情、模型配置、合并关系与整理文档；禁止破坏式回退。

AI 整理可在设置中切换显式 Mock 与真实模型。Mock 模式离线、可重复，结果页标注“整理结果（模拟）”；真实模式使用用户自建的 OpenAI Compatible 配置（Base URL、模型、超时），请求失败不会自动切换为模拟。发送消息只保存原文；长按左侧历史对话并选择“整理”才会启动该对话的后台整理。完成后列表显示未读红点，点击红点查看独立结果页并清除标记；之后仍可从长按菜单重看。结果、失败状态和重试入口只在结果页显示。结果绑定消息内容版本与请求标识，乱序或过期响应不会成为有效结果。进程中断遗留的“整理中”在下次恢复时标记为失败并允许手动重试，不自动重发。具体契约见 `openspec/changes/add-ai-chat-notes-mvp/`。

在对话中长按一条消息，选择“多选”，再点选其他消息；顶部操作栏可复制、删除或合并。至少选中两条时可点“合并”，确认预览后，所选气泡会由一条新消息替换。合并使用消息当前内容，原消息及编辑历史仍保存在记录文件中，可追溯来源；合并不会触发 AI 整理。

### 消息附件

图片和语音附件保存在记录目录的 `attachments/` 下，转发消息可复用同一附件文件。删除整组对话并成功保存后，应用仅清理该对话引用且其他未删除对话均未引用的附件。单条消息软删除后，记录中保留的附件引用仍然有效。若部分附件清理失败，应用会提示失败数量，对话仍保持已删除。

应用在附件目录中放置 Android 的 `.nomedia` 标记，避免附件副本进入系统相册和媒体库。打开已有记录目录时会自动补齐标记，历史副本会随系统媒体索引刷新退出相册。

长按消息进入多选后，点“转发”可在同一张底部面板预览内容，选择其他对话或直接打开可接收的外部应用。微信、QQ 等可选目标取决于设备安装的应用和它们支持的分享格式。选择外部应用后，返回聊天笔记时原来的多选仍在。

### 局域网同步

在两台安卓设备上选择记录目录并接入同一局域网。打开“设置 → 局域网同步”，一台显示二维码，另一台扫码，成功后两端直接列为“自己的设备”。设备信任无到期时间，应用重启或切换记录目录后仍可使用。两台设备都打开应用时会自动交换新增和变更的对话、删除标记以及缺失的附件。初次同步保留双方已有笔记，并发编辑保留主版和冲突副本。设置里可暂停、继续、移除设备并查看最近成功同步时间。访客 Wi-Fi 或路由器的设备隔离可能阻止设备互通。

## 模型配置与凭据

设置页支持新增、编辑、删除多个模型配置并指定默认配置。API Key 只以密文形式保存：使用 Android Keystore 的 AES/GCM 加密后写入 SharedPreferences，Room 与 JSON 快照均不保存明文，界面只显示脱敏状态。删除配置会同时清理对应密文；删除失败可重试。Keystore 加解密只能在真实设备或模拟器上验证，单元测试使用内存实现替代。Base URL 需为 HTTPS，请求路径约定为 `{baseUrl}/chat/completions`。连接测试会发起一次不带业务数据的请求，可能产生少量费用。

## 版本管理与开源仓库

- 开源仓库：<https://github.com/itangwentian/chat-notebook>，MIT 许可证；远端使用 SSH：`git@github.com:itangwentian/chat-notebook.git`。
- 当前目录是 git 工作副本，主分支为 `main`，所有改动先在本地提交再推送。
- 项目早期使用本地 SVN，仓库 `D:\CODES\.svn-repositories\chat_notebook` 只作历史归档，不再更新；`.svn` 已加入 `.gitignore`。

```powershell
git status
git diff
# 新文件直接包含在 add 中
git add -A
git commit -m "说明本次变更"
git push
git log --oneline -5
```

忽略规则见根目录 `.gitignore`：构建产物、签名文件与口令、`local.properties`、日志和 IDE 配置都不入库。`.agents` 和 `openspec` 纳入版本管理。

若网络访问 `github.com` 受限，`git push` 可能被拦截，需在可访问外网的环境执行。

## OpenSpec 工作流

需要 Node.js 22.12.0 或更高版本。以下命令使用固定的 OpenSpec 2.0.0，无需全局安装。

```powershell
# 查看进行中的变更
npx --yes @fission-ai/openspec@2.0.0 list

# 校验所有需求与变更（添加内容后运行）
npx --yes @fission-ai/openspec@2.0.0 validate --all --strict --no-interactive
```

在 Codex CLI 或 IDE 中使用以下技能；桌面端也可从 Skills 中选择。新生成的技能如未显示，重新打开项目会话。

1. `$openspec-explore`：探索需求与方案。
2. `$openspec-propose`：描述需求，生成提案、规范、设计与任务。
3. `$openspec-apply-change`：按任务实现并验证。
4. `$openspec-update-change`：需求变化时更新变更文档。
5. `$openspec-sync-specs`：需要时将变更规范同步到主规范。
6. `$openspec-archive-change`：完成后归档变更。

目录说明：

- `openspec/config.yaml`：项目上下文、文档语言与规则。
- `openspec/specs/`：当前需求规范。
- `openspec/changes/`：进行中的变更及归档。
- `.agents/skills/`：官方生成的 Codex 工作流。

当前进行中的变更见 `openspec/changes/`；新需求从创建提案开始，并随项目建立更新配置中的上下文。

参考：[OpenSpec 官方文档](https://github.com/Fission-AI/OpenSpec)。
