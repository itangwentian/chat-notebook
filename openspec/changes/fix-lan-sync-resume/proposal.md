# Proposal

## Why

在两台设备上点击“暂停同步”后再点击“继续同步”，界面提示连接失败，之后即使双方都在前台、同一局域网可达，也不再交换数据，只能重启应用或重新配对。

## What Changes

- 同步会话的建立过程（读取设备身份、同步记录跟踪、网络与服务发现）改为失败后自动重建并按退避重试，一次瞬时失败不再永久结束该设备的同步协程。
- 配对会话采用同样的重试策略，避免一次“配对连接失败”后无法再次配对。
- 网络会话在服务发布、服务发现或监听失败时自动重建，而不是让整个同步流程结束。
- 暂停后继续同步仍按规范关闭旧连接，并由重试策略自动恢复新连接，无需重新配对。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `lan-note-sync`: 明确暂停后继续同步、以及网络或服务发现瞬时失败后，系统必须自动重建会话并重试，不得因一次失败永久停止同步。

## Impact

- `app/src/main/java/com/chatnotebook/app/data/sync/LanSyncManager.kt`：`syncPeer` 与 `runPairing` 改为监督重试，失败提示统一。
- `app/src/main/java/com/chatnotebook/app/data/sync/LanTransport.kt`：`foregroundLanSessions` 在会话失败后按退避重建并回调错误。
- 单元测试：新增会话失败后自动重建的断言。