# 一加15 Magisk 注入模块实测报告

日期：2026-10-22
设备：OnePlus 15 (PLK110) / Android 16 / 内核 6.12.23 / Root: KernelSU
驱动：qca_cld3_peach_v2 (QCACLD 3.0)

## 一、模块安装情况

- 模块已刷入：`/data/adb/modules/wifitester-injection`
- 属性已生效：`persist.wifitester.injection.enabled=1`、`ro.wifitester.supported=1`
- SELinux：仍为 `Enforcing`
- 结论：Magisk 层加载成功，但**注入功能未生效**

## 二、关键事实：驱动没有注入参数

模块会写这些 sysfs：
`inject`、`promiscuous`、`raw_mode`、`frame_injection`

实测驱动实际暴露的参数只有：

```
con_mode
con_mode_epping
con_mode_ftm
country_code
enable_11d
enable_dfs_chan_scan
fwpath
pcie_gen_speed
prealloc_disabled
qdf_log_dump_at_kernel_enable
qdf_log_flush_timer_period
timer_multiplier
```

因此模块对这些不存在参数的写入全部是空操作，不会改变硬件行为。

## 三、真实注入测试（nl80211 底层）

自编工具 `nlinject`，直接发送 `NL80211_CMD_FRAME`（比 aireplay-ng 更底层）：

| 场景 | 结果 |
| --- | --- |
| 接口启用前 | -100 ENETDOWN（接口 DOWN，属正常） |
| con_mode=4 监听模式，wlan0 UP，信道6 | **-95 ENOTSUPP（Operation not supported）** |
| 新建独立 mon1 接口 | -22 EINVAL（驱动拒绝创建第二个监听接口） |
| con_mode=8 | 写入被拒，接口消失，驱动异常，最终重启恢复 |

同一时段抓包对照（说明监听接收正常）：

```
22:24:03 Beacon (ZHY) CH: 6
22:24:03 Beacon (ChinaNet-kqaU) CH: 6
22:24:03 Data DA:01:00:5e:7f:ff:fa BSSID:3c:6a:48:33:cd:b9
```

结论：**能收不能发**。`NL80211_CMD_FRAME` 在驱动/固件层直接返回 ENOTSUPP，Root 与 Magisk 无法改变该返回值。

## 四、原因分析

`con_mode=4` 只把 VDEV 切到 MONITOR 类型，固件监控路径是 **capture-only**。
真正的注入还需要：

1. 固件 WMI 服务声明 `monitor/inject` 能力，或驱动强制置位；
2. TX 侧允许非关联 STA 的原始帧（`wlan_hdd_tx_rx.c` 中的授权检查）；
3. VDEV TX encap 设为 RAW；
4. 关闭固件对未知/非加密帧的丢弃。

这些都在驱动/固件内部，不是 sysfs 参数，故 Magisk 模块层面的文件写入无效。

## 五、可行路线

### A. 打驱动补丁（唯一能在本机实现注入的办法）
- 需要与 `6.12.23-android16-5-o-geb065695cf38-4k` 完全匹配的 vendor 驱动源码；
- 修改 `wlan_hdd_tx_rx.c`、`wmi_unified_tlv.c`、VDEV create 的 encap/drop 参数；
- 交叉编译出 `qca_cld3_peach_v2.ko`，校验 vermagic，替换 `/vendor/lib/modules/`；
- 风险高：驱动签名校验、ABO/vbmeta、系统更新覆盖。

### B. OTG 外接网卡（推荐）
- RTL8188FU / RTL8812AU 等，配合 rtl8xxxu 模块；
- 监听 + 注入均可用，成功率最高，不动内置驱动。

## 六、当前设备状态

- 已通过重启恢复：`wlan0` 为 managed，已连回 `ChinaNet-403-5G`，`con_mode=0`。
- 测试工具：`/data/local/tmp/nlinject`（可保留用于复测）。

## 七、建议

1. 现有 Magisk 模块对注入无效，建议在 Magisk 中卸载，避免误导和开机执行无用脚本。
2. 若要继续，优先走 OTG 方案；若坚持内置网卡注入，下一步是获取匹配源码做驱动补丁，而不是再加 Magisk 模块。
