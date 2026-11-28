# Proposal

## Why

当前配对与同步完全依赖 mDNS（`NsdManager`）互相发现：显示二维码的一方注册服务，扫码方发现后连接。在 Windows Subsystem for Android（WSA）上运行本应用时，mDNS 发现不可用，导致电脑端与手机无法配对，也无法同步。实测 WSA 的局域网 IPv4 入站可达（WSA 与宿主机共用同一 IP，局域网设备可连接其监听端口），因此改为“二维码携带地址与端口、扫码直连”即可打通电脑端。

## What Changes

- 配对二维码携带对端局域网 IPv4 地址与监听端口（连同证书指纹、一次性令牌和协议版本），扫码方解析后直接建立 TCP 连接，不再依赖 mDNS 服务发现。
- 显示二维码前先绑定监听端口，再生成并展示二维码；网络地址变化或二维码过期时取消配对并提示重新生成。
- 同步也不再依赖 mDNS：配对时记录对端地址，同步端口由双方指纹确定性选择；收到入站连接时用实际来源地址更新对端记录。
- 移除 `NsdManager` 服务注册/发现与多播锁相关代码，配对与同步失败提示改为可操作的直连提示。
- **BREAKING**：配对协议版本升级，双方必须升级到新版本后重新扫码配对；升级后缺少对端地址的旧配对条目无法直连，需重新扫码配对。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `lan-note-sync`: 配对与同步的设备发现从 mDNS 改为二维码携带地址直连，信任条目新增对端地址，并规定地址变化与旧条目重新配对的处理。
- `lan-pairing-scan`: 配对二维码内容新增对端地址与端口，扫码后的连接与失败提示按直连方式调整。

## Impact

- 代码：`data/sync/LanTransport.kt`、`data/sync/LanPairing.kt`、`data/sync/LanSyncManager.kt`、`data/sync/PairingQr.kt` 及相关单元测试；设置页同步状态文案。
- 依赖：移除 `NsdManager`、`WifiManager` 多播锁的使用，不新增第三方依赖。
- 兼容性：配对协议版本升级，旧版本互不兼容；已配对但缺少地址的条目需重新扫码配对。