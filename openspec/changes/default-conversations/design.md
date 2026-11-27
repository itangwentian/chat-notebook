# Design

## 默认对话的创建时机

在 `PersistentChatRepository.open()` 完成记录恢复后执行：若本机尚未执行过初始化，且快照中没有任何未删除的对话，则通过 `ChatSessionService.seedDefaultConversations()` 创建默认对话。

```kotlin
suspend fun seedDefaultConversations(): String? {
    if (records.current().sessions.any { !it.isDeleted }) return null
    val importantId = newId()
    val draftId = newId()
    val time = now()
    records.update { before ->
        before.copy(
            sessions = before.sessions + listOf(
                SessionRecord(importantId, "极其重要", time, time, lastOpenedAt = 0),
                SessionRecord(draftId, "草稿", time, time, lastOpenedAt = time),
            ),
        )
    }
    return draftId
}
```

两个对话在一次快照更新中写入，避免界面先收到只含“极其重要”的中间状态而把默认选中固定在它上面；`lastOpenedAt` 明确让“草稿”大于“极其重要”，保证首次进入选中“草稿”。

## 只依据目录是否为空

`PersistentChatRepository.open()` 在任何写入之前用 `DocumentFile.fromTreeUri(...).listFiles()` 判断目录是否为空：

- 空目录：创建默认对话。
- 存在任何文件（旧笔记、设置文件、已删除对话留下的记录文件）：跳过创建。
- 无法读取目录内容：按非空处理，避免误建。

不使用安装级偏好标记，因此卸载重装、重命名或删除默认对话后重新选择原目录，都不会重建默认对话；`seedDefaultConversations()` 内部仍保留“已有对话则跳过”的防御判断。

## 默认选中与后续恢复

`ChatViewModel` 已有逻辑按 `lastOpenedAt` 最大者激活对话；“草稿”在创建时被标记为最近打开，因此首次进入默认选中它。之后每次选择对话都会调用 `markSessionOpened`，重启后恢复最后使用的对话。

## 移除分组

删除 `SessionGroupStore`、`ChatUiState.selectedSessionGroup/sessionGroups`、`groupOf`、`selectSessionGroup`、`moveSessionToGroup`、会话菜单的“移到另一分组”与抽屉分组标签；对话列表直接展示全部对话。