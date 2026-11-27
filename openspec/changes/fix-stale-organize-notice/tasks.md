# Tasks

## 1. 移除过期提示

- [x] 1.1 `ChatViewModel.organizeSession()` 不再在整理启动成功后写入“后台整理中”，保留失败提示；验证方式：单测断言整理后 `notice` 为空
- [x] 1.2 更新 `ChatViewModelTest` 整理流程断言，验证方式：`testDebugUnitTest` 通过

## 2. 验证

- [x] 2.1 运行 `.\gradlew.bat :app:testDebugUnitTest`、`.\gradlew.bat :app:assembleDebug`、`.\gradlew.bat :app:lintDebug`
- [x] 2.2 运行 `npx --yes @fission-ai/openspec@2.0.0 validate --all --strict`
- [x] 2.3 在模拟器从长按菜单选择“整理”，进入并退出结果页，确认不出现“后台整理中”提示，列表整理状态与未读红点正常

## Workflow follow-up

- 变更验收后按项目约定归档到 `openspec/changes/archive/`。