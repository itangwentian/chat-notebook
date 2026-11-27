# Tasks

## 1. 设置载荷与合并

- [x] 1.1 新增 `SyncSettings` / `SyncLlmConfig` 与墓碑存储（偏好中的 `id -> deletedAt`）。
- [x] 1.2 实现确定性合并：按 id 取较新配置、合并墓碑、墓碑新于配置时移除。
- [x] 1.3 单元测试：新增合并、同 id 编辑、两端并发新增、删除传播、Key 跟随较新配置。

## 2. 同步协议

- [x] 2.1 `SyncLibrary` 读取本机配置与（解密后的）Key，生成设置载荷。
- [x] 2.2 `LanSyncProtocol.exchange` 在清单交换后增加设置交换：发起方先写后读，接收方先读后写。
- [x] 2.3 收到后应用合并结果；无差异时不写入、不提升代次。

## 3. 应用与密钥

- [x] 3.1 `RecordRepository` 增加应用合并配置的方法，并在有变化时提升代次。
- [x] 3.2 应用后通过 `CredentialStore` 保存或删除对应 Key，使用本机 Keystore 重新加密。
- [x] 3.3 `ChatRepository.deleteLlmConfig` 记录删除墓碑。

## 4. 验证

- [x] 4.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，执行 `npx --yes @fission-ai/openspec@2.0.0 validate sync-llm-configs --strict --no-interactive`，记录结果。
- [x] 4.2 双设备人工验证模型与 Key 同步、删除传播；无法执行时如实记录。

## 实施记录

- 4.1 自动化验证通过：`.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，32 个测试套件、200 个测试全部通过（含新增 `SyncSettingsTest`、`LlmConfigTombstonesTest`）；`npx --yes @fission-ai/openspec@2.0.0 validate sync-llm-configs --strict --no-interactive` 通过。
- 4.2 由用户在两台可互联设备上完成人工验证，模型配置与 API Key 同步、删除传播均符合预期。此前单机模拟器（AVD `chat_notebook_api35`，API 35）验证：设置页「测试连接」结果以底部 Snackbar 呈现；局域网同步卡片与配对二维码正常显示；删除模型配置后 `shared_prefs/llm_config_state.xml` 写入 `deleted_<id>` 墓碑。两个模拟器实例因独立 NAT（均 10.0.2.15/24 但相互隔离）无法互相发现，`-wifi-server-port`/`-wifi-client-port` 在 netsim 虚拟 Wi-Fi 下未生效，`-shared-net-id` 也未生成第二网卡。
- 兼容性说明：设置帧在既有同步握手内交换，未提升 `LAN_SYNC_VERSION`；旧版本与本次版本混合配对时，设置帧解析会失败并表现为同步失败，需两端同时升级。
