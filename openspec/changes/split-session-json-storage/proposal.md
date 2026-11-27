# Proposal

## Why

当前记录目录每次保存都会写出一个包含全部对话的 `records-*.json` 快照。对话越多，单文件越大，每次发送、编辑或打开对话都要重写整库；旧快照不断累积，恢复时还要逐个读取、校验全部快照，模拟器上全量恢复已需要数分钟。用户要求“每组对话一个 json，而不是每个消息一个 json”，需要把持久化粒度改为按对话隔离。

## What Changes

- 每个未删除的对话 SHALL 对应一个 `session-<sessionId>-<20 位 generation>.json`，文件内包含该对话的会话信息、全部消息、AI 结果、编辑历史、引用、气泡格式、以该对话为目标的合并关系、整理状态、消息合并与整理文档。
- 全局模型配置 SHALL 单独写入 `settings-<generation>.json`，不再随每个对话重复保存。
- 写入 SHALL 先创建新 generation 文件并回读校验，成功后才删除同对话的旧 generation 文件；写入失败时保留旧文件与内存索引。
- 恢复 SHALL 按对话聚合最新有效文件；某个对话的最新文件损坏时回退到该对话的上一有效 generation；只有存在候选文件却无任何有效数据时才报错，且 MUST NOT 修改现有文件。
- 标记删除的对话 SHALL 不再写入，并删除其已有文件；重启后该对话不再恢复。
- 记录目录中只有旧 `records-*.json` 时 SHALL 自动迁移：按对话拆分后逐个写入并校验，全部成功后才删除旧快照；任一步失败 SHALL 回滚本次新文件并保留旧快照。
- 保留 `SnapshotCodec` 以读取旧快照；Room 仅作为可重建索引，结构与版本不变。

## Capabilities

### New Capabilities

- `session-file-storage`：按对话拆分 JSON 持久化、逐对话原子写入、损坏回退、删除清理与旧快照迁移。

## Impact

- 代码：`SnapshotStore` 接口改为按文件名读写与删除；`SafSnapshotStore` 支持会话/设置文件；`Snapshot.kt` 新增会话文件与设置文件编解码；`RecordRepository` 改为聚合加载、按会话 diff 写入、迁移与清理。
- 数据：新文件格式；旧 `records-*.json` 仅用于迁移，迁移成功后删除。Room 索引可随时重建，不改变表结构。
- 测试：更新 5 个内存 Store；新增每对话独立文件、损坏回退、删除清理与旧快照迁移用例。
- 验证：`testDebugUnitTest`、`lintDebug`、`assembleDebug`；模拟器检查记录目录中每对话一个 JSON、重启恢复、发送/编辑/删除/转发/整理不回归。
