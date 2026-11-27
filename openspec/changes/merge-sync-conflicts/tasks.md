# Tasks

## 1. 冲突合并

- [x] 1.1 `resolveConflict` 改为并集合并，返回单个合并后的对话，`copy` 仅在删除冲突时保留。
- [x] 1.2 实现消息、编辑历史、引用、表情、合并关系、整理文档的并集与确定性冲突规则。
- [x] 1.3 `LanSyncProtocol` 应用合并结果（`copy` 可为空）。

## 2. 测试

- [x] 2.1 单测：两端结果可交换且一致；两端新增消息并集；同消息取较新内容；附属数据并集。
- [x] 2.2 保留删除冲突用例，确认删除生效且内容另存副本。

## 3. 验证

- [x] 3.1 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- [x] 3.2 运行 `npx --yes @fission-ai/openspec@2.0.0 validate merge-sync-conflicts --strict --no-interactive`，记录结果。
- [ ] 3.3 双设备人工验证冲突并集；无法执行时如实记录。

## 实施记录

- 3.1 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，32 个测试套件、203 个测试全部通过（`SyncConflictsTest` 5 个用例覆盖并集、可交换性、较新消息胜出、附属数据并集、删除冲突）。
- 3.2 `npx --yes @fission-ai/openspec@2.0.0 validate merge-sync-conflicts --strict --no-interactive` 通过（Change 'merge-sync-conflicts' is valid）。
- 3.3 未执行双设备人工验证：本机两个模拟器实例处于独立 NAT 无法互联（详见 `sync-llm-configs` 记录），需要在可互联的两台设备上确认并发编辑后只保留一个对话且内容为并集。