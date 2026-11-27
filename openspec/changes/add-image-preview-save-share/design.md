# Design

## Context

图片预览由 `ChatScreen.kt` 的私有 `AttachmentPreviewDialog` 用 `AlertDialog` 渲染：标题“图片预览”、按最长边 1600 采样解码的 `Image`、右下角“关闭”按钮。附件 URI 由 `ChatActions.attachmentUri(relativePath)` 解析。项目已有 `MessageShareFileProvider`（authority `${applicationId}.message-share`）与 `MessageSharing`，把附件复制到 `cache/shared-messages` 后以 FileProvider URI 分享，并清理一天前的缓存。minSdk 为 26，targetSdk 为 35。

## Goals / Non-Goals

- 在预览底部提供保存与转发两个图标操作，覆盖权限、失败与反馈。
- 复用现有 FileProvider 与分享缓存，不新增网络能力，不改附件存储与同步。
- 不改变图片预览的关闭方式与解码尺寸。

## Decisions

### 预览布局

用 `Dialog` + `Surface` 自定义预览：顶部一行标题与关闭图标，中部图片（保持最长边 1600、`ContentScale.Fit`），底部一行等宽并排的 `IconButton`：

- 保存：新增矢量资源 `res/drawable/ic_save_to_gallery.xml`，`contentDescription` 为“保存到相册”，`testTag("preview_save")`。
- 转发：新增矢量资源 `res/drawable/ic_share.xml`，`contentDescription` 为“转发”，`testTag("preview_share")`。

不使用纯文字按钮；图标按钮尺寸不小于 48dp。按钮 `enabled = 图片解码成功`，图片不可用或加载中时禁用。附件属性与 URI 仍由调用处传入，关闭行为保持点击外部、返回键与关闭图标。

### 操作入口与反馈

`ChatActions` 增加 `saveImage` 与 `shareImage` 两个回调，由 `MainActivity` 注入；预览对话框只负责权限门槛与触发。保存/转发在协程中执行，完成后用 Toast 提示“已保存到相册”“保存失败”“转发失败”“需要存储权限才能保存”等中文文案。Toast 只在注入层出现，便于 Compose 测试只断言按钮与回调。

### 保存到相册

新增 `GalleryImageWriter`（`ui` 包）：

- 文件名 `chat_notebook_yyyyMMdd_HHmmss.<ext>`，扩展名取自 `relativePath`，缺失时按 MIME 推断（image/jpeg → jpg）。
- Android 10 及以上：`MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 插入，`RELATIVE_PATH = Pictures/ChatNotebook`，先置 `IS_PENDING = 1`，复制完成后清除；失败删除占位行。
- Android 8–9：使用同一 MediaStore 插入，需 `WRITE_EXTERNAL_STORAGE`；在清单声明 `maxSdkVersion="28"`，预览对话框用 `RequestPermission` 启动器先申请，拒绝直接提示，不进入写入。
- 每次保存都新建条目，不覆盖已有图片。

### 转发到已安装应用

新增 `ImageSharing.prepare(context, uri, mimeType, fileName)`：复用 `cache/shared-messages` 目录（沿用现有 FileProvider 路径与一天清理规则），复制为 `image-<uuid>.<ext>`，构造单图 `ACTION_SEND`，设置 `type`、`EXTRA_STREAM`、`ClipData` 与 `FLAG_GRANT_READ_URI_PERMISSION`，由界面以 `Intent.createChooser` 打开系统选择器，列表自然包含微信、QQ 等可接收图片的应用。读取失败或无法复制时抛出并提示，不打开选择器。

### 边界

- 图片未加载完成或文件缺失：按钮禁用，保留现有“附件不可用”提示。
- 多选模式禁用预览的既有行为不变。
- 保存的是原始字节，不重新压缩；分享复制的是原图内容。

## Risks / Trade-offs

- [Android 8–9 权限拒绝] → 明确提示，不写入，不影响预览。
- [大图复制耗时] → 在 IO 协程执行，按钮在操作期间禁用防止重复触发。
- [系统分享目标差异] → 使用系统选择器，避免维护应用白名单。

## Migration Plan

无数据迁移。仅新增资源、字符串与操作入口。

## References

- `app/src/main/java/com/chatnotebook/app/ui/ChatScreen.kt`（`AttachmentPreviewDialog`）
- `app/src/main/java/com/chatnotebook/app/ui/MessageSharing.kt`
- `app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/message_share_paths.xml`
