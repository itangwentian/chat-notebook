# Tasks

## 1. 预览界面

- [x] 1.1 把 `AttachmentPreviewDialog` 改为自定义 `Dialog`：标题与关闭图标、图片、底部并排两个图标按钮，新增 `ic_save_to_gallery`、`ic_share` 矢量资源与中文 `contentDescription`/字符串。
- [x] 1.2 按钮仅在图片解码成功后可用；加载中与不可用状态保持现有表现；补充 `testTag("preview_save")`、`testTag("preview_share")`。

## 2. 操作接线

- [x] 2.1 `ChatActions` 增加 `saveImage`、`shareImage` 回调并在 `MainActivity` 注入：协程执行、Toast 反馈“已保存到相册”“保存失败”“转发失败”等。
- [x] 2.2 Android 8–9 保存前用 `RequestPermission` 申请 `WRITE_EXTERNAL_STORAGE`，清单声明 `maxSdkVersion="28"`；拒绝时提示且不写入。

## 3. 保存与转发实现

- [x] 3.1 新增 `GalleryImageWriter`：按时间戳命名、按原扩展名或 MIME 推断格式，Android 10+ 用 `IS_PENDING` 写入 `Pictures/ChatNotebook`，失败清理占位行，重复保存不覆盖。
- [x] 3.2 新增 `ImageSharing.prepare`：复用 `cache/shared-messages` 与现有 FileProvider，复制单张图片并构造带临时读权限的 `ACTION_SEND`，由界面打开系统选择器；附件缺失时提示失败。

## 4. 测试与验证

- [x] 4.1 单元测试：文件名与扩展名推断、分享 Intent 的 action/type/stream/flag、附件缺失的失败路径；Compose 测试：预览显示两个图标按钮、图片不可用时禁用、点击触发回调。
- [x] 4.2 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，执行 `npx --yes @fission-ai/openspec@2.0.0 validate add-image-preview-save-share --strict --no-interactive`，记录结果。
- [ ] 4.3 在模拟器打开带图片的对话预览：保存后相册出现图片、重复保存生成新图；点转发出现系统选择器并能选择目标应用；权限拒绝路径无法在 Android 10+ 模拟器复现时如实记录。

## 实施记录

- 2026-11-23：`AttachmentPreviewDialog` 改为自定义 `Dialog` + `Surface`：标题、关闭图标、图片与底部并排两个 `IconButton`；新增 `ic_save_to_gallery`、`ic_share`、`ic_close` 矢量资源与中文 `contentDescription`；按钮仅在图片解码成功后可用，带 `preview_save`、`preview_share`、`preview_close` 标签。
- `ChatActions` 增加 `saveImage`、`shareImage`，由 `MainActivity` 注入：协程执行，Toast 提示“已保存到相册”“保存失败”“转发失败”；Android 10 以下先申请 `WRITE_EXTERNAL_STORAGE`，拒绝时提示“需要存储权限才能保存”，清单声明 `maxSdkVersion="28"`。
- 新增 `GalleryImageWriter`：`chat_notebook_yyyyMMdd_HHmmss.<ext>` 命名，扩展名取路径或按 MIME 推断；Android 10+ 使用 `RELATIVE_PATH=Pictures/ChatNotebook` 与 `IS_PENDING`，失败删除占位行；Android 8–9 写入公共图片目录并以 `_n` 后缀避免覆盖。
- 新增 `ImageSharing.prepare`：复用 `cache/shared-messages` 与 `.message-share` FileProvider，清理一天前缓存后复制单图，构造单图 `ACTION_SEND`（type、`EXTRA_STREAM`、`ClipData`、读权限），由界面打开系统选择器。
- 测试：`ImagePreviewActionsTest` 覆盖文件名/扩展名推断、分享 Intent 的 action/type/stream/flag 与附件缺失失败；`ChatScreenTest` 覆盖预览两个图标按钮、点击回调、不可用时禁用与关闭。
- 未执行：模拟器上保存到相册、重复保存、系统选择器与 Android 8–9 权限拒绝路径（4.3）。
