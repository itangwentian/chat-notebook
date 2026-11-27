# Tasks

## 1. 检测本应用记录数据

- [x] 1.1 `RecordRepository.restore()` 在列出目录文件后记录是否存在本应用记录文件（会话、设置、旧版笔记或同步日志），并只读暴露该状态；验证方式：`RecordRepositoryTest` 覆盖空目录为否、设置文件单独存在为是、写入会话后重启恢复为是
- [x] 1.2 确认该状态在恢复阶段、任何写入之前计算，验证方式：单测断言空目录恢复后为否，删除全部会话后重启仍为是

## 2. 调整默认对话创建条件

- [x] 2.1 `ChatRepository.open()` 移除“目录没有任何文件”的判断（含 `isDirectoryEmpty` 与 `DocumentFile` 依赖），改为仅在恢复后发现“无既有记录数据”时创建默认对话，验证方式：编译通过且相关单测通过
- [x] 2.2 `ChatSessionService.seedDefaultConversations()` 的防御性判断改为“存在任何会话（含已删除）则不创建”，保持名称“极其重要”和“草稿”并默认选中“草稿”，验证方式：`ChatSessionServiceTest` 覆盖空记录创建、已有对话不重复、仅有已删除对话不创建

## 3. 验证

- [x] 3.1 运行 `.\gradlew.bat :app:testDebugUnitTest`、`.\gradlew.bat :app:assembleDebug`、`.\gradlew.bat :app:lintDebug`，确认全部通过
- [x] 3.2 运行 `npx --yes @fission-ai/openspec@2.0.0 validate --all --strict`，确认本变更校验通过
- [x] 3.3 在模拟器验证：空目录、仅含无关文件的目录、仅含空子目录的目录都会生成两个默认对话并选中“草稿”；包含本应用记录文件的目录不生成（清除数据后重新选择原目录仍只有两个对话）

## Workflow follow-up

- 变更验收后按项目约定归档到 `openspec/changes/archive/`。
- 真机验证尚未执行（当前仅有模拟器），待有设备时补充记录。