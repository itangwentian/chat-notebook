# Proposal

## Why

局域网同步的“扫码配对”当前使用系统相机预览缩略图进行识别，返回的位图分辨率很低，屏幕上的配对二维码经常无法被 ZXing 解出，界面只提示“无法识别二维码”，用户无法完成配对。现有实现还把“没有识别到二维码”和“二维码内容无效或过期”混为同一条提示，无法指导用户下一步操作。

## What Changes

- 相机扫码改为拍摄全分辨率照片后再识别，避免预览缩略图分辨率不足。
- 识别流程增加旋转重试与 `TRY_HARDER` 等解码提示，提升对屏幕二维码的识别率；图片识别入口复用同一套解码逻辑。
- 区分并明确失败提示：未识别到二维码、二维码内容无效、配对版本不兼容或令牌过期时给出不同中文提示，并提示可改用“从图片识别”。
- 拍照产生的临时文件在识别结束后删除，不进入记录目录。
- 配对成功、一次性令牌消费与长期信任规则保持不变，不改变协议与安全校验。

## Capabilities

### New Capabilities

- `lan-pairing-scan`: 配对二维码的相机扫描与图片识别可靠性，以及识别失败时的可见提示。

### Modified Capabilities

（无）

## Impact

- 代码：`app/src/main/java/com/chatnotebook/app/ui/LanSyncPanel.kt`、`app/src/main/java/com/chatnotebook/app/data/sync/PairingQr.kt`。
- 资源：FileProvider 路径增加配对拍照缓存目录，`app/src/main/res/xml/message_share_paths.xml`。
- 测试：`PairingQrTest` 增加旋转、低质量与无效内容用例；扫码界面提示通过 Compose 或模拟器验证。
- 依赖：沿用现有 ZXing，不新增第三方库；相机权限申请流程保持现状。
- 兼容性：配对协议、二维码内容格式、信任存储与同步行为不变。
