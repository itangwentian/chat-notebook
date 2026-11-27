# message-bubble-formatting Specification

## Purpose

定义消息气泡的字体效果与颜色格式，包括加粗、倾斜、删除线的叠加规则、格式标识的编解码兼容性，以及格式在渲染与导出时的呈现方式。

## Requirements

### Requirement: 气泡字体效果可叠加

气泡的加粗、倾斜、删除线 SHALL 作为复选项，用户可以同时启用其中任意组合，也可以全部取消；颜色 MUST 保持互斥单选。

#### Scenario: 同时启用多个字体效果
- **WHEN** 用户先选择“加粗”，再选择“倾斜”
- **THEN** 气泡文字同时以粗体和斜体显示
- **AND** 两个菜单项都处于选中状态

#### Scenario: 取消其中一个字体效果
- **WHEN** 气泡已同时启用加粗与倾斜，用户再次选择“加粗”
- **THEN** 气泡只保留倾斜
- **AND** “加粗”菜单项变为未选中

#### Scenario: 与颜色组合
- **WHEN** 气泡已启用加粗与倾斜，用户选择红色
- **THEN** 气泡文字以粗体、斜体、红色显示

#### Scenario: 清除全部格式
- **WHEN** 用户取消最后一个已启用的字体效果且没有选中颜色
- **THEN** 气泡恢复无格式显示

### Requirement: 复合格式标识

格式标识 SHALL 支持同时包含多个字体效果；解析 MUST 接受任意字体效果组合与至多一种颜色，重复的字体效果或颜色 MUST 视为无效。

#### Scenario: 解析复合标识
- **WHEN** 读取到标识 `bold+italic+strikethrough+red`
- **THEN** 解析结果为三个字体效果叠加红色

#### Scenario: 兼容旧标识
- **WHEN** 读取到历史标识 `italic+red`
- **THEN** 解析结果仍为倾斜加红色

#### Scenario: 拒绝重复项
- **WHEN** 读取到标识 `bold+bold` 或 `red+green`
- **THEN** 解析结果为空

### Requirement: 复合格式的导出

导出 Markdown 时 SHALL 同时应用全部已选字体效果。

#### Scenario: 导出叠加格式
- **WHEN** 气泡已启用加粗与删除线
- **THEN** 导出内容中该段文字同时具有加粗与删除线标记