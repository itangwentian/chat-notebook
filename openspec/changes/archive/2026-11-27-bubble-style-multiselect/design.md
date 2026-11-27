# Design

## 模型

字体效果从单值改为集合，颜色保持可选单值：

```kotlin
data class BubbleFormat(
    val styles: Set<BubbleTextStyle> = emptySet(),
    val color: BubbleColor? = null,
)
```

`id` 按固定顺序拼接：先按枚举顺序输出已选字体效果（`bold`、`italic`、`strikethrough`），再输出颜色。固定顺序保证同一组合只对应一个字符串，便于去重、比较与同步。

## 解析与兼容

`BubbleFormat.fromId` 逐段解析：

- 字体效果允许出现多次（最多三个不同项），重复同一项视为无效。
- 颜色最多出现一次，重复视为无效。
- 未知片段或空字符串返回 `null`。
- 旧标识（`bold`、`italic+red`）天然兼容，无需迁移。

## 交互

- `toggleStyle`：目标效果已在集合中则移除，否则加入；结果为空且无颜色时返回 `null`（表示清除格式）。
- `toggleColor`：行为不变，颜色仍互斥。
- 格式菜单按“该字体效果是否为集合成员”判断选中态，点击只切换该项，不影响其他字体效果。

## 渲染与导出

- Compose 渲染同时判断三个成员，分别设置 `FontWeight.Bold`、`FontStyle.Italic`、`TextDecoration.LineThrough`。
- 导出 Markdown 按 Bold、Italic、StrikeThrough 的顺序逐层包裹文本，再包裹颜色 span，多行仍逐行包裹。

## 取舍

- 颜色仍单选：需求只要求三种字体效果复选，保留颜色互斥可以避免配色冲突。
- 不引入新的持久化字段：格式仍以 id 字符串保存在 reactions 的 emoji 字段中，改动只发生在编解码与展示层。