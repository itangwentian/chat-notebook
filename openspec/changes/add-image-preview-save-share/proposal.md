# Proposal

## Why

聊天气泡里的图片点开后只有查看和关闭，无法保存到相册，也不能转发到微信、QQ 等已安装应用；用户需要先退出预览再另找入口，体验割裂。

## What Changes

- 图片预览界面底部新增并排的两个图标按钮：保存到本地相册、转发到本机已安装应用。
- 保存：把预览中的原图写入系统相册；Android 10 及以上通过 MediaStore 直接写入，Android 8–9 先请求存储权限；成功、失败与拒绝授权都有中文提示，重复保存生成新图片。
- 转发：以单张图片调用系统分享选择器，列出可接收图片的已安装应用（如微信、QQ），授予临时读权限，不上传网络、不修改原图。
- 两个按钮仅在图片加载完成后可用；图片不可用时保持现有占位提示。
- 不改变附件存储、同步协议和整理结果相关能力。

## Capabilities

### New Capabilities

- `image-preview-actions`: 图片预览底部的保存到相册与转发操作，包括权限、反馈与失败处理。

### Modified Capabilities

（无）

## Impact

- 代码：`ChatScreen` 的 `AttachmentPreviewDialog` 布局与回调、`ChatActions` 注入操作、新增保存与分享辅助对象、两个图标矢量资源与中文字符串。
- 复用：现有 `message-share` FileProvider 与 `MessageSharing` 的缓存/授权方式。
- 兼容性：不新增网络权限；存储权限仅在 Android 8–9 声明与请求；原有预览关闭行为保留。
