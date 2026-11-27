# Design

## Context

`MainActivity` 用 Compose 状态切换根级界面：隐私同意页、设置页（`settingsOpen`）、整理结果页（`resultSessionId`）和对话界面。当前没有注册任何返回拦截，因此非对话界面按返回会走 Activity 默认行为并结束应用。设置页内部还有模型配置表单与法律文档两层局部状态，均由 `SettingsScreen` 自己持有。

对话界面内的会话抽屉、对话框、底部面板和图片预览由 Material 3 / Compose 组件自身处理返回关闭。Android 系统返回键与返回手势在 Compose 中统一通过 `BackHandler` 接入。

## Goals / Non-Goals

**Goals：**

- 根级界面按返回时逐级回到对话界面。
- 设置页内部的表单与法律文档先于设置页本身返回。
- 对话界面保持按返回退出应用。
- 返回键、返回手势与顶栏返回入口行为一致。

**Non-Goals：**

- 不引入 Navigation Compose 或路由栈，不重构现有状态驱动的界面切换。
- 不修改任何顶栏关闭按钮、对话框按钮或数据状态。
- 不开启 Android 13+ 预测性返回的系统级动画开关。
- 不改变隐私同意页根界面的返回行为（无上级界面，保持结束应用）。

## Decisions

### 根级返回统一注册在 MainActivity

在 `MainActivity` 的已同意分支注册两个 `BackHandler`：`settingsOpen` 时执行 `closeSettings()`，整理结果页打开时执行 `closeOrganizationResults()`。两者互斥且只在对应界面可见时启用。

为避免把判断逻辑散落在 `setContent` 中并便于测试，抽出一个无副作用的 `AppBackHandler` 可组合函数，参数为 `settingsOpen`、`resultOpen` 与两个回调，由 `MainActivity` 调用。

备选方案：在 Activity 中重写 `onBackPressed`。状态保存在 Compose 的 `StateFlow` 中，Activity 无法直接感知，且需要额外桥接，故不采用。

### 设置页内部层级自行处理返回

`SettingsScreen` 注册自己的 `BackHandler`：

- 法律文档打开时，返回只关闭文档并回到设置列表。
- 模型配置表单打开时，返回只关闭表单并回到设置列表。
- 两者都未打开时不注册，让根级处理器关闭设置页。

Compose 中后组合的 `BackHandler` 优先，`SettingsRoute` 位于 `MainActivity` 根级处理器之后，因此内层处理器优先，符合“逐级返回”。

### 对话界面不拦截返回

对话界面不注册 `BackHandler`，保留 Activity 默认的结束行为。抽屉、对话框、底部面板和图片预览依赖组件自带的返回处理，仍表现为“先关闭浮层”。多选、编辑、引用属于对话界面内的行内状态，不视为独立层级，按用户要求此时返回仍退出应用。

### 隐私同意页

隐私同意页的根界面出现在进入应用之前，没有上一级界面，保持默认返回结束。若用户从同意页打开隐私政策或用户协议，文档页属于其下一级界面，返回先关闭文档并回到同意页，不退出应用。

## Risks / Trade-offs

- [多个 BackHandler 同时启用导致顺序不符] → 根级与设置页处理器按状态互斥启用，内层优先，测试覆盖表单与文档两种层级。
- [顶栏返回与系统返回重复触发] → `closeSettings`、`closeOrganizationResults`、`closeForm` 均为幂等状态更新，重复调用无副作用。
- [Android 13+ 预测性返回表现不一致] → 本次不开启系统动画开关，`BackHandler` 仍能正确接管返回；动画统一留待后续版本。
- [对话界面误退出] → 行内编辑/多选状态按需求不额外拦截，若后续需要“先取消编辑”可另立变更。

## Migration Plan

无需数据迁移。仅新增返回处理逻辑与测试。回滚时移除新增的 `BackHandler` 与 `AppBackHandler` 即可恢复原有行为。

## References

- `openspec/changes/fix-back-navigation-levels/proposal.md`
- `openspec/changes/fix-back-navigation-levels/specs/app-navigation/spec.md`
