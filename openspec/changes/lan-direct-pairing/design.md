# Design

## Context

配对与同步目前都依赖 mDNS 发现：`LanTransport.serve()` 在绑定端口后用 `NsdManager` 注册服务并发现对端，拿到 IP 与端口后再建连。WSA 内的 `NsdManager` 发现不可用，导致电脑端无法与手机配对和同步。实测 WSA 与宿主机共用同一 IPv4（镜像网络），局域网对该地址的入站连接可达，因此只要拿到地址和端口即可直连，无需 mDNS。

## Goals / Non-Goals

**Goals**

- 配对完全由二维码携带地址与端口，扫码后直连，不再依赖 mDNS。
- 配对后的同步同样不依赖 mDNS，在 WSA 与手机之间可用。
- 旧配对数据、旧二维码有明确的兼容与提示行为。

**Non-Goals**

- 不引入手动输入地址的界面。
- 不改变同步协议版本与数据合并规则。
- 不保证对端更换局域网地址后无需任何配对即可恢复（依赖入站连接更新地址，见 Risks）。

## Decisions

### 1. 二维码内容

`PairingOffer` 升级为协议版本 4：

```text
protocol=4, address=<对端局域网 IPv4>, port=<监听端口>, fingerprint=<证书指纹>, token=<一次性令牌>
```

- 移除 `discoveryId`（只为匹配 mDNS 服务存在）。
- 解码时校验：`address` 必须是 IPv4 字面量且非回环/组播/任意地址，`port` 在 1024..65535，指纹与令牌格式不变。
- 旧版本二维码因 `protocol` 不匹配，提示“配对版本不兼容，请更新两台设备”。

### 2. 显示方：先绑定端口，再生成二维码

- `showPairing()` 先取得当前局域网接口（`interfaces().filterNotNull().first()`，超时 10 秒，否则提示“请先连接局域网”），在 `lan.address` 上绑定 `ServerSocket(0)`，用实际端口构造 `PairingOffer`，再展示二维码。
- 随后在后台跑 accept 循环；监听失败按 `foregroundLanSessions` 的退避重建，二维码保持不变（端口不变，因为绑定在展示前完成）。
- 二维码有效期内接口地址变化、应用离开前台、用户取消或配对成功时关闭 `ServerSocket` 并清理状态；地址变化时提示“网络已变化，请重新生成二维码”。
- 二维码 120 秒过期行为保持不变。

### 3. 扫码方：解析后直连，带退避重试

- 校验二维码地址是否在当前局域网子网内（复用 `LanInterface.canReach`），不满足直接提示“请确认两台设备连接同一局域网”。
- 在二维码有效期内循环尝试连接（500ms 起、指数退避、上限 10 秒），成功后走原有的 TLS 双向认证与 `PairRequest`/`PairAccepted` 流程；失败或超时提示“无法连接该设备，请确认两台设备在同一局域网后重试”。
- 扫码方不再监听、不再注册服务。

### 4. 同步：保存对端地址 + 确定性端口

- `LanTrust` 新增 `host: String = ""`（默认值保证旧数据可解码）。
- 配对时写入对端地址：显示方取 accept 到的 socket 来源地址，扫码方取二维码中的地址。
- 双方同步监听端口由指纹对确定性计算：
  `port = 20000 + (sha256("lan-port:" + min(fpA,fpB) + ":" + max(fpA,fpB)) 前 4 字节 % 9000)`
  端口范围 20000–28999，避开系统临时端口段；两端算出同一个端口，各在自己设备上监听，连接对端时使用同一端口。
- `synchronize` 不再发现，只做两件事：在 `lan.address:port` 上 accept；当本机指纹较小（initiator）时，主动连到 `trust.host:port`。
- 收到入站连接并通过指纹认证后，用 socket 来源地址更新该对端的 `host`，实现地址变化后的自愈。
- 对端地址不在当前子网时提示“对端不在当前局域网，请重新扫码配对”，并按退避重试；`host` 为空的旧条目直接提示“请重新扫码配对以恢复同步”，不做 mDNS 回退。

### 5. 移除 mDNS

- 删除 `NsdManager` 注册/发现、`WifiManager` 多播锁、`SERVICE_TYPE`、`LanPeer.discoveryId` 与 `discoveryTag()`。
- `LanTransport` 对外只保留：`interfaces()`、`bind()`、`listen()`、`connect(address, port)`。
- 保留 `sameSubnet` 校验与监听前的 `canReach` 过滤。

## Risks / Trade-offs

- **地址变化**：手机切换网络后地址会变。若对端仍能发起入站连接，地址会被自动更新；否则需要重新扫码配对。这比 mDNS 弱，但换来了 WSA 可用性，且无后台组播开销。
- **端口确定性冲突**：同一设备与多个对端配对时理论上可能算出相同端口（9000 分之一量级），表现为监听失败与可见错误提示，重试与重新配对可恢复。
- **旧数据**：升级后缺少 `host` 的条目无法同步，界面提示重新扫码；不会删除条目或本机笔记。
- **WSA 网络**：仍依赖 Windows 防火墙允许该端口入站；当前环境专用/公用防火墙已关闭，实测可达。

## Migration Plan

1. 升级后 `LanTrust.host` 默认为空，列表照常显示，同步时提示重新扫码配对。
2. 双方升级到新版本后重新扫码，写入地址并恢复同步。
3. 无需迁移记录目录或同步数据。

## Open Questions

无。