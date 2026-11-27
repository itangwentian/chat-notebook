# Spec Delta

## Purpose

把持久化粒度从“整库快照”改为“每个对话一个 JSON”，让写入、恢复与删除都以单个对话为单位，降低单次写入成本并缩短恢复时间。

## ADDED Requirements

### Requirement: 每个对话一个文件

每个未删除对话 SHALL 持久化为独立文件 `session-<sessionId>-<20 位 generation>.json`。该文件 SHALL 包含该对话的会话信息、全部消息、整理结果、编辑历史、引用、气泡格式、以该对话为目标的合并关系、整理状态、消息合并记录与整理文档。系统 MUST NOT 再写出包含全部对话的整库快照文件。

全局服务配置 SHALL 保存在独立的 `settings-<generation>.json` 中，MUST NOT 复制到每个对话文件。

#### Scenario: 两个对话产生两个文件
- **WHEN** 用户创建两个对话并分别发送消息
- **THEN** 记录目录中出现两个以各自 sessionId 命名的会话 JSON，每次保存只更新发生变化的对话文件

#### Scenario: 全局设置不重复保存
- **WHEN** 用户保存服务配置
- **THEN** 配置写入设置文件，各会话文件不包含服务配置副本

### Requirement: 逐对话原子写入

写入 SHALL 先创建新的 generation 会话文件并回读校验字节一致，成功后才删除同一对话的旧 generation 文件。写入失败时 SHALL 保留旧文件与当前内存索引，MUST NOT 让索引出现部分新数据。

#### Scenario: 写入失败保持旧数据
- **WHEN** 记录目录在保存时不可写
- **THEN** 保存失败，旧会话文件仍在，重启后仍能恢复到保存前的消息

#### Scenario: 校验成功后才清理旧版本
- **WHEN** 某对话保存成功
- **THEN** 新 generation 文件可读取且校验一致，旧 generation 文件被删除

### Requirement: 按对话恢复与损坏回退

启动时 SHALL 按 sessionId 聚合会话文件，并选择每个对话最新的有效 generation。某个对话的最新文件校验失败时 SHALL 回退到该对话更早的有效文件，并记录恢复警告。存在候选文件但没有任何有效数据时 SHALL 报错并 MUST NOT 删除或改写现有文件。Room 索引 SHALL 由恢复出的聚合数据完整重建。

#### Scenario: 重启恢复多个对话
- **WHEN** 记录目录中有多个有效会话文件并重启应用
- **THEN** 每个对话的消息、整理文档与派生数据分别恢复，互不混合

#### Scenario: 最新文件损坏
- **WHEN** 某对话最新 generation 文件损坏而旧 generation 有效
- **THEN** 该对话恢复为旧 generation 的内容，其他对话不受影响

#### Scenario: 无有效文件
- **WHEN** 目录中存在候选文件但全部校验失败
- **THEN** 恢复失败并保留全部原文件

### Requirement: 删除对话清理文件

标记删除的对话 SHALL 在保存时被跳过并删除其会话文件，SHALL NOT 再写入新版本。应用重启后该对话 MUST NOT 被恢复。

#### Scenario: 删除后重启
- **WHEN** 用户删除一个对话并重启应用
- **THEN** 该对话及其消息不再出现，其他对话文件保持不变

### Requirement: 旧快照迁移

当记录目录中只有旧 `records-*.json` 快照而没有新格式文件时，应用 SHALL 自动迁移：解码最新有效旧快照，按对话拆分为会话文件并写入设置文件；全部写入并校验成功后才删除旧快照。任一写入失败 SHALL 删除本次已写文件并保留旧快照。

#### Scenario: 首次启动自动迁移
- **WHEN** 用户升级后打开包含旧快照的记录目录
- **THEN** 目录被拆分为每对话一个 JSON，旧快照在成功后删除，对话与消息完整保留

#### Scenario: 迁移写入失败
- **WHEN** 迁移过程中写入某个会话文件失败
- **THEN** 本次已写入的新文件被回滚，旧快照保留，下次启动可重试
