# 工具包构建变体

| 变体 | 内置工具包 | 说明 |
| --- | --- | --- |
| core | tcpdump, arpspoof | 最小体积，其余工具包按需下载 |
| standard | core + aircrack | 常用审计工具随包提供 |
| professional | core + aircrack + hostapd + reaver | 全功能，体积最大 |

构建方式（默认使用 app/build.gradle 的单一配置）:

```bash
gradle :app:assembleDebug
```

如需区分变体，请在 app/build.gradle 的 `productFlavors` 中增加
`core` / `standard` / `professional` 三个 flavor，并复用
`app/src/main/assets/tool_packages/config.json` 中的包定义。
