# Tasks

## 1. 会话自动重建

- [x] 1.1 `foregroundLanSessions` 在会话失败时回调错误并按退避重建，验证方式：新增单测断言首次失败后再次调用 `serve`
- [x] 1.2 `LanSyncManager.syncPeer` 将设备身份读取、`startSyncTracking` 与网络会话纳入失败重试，设备取消信任时退出
- [x] 1.3 `LanSyncManager.runPairing` 采用同样的失败重试，保持二维码与扫码超时行为

## 2. 验证

- [x] 2.1 运行 `.\gradlew.bat :app:testDebugUnitTest`、`.\gradlew.bat :app:assembleDebug`、`.\gradlew.bat :app:lintDebug`
- [x] 2.2 运行 `npx --yes @fission-ai/openspec@2.0.0 validate --all --strict`
- [x] 2.3 在模拟器安装后暂停、继续同步，确认界面回到“等待自己的设备”且不再停留在失败提示

## 验证说明

- 新增单测 `failedSessionIsRebuiltInsteadOfEndingSync`：首次会话抛出 `IOException` 后按 500ms 退避再次调用 `serve`，并回调一次失败信息。
- 模拟器（Android 模拟器，注入已配对设备 `FakePeer`）连续暂停、继续同步 4 次，状态均回到“等待自己的设备”，未出现失败提示；验证后已删除注入的假设备数据（`local_network_sync.xml`）。
- 该模拟器仅有 `eth0` 且被系统标记为 CELLULAR，没有 WIFI/ETHERNET 网络，无法单机验证两端数据交换；真实双设备暂停、继续同步仍需在两台真机上复测。

## Workflow follow-up

- 变更验收后按项目约定归档到 `openspec/changes/archive/`。