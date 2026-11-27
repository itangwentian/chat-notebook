# Design

## 背景

`openOrganizationResults()` 会调用 `organizeSession()` 立即重新整理，以保证查看与导出的是最新内容。`organizeSession()` 在启动成功后写入 `notice = "后台整理中"`，而 `notice` 由 `ChatScreen` 的 `SnackbarHost` 展示。用户从结果页返回对话页时，该提示才显示出来。

## 决策

- 删除整理启动成功时的 `notice` 写入，仅保留失败提示。
- 整理进度继续由 `organizingSessions` 驱动的列表状态和结果页文案表达，不新增替代提示，避免重复告知。

## 风险

- 从长按菜单选择“整理”后不再有开始提示，但列表会立即显示整理中状态，且结束后出现未读红点，反馈仍然完整。