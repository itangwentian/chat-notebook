# Tasks

## 1. 排序实现

- [x] 1.1 `MessageSharing` 增加常用应用包名顺序与纯排序函数 `orderShareTargets`。
- [x] 1.2 `findTargets` 返回排序后的目标列表。

## 2. 测试与验证

- [x] 2.1 新增单测：常用应用按既定顺序优先，其余按名称排序，未安装项不占位。
- [x] 2.2 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- [x] 2.3 运行 `npx --yes @fission-ai/openspec@2.0.0 validate prioritize-common-share-apps --strict --no-interactive`。

## 实施记录

- 2.1 新增 2 个用例：常用应用优先且其余按名称排序、缺少常用应用时不留空位。
- 2.2 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，32 个测试套件、209 个测试全部通过。
- 2.3 OpenSpec strict validate 通过（Change 'prioritize-common-share-apps' is valid）。
- 未在模拟器上人工确认实际应用列表顺序。