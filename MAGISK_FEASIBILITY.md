# 能否用 Magisk 模块实现一加15帧注入 — 可行性结论

日期：2026-10-22
设备：OnePlus 15 / Android 16 / Kernel 6.12.23 / Root: KernelSU

## 结论

**“纯 Magisk 模块”（只改文件、写 sysfs、设属性）做不到。**
要实现注入，Magisk 模块里必须携带 **改过的驱动 .ko** 或 **自编译内核模块**。
Magisk 只是“搬运 + 开机加载”，它不能凭空创造驱动里不存在的发送实现。

## 实测证据

| 测试 | 结果 | 含义 |
| --- | --- | --- |
| 写 `inject/promiscuous/raw_mode` | 参数不存在 | 驱动没有开关可拨 |
| `NL80211_CMD_FRAME`（监听模式，信道6） | -95 ENOTSUPP | 上层发送接口不支持 |
| kallsyms 查 `wlan_hdd_cfg80211_ops` | 无 `mgmt_tx` 符号 | cfg80211 发送回调根本没注册 |
| 新建 `mon1` 监听接口 | -22 EINVAL | 驱动只允许一个监听 vdev |
| AF_PACKET 直接发 802.11 | send 返回 0，但 `tx_packets=0`、`tx_dropped++` | 帧进栈后被驱动丢弃 |
| 同时段 tcpdump | 正常收到 Beacon/Data | 接收链路没问题，纯发送缺失 |

## 设备提供的“可改造入口”

```
CONFIG_MODULE_SIG=y
# CONFIG_MODULE_SIG_FORCE is not set     -> 允许加载未签名模块
CONFIG_KPROBES=y
CONFIG_KALLSYMS_ALL=y
/proc/sys/kernel/modules_disabled = 0
/proc/sys/kernel/kptr_restrict = 2      -> 地址对用户态隐藏
/dev/mem, /dev/kmem                     -> 不存在
```

即：**Magisk 模块可以在开机时 insmod 一个自编译 .ko**（未签名可加载），
但不能靠用户态写内存绕过。

## 三种“Magisk 方案”的实际差别

### 方案 A：模块里写 sysfs / 属性（当前已试）
- 可行性：0
- 原因：驱动无对应参数；ENOTSUPP 来自代码路径缺失，不是权限或配置。

### 方案 B：模块替换打过补丁的 `qca_cld3_peach_v2.ko`
- 可行性：**理论可行，工程量最大**
- 前提：
  1. 拿到与 `6.12.23-android16-5-o-geb065695cf38-4k` 完全匹配的 vendor 驱动源码；
  2. 补上 monitor 发送实现（`mgmt_tx` 或 monitor vdev 的 xmit 放行）；
  3. 用相同 CLANG/vermagic 编译，替换 `/vendor/lib/modules/qca_cld3_peach_v2.ko`；
  4. Magisk magic mount 覆盖该文件，并处理驱动早已被 init 加载的顺序问题（需 rmmod/insmod 或提前挂载）。
- 风险：驱动 reload 已在测试中出现过整机重启；系统更新会覆盖。

### 方案 C：模块开机加载自编译内核模块（kprobe/内存补丁）
- 可行性：**有条件可行，但比方案 B 更难**
- 需要：
  1. 匹配内核源码/符号表，编译出可 insmod 的 .ko；
  2. 在运行时把 `wlan_hdd_cfg80211_ops.mgmt_tx` 指向自己实现的函数；
  3. 自己实现函数还得调用驱动内部的 `wma_mgmt_tx` 等未导出符号（需 kallsyms 查找）；
  4. 固件仍可能丢弃“非关联 STA”的帧，需要在 VDEV 层放行。
- 风险：KASLR、kptr_restrict=2、函数签名随版本变化，极易崩溃。

## 实际建议

1. 想要“能用”：OTG 外接 RTL8188FU/RTL8812AU，最稳。
2. 想要“内置网卡注入”：先做 **方案 B**（有源码才能谈），Magisk 模块只是最后的装载壳。
3. 当前 `wifitester-injection` 模块对注入无效，建议卸载，避免开机跑无用脚本。

## 卸载

```bash
adb shell su -c "rm -rf /data/adb/modules/wifitester-injection && reboot"
```
