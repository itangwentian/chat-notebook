# Design

## Context

多选状态由 `ChatViewModel` 管理，现有选择框把所选消息复制到应用内对话。附件文件位于 Storage Access Framework 记录目录，通过仓库解析 URI。新行为见 `specs/external-message-sharing/spec.md`。

## Goals / Non-Goals

- 在圆角底部面板中展示内容预览、横排外部应用图标和应用内对话列表，复用多选与错误提示。
- 直接打开接收应用的分享入口。接收格式与后续确认流程由目标应用决定。
- 保持记录数据格式，使用仅涵盖分享副本的文件提供器。

## Decisions

- ViewModel 从当前对话取得选中且未删除的消息，以创建时间和 ID 排序。面板用相同的动作和 MIME 类型发现当前可用的外部 Activity，查询范围通过清单限定为分享 Intent。查询阶段只读取应用信息。用户点选目标后，Activity 准备文件并向所选组件发送显式 Intent，关闭本应用的面板并保留多选。
- 纯正文使用 `text/plain`，单附件用 `ACTION_SEND`，多附件用 `ACTION_SEND_MULTIPLE`。同类附件使用最具体的共同 MIME 类型，图片与语音混合时使用 `*/*`。正文通过 `EXTRA_TEXT` 传递。
- 将所选附件流式复制到单次分享专属的私有缓存目录，通过 `FileProvider`、`ClipData` 和临时只读标志交接。原记录目录 URI 的授权由 SAF 提供器控制，自有分享副本可明确限制授权范围。下一次准备附件时清除超过一天的分享缓存，准备失败时清除本次副本。

## Risks / Trade-offs

- 接收应用可能只接受部分 MIME 类型或忽略附件请求中的正文。转发面板根据请求类型展示兼容目标，接收结果由目标应用处理。
- 附件缺失或目录权限失效会中断准备。应用提示错误并保留多选供用户调整。

## Migration Plan

按任务接入外部分享并验证。现有记录可以直接使用。
