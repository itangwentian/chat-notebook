# Tasks

## 1. 信任列表

- [x] 1.1 `LanTrustStore` 改为读写 `lan_sync_peers` JSON 数组，兼容旧的 `lan_sync_peer` 单对象并在写入后移除旧键。
- [x] 1.2 `LanSyncManager` 使用信任列表，新增设备时追加而非覆盖；设备身份变化时清理失效条目。

## 2. 多设备同步

- [x] 2.1 按信任列表为每台设备启动独立同步循环，复用现有发现标签与协议。
- [x] 2.2 每次连接与协议交换使用共享互斥锁串行执行，单台失败不影响其他设备。
- [x] 2.3 解除配对改为按指纹移除；移除最后一台时清空最近成功时间。

## 3. 配对入口与设备列表

- [x] 3.1 `LanSyncStatus` 暴露已配对设备列表；配对入口在已配对后仍然显示。
- [x] 3.2 设置页列出各设备并支持逐台移除确认；配对中显示取消入口。

## 4. 测试与验证

- [x] 4.1 更新信任存储测试：旧单设备迁移、多设备读写、移除单台。
- [x] 4.2 更新设置页测试：有设备时添加设备入口仍可见、逐台移除、全局暂停。
- [x] 4.3 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，执行 `npx --yes @fission-ai/openspec@2.0.0 validate support-multiple-lan-devices --strict --no-interactive`，记录结果。
- [ ] 4.4 三台设备同时在线的人工验证无法在本机完成时如实记录。

## 实施记录

- 信任存储：`LanTrustStore` 使用 `lan_sync_peers` 数组，读取时回退旧 `lan_sync_peer`，写入后删除旧键；测试覆盖迁移、多设备读写与逐台移除。
- 管理器：`LanSyncStatus.peer` 改为 `peers: List<LanDevice>`；配对与同步拆成两个循环，配对不再检查已有设备；每台设备一个同步子任务，连接与协议交换通过 `sessionLock` 串行；单台异常只影响自身。
- 界面：已配对后仍显示“显示二维码 / 扫码配对 / 从图片识别”；设备列表逐行“移除设备”并弹出对应确认；暂停/继续与最近成功时间保持全局。
- 验证：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 通过；`validate --strict` 通过（详见最终汇报）。
- 未验证：三台设备同时在线的真实同步。当前环境只有 WSA 与一个模拟器，无法完成三台设备配对与交换的人工验证。
