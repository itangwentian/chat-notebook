# Design

## 排序策略

在 `MessageSharing` 中维护常用应用的包名顺序：

```kotlin
private val preferredPackages = listOf(
    "com.tencent.mm",             // 微信
    "com.tencent.mobileqq",       // QQ
    "com.ss.android.lark",        // 飞书
    "com.tencent.wework",         // 企业微信
    "com.alibaba.android.rimet",  // 钉钉
    "org.telegram.messenger",     // Telegram
    "com.whatsapp",               // WhatsApp
)
```

`findTargets` 取得目标后调用纯函数 `orderShareTargets`：

- 命中常用列表的目标按列表索引升序排列。
- 未命中的目标排在后面，按应用名称升序排列，避免依赖系统返回顺序。
- 排序使用包名而非组件名，同一应用有多个分享入口时仍保持相对稳定。

## 取舍

- 不记录个人使用频率：固定常用列表可预测、无需新增持久化；若后续需要“最近使用”，可在排序键前追加使用时间。
- 不改变目标过滤：不可导出、无权限、本应用自身仍会被排除。