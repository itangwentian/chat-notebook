# Spec Delta

## Purpose

让用户以自备密钥方式维护和切换多个文本模型配置，以统一的连接测试和调用行为解耦业务与供应商，并保护本地凭据，避免模型设置变化或请求失败损害已保存的信息。

## ADDED Requirements

### Requirement: 多模型配置生命周期
系统 SHALL 支持新增、编辑、删除多个配置，保存名称、Provider 类型、Base URL、Model ID、协议、启用状态及创建和更新时间，支持 DeepSeek、Qwen、Kimi、MiniMax、OpenAI 和自定义类型。URL 和模型由用户配置，不固定模型名称；不支持的协议 SHALL 明确提示而非伪装可用。

#### Scenario: 保存多个配置
- **WHEN** 用户保存两个不同模型配置并重启
- **THEN** 两者非敏感字段恢复，名称和模型可区分，凭据仅以脱敏状态呈现

### Requirement: 默认模型与请求快照
系统 SHALL 允许选择已启用的可用配置作为默认模型并切换；每次请求使用发起时的配置快照。默认配置删除或禁用后 SHALL 清除默认并提示选择，不擅自替换为其他配置或 Mock。

#### Scenario: 请求期间切换
- **WHEN** 模型 A 请求处理中用户将默认模型改为 B
- **THEN** 已发起请求仍归属 A，后续请求使用 B

### Requirement: 统一文本调用与连接测试
系统 SHALL 使用可替换的统一文本请求响应接口，支持配置化 OpenAI Compatible 协议并按能力声明文本支持；测试连接 SHALL 使用指定配置执行最小文本请求，返回明确中文成功、认证失败、超时或协议错误。未配置默认模型 SHALL 不妨碍保存笔记。

#### Scenario: 认证失败
- **WHEN** 连接测试返回认证错误
- **THEN** 界面显示脱敏中文错误，不泄露请求头或密钥，不标记测试成功

### Requirement: 凭据保护
系统 SHALL 使用 Android Keystore 保护加密密钥，仅持久化加密后的 API Key；UI 脱敏，不写入日志、崩溃信息、URL 或普通配置文件，删除配置同时清理凭据。加密凭据不可解密时 SHALL 提示重新输入，不能静默覆盖配置。远程凭据传输 SHALL 使用 HTTPS。

#### Scenario: 删除配置
- **WHEN** 用户确认删除模型配置
- **THEN** 非敏感配置与对应凭据均清理，后续请求无法再选择该配置

#### Scenario: 密钥失效
- **WHEN** 恢复数据后原 Keystore 密钥不可用
- **THEN** 配置显示需要重新输入凭据，不返回解密细节或明文
