# Tasks

## 1. 数据模型

- [x] 1.1 `BubbleFormat` 改为 `styles: Set<BubbleTextStyle>`，`id` 按固定顺序拼接，`toggleStyle` 增删集合成员。
- [x] 1.2 `BubbleFormat.fromId` 支持多个字体效果，重复项与未知片段视为无效，保持旧标识兼容。

## 2. 渲染与导出

- [x] 2.1 气泡与合并段落渲染同时应用所有已选字体效果。
- [x] 2.2 格式菜单按集合成员显示选中态，点击只切换对应效果。
- [x] 2.3 `MessageMarkdown` 叠加包裹所有字体效果，再应用颜色。

## 3. 测试与验证

- [x] 3.1 更新单测：复合标识解析、重复项拒绝、字体效果叠加与取消、导出叠加格式。
- [x] 3.2 运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- [x] 3.3 运行 `npx --yes @fission-ai/openspec@2.0.0 validate bubble-style-multiselect --strict --no-interactive`。

## 实施记录

- 3.1 更新 `MessageInteractionTest`：复合标识解析与顺序归一、重复项拒绝、字体效果独立增删；`SessionDocumentServiceTest`：导出同时应用加粗与删除线。
- 3.2 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功，32 个测试套件、207 个测试全部通过。
- 3.3 OpenSpec strict validate 通过（Change 'bubble-style-multiselect' is valid）。
- 未在模拟器上人工确认多选叠加后的实际显示效果。