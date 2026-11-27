# Design

## Context

聊天列表的滚动由 `ChatScreen.kt` 中一个 `LaunchedEffect(activeSessionId, scrollSignal, itemCount)` 控制，只在切换对话或 `scrollSignal` 变化时调用 `animateScrollToItem`。`scrollSignal` 目前由会话切换、本地发送、合并与引用定位等操作递增。当前对话的消息来自 `repository.messages(activeSessionId)`，本地发送与局域网同步写入都会让该 Flow 发出新列表，但同步写入不会递增 `scrollSignal`，因此列表不滚动。所需行为见 proposal.md 与 `specs/chat-auto-scroll/spec.md`。

## Goals / Non-Goals

**Goals:** 当前对话新增消息时自动跟随到最新内容，覆盖同步写入；不改变既有会话切换、发送定位与引用定位；实现集中在界面层。

**Non-Goals:** 不改变同步协议与写入流程；不引入“有新消息”悬浮提示或未读计数；不调整消息分组、日期头或气泡布局；不改变滚动动画样式。

## Decisions

### 1. 在列表侧检测消息数量增长

在 `ChatScreen` 的滚动 `LaunchedEffect` 中记录上一次的 `itemCount`：当 `itemCount` 大于上一次值且不是会话切换时，视为新增消息并滚动到末尾。会话切换时重置该记录，继续沿用既有“切到最新消息/引用目标”的逻辑；`scrollSignal` 变化仍优先处理。

选择界面侧检测而不是在 ViewModel 每次消息 Flow 更新时递增 `scrollSignal`，因为 Flow 更新还包含编辑、删除和格式变化，全部递增会导致需求之外的滚动；界面侧按 `itemCount` 增长判断更精确。

替代方案：仅在用户接近底部时自动滚动。需求明确要求有新内容即滚屏显示，因此不采用该限制。

### 2. 滚动目标与动画

新增消息时滚动到 `itemCount - 1`，即最后一条消息；不设置 `scrollTargetId`。连续同步到达时，每次增长都会启动新的 `animateScrollToItem`，Compose 会取消上一次动画并继续前往最新位置，最终停在最新消息。

本地发送已经通过 `scrollSignal` 与 `scrollTargetId` 定位到刚发送的消息，新增计数检测不会改变其结果；首屏加载时 `switched` 已触发滚动，与增长检测叠加也不会产生可见差异。

## Risks / Trade-offs

- [用户正在查看历史消息时被同步打断] → 需求要求有新内容就滚屏，接受该行为；后续如需可增加“仅在底部附近跟随”的开关。
- [同步批量写入导致多次滚动] → 动画会被后续目标覆盖，最终停在新消息；不做节流以保持实现简单。
- [编辑或删除被误判为增长] → 仅以 `itemCount` 增长判断，数量不变或减少不会触发。
