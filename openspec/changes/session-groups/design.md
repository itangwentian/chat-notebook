# Design

## 分组模型

分组固定为两个内置项，用枚举表达，首次安装即存在，无需初始化写入：

```kotlin
enum class SessionGroup(val storageKey: String, val label: String) {
    Important("important", "极其重要"),
    Draft("draft", "草稿"),
}
```

`SessionGroup.fromKey(null)` 回退到 `Draft`，因此“对话未指定分组”和“首次安装没有历史选择”自然落到草稿。

## 存储

`SessionGroupStore` 使用 SharedPreferences（`session_groups`）：

- `selected_group`：最后一次选中的分组。
- `session_<id>`：会话所属分组。

传入 `null` 的实例是内存实现，供单元测试使用；应用通过 `SessionGroupStore.persisted(context)` 创建。

分组属于设备偏好，和排序方式一样不写入记录目录，也不参与局域网同步。另一台设备同步到的对话若本机没有归属记录，显示在“草稿”。

## 状态与交互

- `ChatUiState` 增加 `selectedSessionGroup` 与 `sessionGroups`（会话 id → 分组），并提供 `groupOf()`（缺省草稿）。
- `selectSessionGroup`：保存并切换筛选。
- `createSession`：新会话归入当前选中分组。
- `selectSession`：把选中项切到该会话所属分组，避免选中的会话从列表消失。
- `moveSessionToGroup`：更新归属；若移动的是当前打开的会话，同时切换选中分组。
- `deleteSession`：删除归属记录。
- 抽屉在“新对话”按钮下方显示两个 `FilterChip`；列表只渲染当前分组，空分组显示“该分组暂无对话”；会话菜单增加“移到另一分组”。

## 取舍

- 未做分组增删改名：需求只要求两个默认分组与默认选中，固定分组能保持存储与界面简单。
- 未把归属写入会话记录：避免改动数据库结构、记录格式与同步协议；代价是分组不跨设备同步。