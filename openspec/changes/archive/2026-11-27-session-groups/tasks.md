# Tasks

## 1. 分组数据与状态

- [x] 1.1 新增 `SessionGroup` 与 `SessionGroupStore`（默认草稿、记住最后选择、会话归属）。
- [x] 1.2 `ChatUiState` 增加选中分组与会话归属映射，`ChatViewModel` 支持选择分组、新建归组、移动分组、删除清理。

## 2. 界面

- [x] 2.1 抽屉增加“极其重要/草稿”筛选，只显示当前分组，空分组给出提示。
- [x] 2.2 会话菜单增加“移到另一分组”。

## 3. 测试与验证

- [x] 3.1 单测：首次安装默认草稿、记住最后选择、新建对话归入当前分组、移动会话同步切换分组。
- [x] 3.2 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- [x] 3.3 运行 `npx --yes @fission-ai/openspec@2.0.0 validate session-groups --strict --no-interactive`。

## 实施记录

- 3.2 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，32 个测试套件、206 个测试全部通过（新增 3 个用例：首次安装默认草稿、记住最后选择并让新对话归入当前分组、移动当前会话时同步切换分组）。
- 3.3 OpenSpec strict validate 通过（Change 'session-groups' is valid）。
- 未在模拟器上人工确认筛选与移动菜单的实际显示效果。