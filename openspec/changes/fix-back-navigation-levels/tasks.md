# Tasks

## 1. 根级返回

- [x] 1.1 新增 `AppBackHandler` 可组合函数：`settingsOpen` 时执行关闭设置，整理结果页打开时执行关闭结果，两者互斥启用；新增 `BackNavigationTest` 并验证测试通过。
- [x] 1.2 在 `MainActivity` 的已同意分支接入 `AppBackHandler`，并将现有状态回调传入；验证 `:app:assembleDebug` 通过。

## 2. 设置页内部层级

- [x] 2.1 在 `SettingsScreen` 注册内层 `BackHandler`：服务配置表单打开时关闭表单，隐私政策/用户协议打开时关闭文档；在 `SettingsScreenTest` 覆盖两种层级并在返回后仍停留在设置列表。
- [x] 2.2 验证内层与根级处理器互斥：表单或文档打开时返回不关闭设置页，两者都关闭后返回才回到对话界面。

## 3. 对话界面返回

- [x] 3.1 确认对话界面不注册 `BackHandler`，会话抽屉、对话框、底部面板与图片预览继续由组件自身处理返回；现有 `ChatScreenTest` 通过。

## 4. 验证

- [x] 4.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，执行 `npx --yes @fission-ai/openspec@2.0.0 validate fix-back-navigation-levels --strict --no-interactive`，记录结果。
- [ ] 4.2 在模拟器依次验证：对话界面返回回桌面；设置列表返回回对话；配置表单与隐私政策返回回设置列表；整理结果页返回回对话；连接外接键盘的返回键与手势返回结果一致；无法执行的项目如实记录。

## 实施记录

- 2026-11-23：新增 `ui/BackNavigation.kt` 的 `AppBackHandler`，在 `MainActivity` 已同意分支注册：设置页打开时关闭设置，整理结果页打开时关闭结果，两回调互斥启用；对话界面不注册，保留默认退出。
- `SettingsScreen` 新增内层 `BackHandler`：服务配置表单或隐私政策/用户协议打开时只关闭当前层，回到设置列表；内层处理器后组合，优先于根级设置返回。
- 额外对齐“其他界面逐级返回”：`PrivacyConsentScreen` 打开隐私政策/用户协议时，返回只关闭文档页，不退出应用；同意页本身无上级，仍保持默认退出。
- 测试：新增 `BackNavigationTest`（设置返回、结果返回、设置优先、同意页文档返回）与 `SettingsBackNavigationTest`（表单返回、法律文档返回、内层优先于根级）；`testDebugUnitTest`、`lintDebug`、`assembleDebug` 通过，`openspec validate ... --strict` 通过。
- 构建中曾出现 `MergeServiceTest.mergeCopiesMessagesInSourceOrderAndKeepsSources` 因同一毫秒创建消息导致顺序断言偶发失败，单独重跑与再次全量运行均通过，属既有测试的时序不稳定，不在本变更范围。
- 未执行：模拟器上逐级返回、返回手势与外接键盘返回的人工验证（4.2）。
