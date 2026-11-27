# Tasks

## 1. 排序状态

- [x] 1.1 Goal：界面状态；Requirements：`ChatUiState` 增加默认 `Created` 的排序模式，会话 Flow 按模式降序重排；Constraints：不改数据库查询与活动会话选择；Acceptance Criteria：ViewModel 测试覆盖创建/修改两种排序及切换。
- [x] 1.2 Goal：切换反馈；Requirements：点击在两种模式间切换并短暂提示当前模式；Constraints：侧栏打开时提示需可见，使用 Toast 而非被遮挡的 Snackbar；Acceptance Criteria：测试覆盖按钮点击触发切换。

## 2. 有效修改时间

- [x] 2.1 Goal：补齐 `updatedAt`；Requirements：发送、编辑、删除、合并、格式修改更新所属会话，重命名沿用；打开只更新 `lastOpenedAt`；Constraints：不改变消息与快照结构；Acceptance Criteria：代码检查与模拟器验证“查看不改顺序，发送后上浮”。

## 3. 侧栏按钮

- [x] 3.1 Goal：无文字醒目按钮；Requirements：历史对话标题右侧填充图标按钮，创建模式显示时钟、修改模式显示铅笔，均含向下箭头与无障碍描述；Constraints：不新增文字标签；Acceptance Criteria：Compose 测试确认按钮存在且可点击。

## 4. 验证与交付

- [x] 4.1 Goal：构建与测试；Requirements：运行 `testDebugUnitTest lintDebug assembleDebug`；Constraints：lint 不新增错误；Acceptance Criteria：全部通过。
- [x] 4.2 Goal：模拟器验证；Requirements：验证默认创建排序、切换修改排序、打开不改顺序、发送后上浮；Constraints：使用现有记录目录；Acceptance Criteria：逐项记录。
- [x] 4.3 Goal：APK；Requirements：复制 debug APK 并记录 SHA-256；Constraints：不修改无关功能；Acceptance Criteria：APK 可安装。

## 实施记录

- 构建与测试：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 全部通过；新增 ViewModel 排序切换测试与侧栏按钮 Compose 测试。
- 模拟器验证（Pixel 6 API 35）：
  - 默认按创建时间降序，新建对话位于旧对话上方；
  - 点击按钮切换为铅笔图标，旧对话发送消息后移动到列表顶部；
  - 打开较新的对话后，修改时间排序顺序保持不变；
  - 再次点击恢复时钟图标，新对话回到顶部；
  - 按钮为填充底色圆形图标，无文字，图标与无障碍描述正确。
- 说明：侧栏打开时 Snackbar 会被抽屉遮挡，因此切换反馈改为 Toast；排序不持久化，重启回到创建时间。
- APK：`chat_notebook-debug.apk`，SHA-256 `54F20A2FE750D4689568D54C68DF03CA9C6195DB4FB971D2D4A357607C55D172`。
