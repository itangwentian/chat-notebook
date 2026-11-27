# Proposal

## Why

当前启动时会先渲染“未选择记录目录”的提示，待已保存目录恢复完成才刷新出笔记，造成明显的界面闪烁；提醒时间滚轮无法首尾循环，选 00:00 附近的时刻需要长距离滑动；新建提醒默认只有 5 分钟且无法集中查看全部待触发提醒，容易漏掉散落在不同对话里的提醒。

## What Changes

- 启动恢复优化：已保存记录目录时先显示加载态，恢复完成后再展示笔记；只有从未选择目录或恢复失败时才展示“选择记录目录”提示。
- 提醒时间滚轮支持首尾循环：小时从 23 继续滑到 00，分钟从 59 继续滑到 00，反方向同理，滚动结束后仍吸附到整行。
- 新建提醒的默认时间改为当前时间 10 分钟后；本地日程建议已给出有效时间时仍优先使用建议时间。
- 主界面右上角在存在待触发提醒时显示闹钟按钮；点击后逐行展示全部待触发提醒（时间、消息内容或会话标题），过长内容截断，右侧提供快捷取消，点击行跳转到对应气泡；取消后列表实时更新，全部取消后按钮隐藏。

## Capabilities

### New Capabilities

- `app-startup`: 应用启动时恢复已保存记录目录的加载态与目录选择提示条件。
- `reminder-experience`: 提醒创建时的默认时间与时间滚轮交互、待触发提醒的集中预览、快捷取消与跳转。

### Modified Capabilities

无。

## Impact

- UI：`ChatScreen.kt` 的顶部栏、空态与目录提示、`ReminderDialog` 与时间滚轮、新增提醒预览面板。
- 状态与逻辑：`ChatViewModel.kt` 的 `ChatUiState`、提醒列表与跳转动作；`MainActivity.kt` 的 `ChatActions` 接线。
- 数据：`ChatRepository` 增加全局待触发提醒查询；复用 `RecordDao.observeScheduledReminders()`，必要时补充会话标题与消息预览的只读查询；不改变提醒文件格式与同步协议，不新增重复提醒。
- 验证：`gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`，并在模拟器上从实际入口验证启动、滚轮、默认时间与提醒预览。