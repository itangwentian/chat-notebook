# Tasks

## 1. 配对二维码携带地址

- [x] 1.1 扩展 `PairingOffer`（协议版本 4）：新增 `address`、`port`，移除 `discoveryId`，并在解码时校验 IPv4 字面量、非回环/组播地址与端口范围
- [x] 1.2 调整 `PairingTicket` 与 `showPairing()`：先取局域网接口并绑定端口，再生成二维码；绑定失败、无局域网或地址变化时给出对应提示
- [x] 1.3 显示方改为在已绑定的 `ServerSocket` 上 accept，二维码有效期内端口保持不变，过期/取消/离开前台时关闭

## 2. 扫码直连

- [x] 2.1 扫码方校验二维码地址是否在当前子网，不满足时提示连接同一局域网
- [x] 2.2 扫码方在有效期内按退避重试直连二维码地址，成功后沿用现有 TLS 认证与配对握手；失败/超时给出可操作提示
- [x] 2.3 移除扫码方的本地服务注册与发现逻辑

## 3. 同步改直连

- [x] 3.1 `LanTrust` 增加 `host` 字段（默认空），`PairDevice` 增加对端地址；配对时双方分别写入来源地址与二维码地址
- [x] 3.2 新增确定性端口计算函数（指纹对 → 20000–28999），双方使用同一端口
- [x] 3.3 `synchronize` 改为“本地监听 + 主动连接 `host:port`”，移除 mDNS 发现与 `discoveryTag`
- [x] 3.4 入站连接认证通过后用来源地址更新对端 `host`；地址不在当前子网时提示重新扫码
- [x] 3.5 `host` 为空的旧条目提示“请重新扫码配对以恢复同步”，不做 mDNS 回退

## 4. 移除 mDNS 依赖

- [x] 4.1 删除 `NsdManager` 注册/发现、多播锁、`SERVICE_TYPE`、`LanPeer.discoveryId` 及相关死代码
- [x] 4.2 `LanTransport` 收敛为 `interfaces()` / `bind()` / `listen()` / `connect(address, port)`，保留子网校验与超时

## 5. 文案与设置页

- [x] 5.1 更新配对与同步状态文案：等待扫码、正在连接、无法连接、不在同一局域网、请重新扫码配对
- [x] 5.2 确认旧条目与版本不兼容提示在设置页可见，且不修改笔记本体

## 6. 测试与验证

- [x] 6.1 更新 `LanPairingTest`、`PairingQrTest`：新字段往返、旧版本二维码被拒绝、非法地址/端口被拒绝
- [x] 6.2 新增端口计算单元测试：对称、范围、稳定
- [x] 6.3 运行 `testDebugUnitTest`、`assembleRelease`、`lintDebug`
- [ ] 6.4 在 WSA 与手机（或两台设备）上实测扫码配对与后续同步，记录实际结果与未完成项（WSA 侧监听与二维码识别已验证，跨设备扫码仍缺第二台设备）
- [x] 6.5 更新 README 更新记录与 `ABOUT.md`（如涉及配对方式说明）

## 实施记录

- `LanTransport` 移除 `NsdManager`、多播锁、`SERVICE_TYPE` 与 `LanPeer`，收敛为 `interfaces()` / `bind()` / `listen()` / `connect(address, port)`；`listen` 在监听套接字被关闭时正常结束，`bind` 失败时关闭套接字。
- `LanPairing` 升级到协议 4：二维码携带 IPv4 地址与端口，解码校验地址字面量与端口范围；`LanTrust`/`PairDevice` 新增 `host`，配对时显示方记录来源地址、扫码方记录二维码地址。
- `LanSyncManager`：显示方先绑定端口再展示二维码，扫码方按子网校验后直连并重试；同步使用指纹对确定性端口，入站认证后刷新对端地址；`host` 为空的旧条目提示重新扫码。同步会话以“指纹 + 是否已有地址”为键，地址自愈不重启会话。
- 验证：`.\gradlew.bat testDebugUnitTest`、`lintDebug`、`assembleRelease` 均通过（含新增的二维码地址/端口校验与 `lanPort` 对称、范围、稳定用例）；`npx --yes @fission-ai/openspec@2.0.0 validate --all --strict --no-interactive` 32 项全部通过。旧版本二维码缺少地址与端口时，先读出版本号给出“版本不兼容”提示而不是“内容无效”。
- 已将新构建安装到本机 WSA（`adb install -r`）供后续联调。
- WSA 实测发现：WSA 与 Windows 宿主共用同一 IPv4（192.168.31.138），且镜像网络只把 AF_INET 监听映射到宿主。默认 java.net 套接字是 IPv6 双栈（`/proc/net/tcp6` 里的 `::`/`::ffff:`），宿主连接被拒绝；`-Djava.net.preferIPv4Stack=true` 与 `ServerSocketChannel.open(ProtocolFamily)` 均不可用。为此新增 `Inet4Sockets.kt`：用 `android.system.Os` 创建 AF_INET 的 `Inet4Listener`（bind/listen/poll/accept）与基于 `SocketImpl` 的 `Inet4Socket`，`LanTransport.bind/listen` 与 `LanSyncManager.Pairing.Show` 改用它，TLS 仍由 Conscrypt 通过套接字 fd 完成。
- 本次 WSA 验证：安装启动后进入设置 → 局域网同步 → 显示二维码，`/proc/net/tcp` 出现应用 uid 的监听记录（如 `8A1FA8C0:9DE3` 即 192.168.31.138:40419，状态 `0A`），说明监听已是 AF_INET；Windows `netstat` 可见该端口由 WSA 代理监听，宿主 TCP 连接可建立。二维码 2 分钟过期提示、从图片识别二维码后提示“请确认两台设备连接同一局域网”均正常（自身地址被 `canReach` 拒绝）。
- 未完成：跨设备扫码配对与同步实测。WSA 与宿主共用 IP，宿主发起的连接在应用内会被当作本机地址过滤，无法替代手机；当前 adb 只有 WSA，没有第二台设备。真机验证时应在手机与 WSA 处于同一局域网后互扫二维码，并确认配对后能双向同步。