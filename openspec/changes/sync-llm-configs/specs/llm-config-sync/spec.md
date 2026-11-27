# Spec Delta

## Purpose

让已配对的安卓设备在局域网同步时同时获得对方配置的模型与凭据，减少重复配置，同时保持密钥只在本机落盘加密保存。

## ADDED Requirements

### Requirement: 同步模型配置与 API Key

局域网同步 SHALL 在现有加密会话中交换模型配置，包括名称、服务商类型、协议、Base URL、模型名、超时、启用状态和 API Key。API Key SHALL 仅在已配对设备之间的加密通道中传输；接收端 MUST 使用本机 Keystore 重新加密后保存，MUST NOT 将明文 Key 写入记录目录或日志。

#### Scenario: 新设备获得已有模型
- **WHEN** 设备 A 已配置模型并包含 API Key，设备 B 与其完成配对并进入同步
- **THEN** 设备 B 的设置页出现同一模型，且无需重新填写 Key 即可使用

#### Scenario: 只修改 Key
- **WHEN** 设备 A 更新某模型的 API Key 并保存
- **THEN** 下次同步后设备 B 使用更新后的 Key

### Requirement: 合并与删除传播

模型配置 SHALL 按 id 合并，同一 id 以 `updatedAt` 较新的版本为准；两端各自新增的不同模型 MUST 全部保留。删除 SHALL 记录带时间戳的墓碑并传播，墓碑新于对端同名配置时移除该配置及其 Key。

#### Scenario: 两端同时新增不同模型
- **WHEN** 两端在离线状态下各自新增一个模型后重新连接
- **THEN** 两端最终都包含这两个模型

#### Scenario: 一端删除模型
- **WHEN** 设备 A 删除某模型，设备 B 仍保留旧配置
- **THEN** 同步后设备 B 也移除该模型，且不会在后续同步中恢复

### Requirement: 不改变设备偏好与同步稳定性

默认模型选择等设备偏好 MUST 留在本机。合并结果与本机一致时 MUST NOT 写入或提升同步代次；模型变化 SHALL 通过现有代次机制触发下一轮同步，且不得造成同步回环。

#### Scenario: 收到相同配置
- **WHEN** 对端设置的模型与本机完全一致
- **THEN** 本机不写记录文件、不提升代次，下一轮不重复传输
