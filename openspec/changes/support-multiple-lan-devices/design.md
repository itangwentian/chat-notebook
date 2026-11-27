# Design

## Context

`LanTrustStore` 在 `local_network_sync` 偏好中只保存一个 `lan_sync_peer`；`LanSyncManager` 用一个 `trust` 单值驱动单个同步会话，`showPairing`/`join` 在已有信任时直接返回；`LanSyncSection` 在有设备时隐藏配对按钮。配对入口的缺失正是这些单值假设的结果。

## Goals / Non-Goals

- 支持任意数量的已配对设备，新增设备不覆盖旧设备。
- 保留单设备版本的信任数据迁移与现有同步协议。
- 不引入账号、服务器或云端，不改变附件与冲突规则。

## Decisions

### 信任列表持久化

`LanTrustStore` 改为读写 `lan_sync_peers` 的 JSON 数组；读取时若新键不存在，则回退读取旧的 `lan_sync_peer` 单对象并返回单元素列表。写入新键后删除旧键，避免回退数据覆盖新状态。

### 管理器按设备分循环

`trust` 改为 `MutableStateFlow<List<LanTrust>>`，`LanSyncStatus.peer` 改为 `peers: List<LanDevice>`（指纹 + 名称）。

- 配对循环只依赖前台、记录目录与配对状态，不再检查是否已有设备；完成配对时把新信任追加到列表。
- 同步循环按信任列表为每台设备启动一个子任务，复用现有 `synchronize` 逻辑与每对设备独立的 `discoveryTag`。
- 每次实际连接与协议交换使用共享 `Mutex` 串行化，避免两台设备同时应用记录版本。
- 移除设备只从列表删除对应指纹；列表为空时清空最近成功时间。

### 界面

设置页在记录目录可用时：

- 有设备时先列出“自己的设备 · 名称”，每行提供“移除设备”，确认对话框绑定该设备。
- 配对进行中只显示“取消配对”；否则始终显示添加设备入口。
- 暂停/继续与最近成功时间保持全局。

## Risks / Trade-offs

- [两台设备同时同步时版本冲突] → 连接与交换串行化；失败可重试，不阻塞其他设备。
- [N 台设备产生 N 个发现/服务注册] → 沿用现有随机服务名与按标签过滤，功能正确但资源占用随设备数增加。
- [旧数据迁移] → 首次写入新列表后删除旧键，避免重复迁移。

## Migration Plan

读取旧 `lan_sync_peer` 自动转为列表；不清除本机笔记，不要求重新扫码。

## References

- `app/src/main/java/com/chatnotebook/app/data/sync/LanPairing.kt`
- `app/src/main/java/com/chatnotebook/app/data/sync/LanSyncManager.kt`
- `app/src/main/java/com/chatnotebook/app/ui/LanSyncPanel.kt`
