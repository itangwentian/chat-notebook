# Design

## Context

动机见 proposal.md。当前实现位于 `LanSyncPanel.kt`：相机入口使用 `ActivityResultContracts.TakePicturePreview()` 取得预览缩略图，直接交给 `PairingQr.scan`；图片入口先解码本地图片再调用同一方法。`PairingQr.scan` 只用默认配置的 `QRCodeReader` 解码一次，任何失败都会落到同一条“无法识别二维码”提示。FileProvider 目前只暴露 `cache/shared-messages/`，配对协议与 `PairingOffer` 内容格式保持 v2 不变。

## Goals / Non-Goals

**Goals:** 在正常拍摄条件下稳定识别另一台设备屏幕上的配对二维码；区分识别失败原因并给出可操作提示；相机与图片入口共用同一套解码和校验；不新增第三方依赖。

**Non-Goals:** 不做应用内实时相机预览与自动识别（CameraX/MLKit）；不改配对协议、二维码内容、长期信任和同步流程；不优化二维码视觉样式；不做连续扫描或多码识别。

## Decisions

### 1. 相机改为拍摄完整分辨率照片

使用 `ActivityResultContracts.TakePicture()`，把照片写入应用 cache 的 `pairing/` 子目录，通过现有 FileProvider 暴露给系统相机，识别完成后删除。相比 `TakePicturePreview`，完整分辨率照片能让二维码模块保持足够像素；相比引入 CameraX 实时分析，不增加依赖和相机生命周期管理。

替代方案：CameraX + ImageAnalysis 连续扫描体验更好，但需要新增 camera 依赖、权限与旋转处理，超出本次缺陷修复范围；保留当前预览缩略图无法解决分辨率不足的根因。

需要在 `res/xml/message_share_paths.xml` 增加仅指向 `cache/pairing/` 的路径，不暴露记录目录。`ActivityNotFoundException` 或相机启动失败时提示相机不可用并建议从图片识别。

### 2. 解码流程增强

`PairingQr.scan` 在解码前将位图限制到最长边 1600 像素，使用 `QRCodeReader.decode(BinaryBitmap, hints)`，hints 包含 `TRY_HARDER`、`POSSIBLE_FORMATS = QR_CODE` 和 UTF-8 字符集；首次失败后依次旋转 90、180、270 度重试。图片入口复用同一解码函数，避免两套逻辑行为不一致。

替代方案：只在相机路径加旋转、图片路径保持原样，会让两个入口表现不同，且图片识别同样存在旋转问题，因此统一处理。

### 3. 识别失败分类

失败原因通过类型化异常传递，而不是匹配异常文本：

- 解码器未找到二维码 → 提示“未识别到二维码，请重试或从图片识别”。
- 内容不是配对码 → 提示“二维码内容无效，请使用本应用的配对二维码”。
- 协议版本不兼容 → 沿用“二维码配对版本不兼容，请更新两台设备”。
- 配对令牌过期或对端拒绝 → 由现有配对流程提示“配对令牌无效”，并建议重新显示二维码。
- 相机不可用或权限被拒绝 → 保留现有提示，并引导从图片识别。

失败时不得调用 `manager.join`，已有配对状态不变。

### 4. 临时文件生命周期

每次拍照生成独立缓存文件，识别在 `finally` 中删除，成功、失败或用户取消都不保留。缓存文件名不进入记录目录，也不参与同步清单。

## Risks / Trade-offs

- [系统相机照片对焦失败或二维码不在画面内] → `TRY_HARDER` 与旋转重试只能改善解码，仍失败时明确提示重拍或改用图片识别。
- [部分设备没有可处理拍照 Intent 的相机应用] → 捕获启动异常并提示使用图片识别。
- [大图内存压力] → 先按最长边 1600 采样解码，再旋转与识别；旋转副本使用后及时回收。
- [FileProvider 新增路径误暴露文件] → 只添加 `cache/pairing/`，与记录目录隔离。
- [拍照 Intent 在已有 CAMERA 权限声明时行为差异] → 保留现有运行时权限申请与拒绝提示，不改变权限模型。
