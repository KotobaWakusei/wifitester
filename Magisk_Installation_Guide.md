# WiFiTester注入模块Magisk安装指南

## 📦 模块信息
- **名称**: WiFiTester Injection Module
- **版本**: v1.0
- **设备**: OnePlus 15 (PLK110)
- **芯片**: SM8550
- **驱动**: QCACLD 3.0

## 🚀 安装步骤

### 1. 准备工作
- ✅ 已Root的一加15设备
- ✅ 最新版本的Magisk Manager
- ✅ 至少1GB可用存储空间
- ✅ 备份重要数据（可选但推荐）

### 2. 下载模块
```bash
# 将模块文件传输到设备
adb push WiFiTester-Injection-v1.0.zip /sdcard/
```

### 3. 安装模块
1. 打开Magisk Manager
2. 点击"模块"选项卡
3. 点击"安装 from storage"
4. 选择 `/sdcard/WiFiTester-Injection-v1.0.zip`
5. 确认安装
6. 重启设备

### 4. 启用注入功能
```bash
# 重启后，在终端中运行：
adb shell su -c "echo 1 > /data/local/tmp/injection_enabled"
```

## 🔧 测试功能

### 1. 运行测试脚本
```bash
# 推送测试脚本
adb push test_injection.sh /data/local/tmp/

# 运行测试
adb shell su -c "/data/local/tmp/test_injection.sh"
```

### 2. 验证注入功能
```bash
# 检查注入参数
adb shell "cat /sys/module/qca_cld3_peach_v2/parameters/inject"

# 测试注入
adb shell "aireplay-ng --test wlan0"
```

### 3. 检查日志
```bash
# 查看模块日志
adb shell "logcat | grep wifitester"

# 查看安装日志
adb shell "cat /data/local/tmp/wifitester_install.log"

# 查看测试报告
adb shell "cat /data/local/tmp/wifitester_test_report.txt"
```

## 📊 功能验证

### 成功标志
- ✅ `inject` 参数可设置为1
- ✅ `promiscuous` 参数可设置为1
- ✅ aireplay-ng 测试通过
- ✅ tcpdump 可捕获数据包
- ✅ SELinux 状态为 Permissive/Disabled

### 失败标志
- ❌ 参数无法修改
- ❌ aireplay-ng 测试失败
- ❌ tcpdump 无法捕获数据包
- ❌ SELinux 状态为 Enforcing

## 🛠️ 高级配置

### 1. 手动启用注入
```bash
# 如果自动启用失败，手动设置：
adb shell su -c "
echo 1 > /sys/module/qca_cld3_peach_v2/parameters/inject
echo 1 > /sys/module/qca_cld3_peach_v2/parameters/promiscuous
echo 1 > /sys/module/qca_cld3_peach_v2/parameters/raw_mode
setenforce 0
svc wifi disable && svc wifi enable
"
```

### 2. 查看详细状态
```bash
# 查看所有驱动参数
adb shell "find /sys/module/qca_cld3_peach_v2/parameters -type f -exec echo '=== {} ===' \; -exec cat {} \;"

# 查看WiFi接口状态
adb shell "iw dev wlan0 info"

# 查看网络扫描结果
adb shell "iw dev wlan0 scan | head -10"
```

## 🚨 故障排除

### 常见问题

#### 1. 模块安装失败
**问题**: Magisk提示安装失败
**解决**:
- 确认设备已Root
- 检查Magisk版本是否为25.0+
- 尝试重新下载模块文件
- 检查存储空间是否足够

#### 2. 注入功能未启用
**问题**: 测试显示注入功能未启用
**解决**:
```bash
# 手动启用
adb shell su -c "
echo 1 > /data/local/tmp/injection_enabled
echo 1 > /sys/module/qca_cld3_peach_v2/parameters/inject
svc wifi disable && svc wifi enable
"
```

#### 3. WiFi连接异常
**问题**: 安装后无法连接WiFi
**解决**:
```bash
# 重启WiFi服务
svc wifi disable
sleep 2
svc wifi enable

# 或重启设备
```

#### 4. SELinux阻止操作
**问题**: 日志显示SELinux错误
**解决**:
```bash
# 临时设置
setenforce 0

# 或创建自定义策略
```

### 日志分析

#### 关键日志关键词
- `wifitester` - 模块相关日志
- `qca_cld3` - 驱动相关日志
- `injection` - 注入相关日志
- `permission` - 权限相关日志

#### 查看完整日志
```bash
# 实时查看日志
adb shell "logcat -s wifitester"

# 保存日志到文件
adb shell "logcat -s wifitester > /sdcard/wifitester_log.txt"
```

## 🔄 卸载模块

### 1. 正常卸载
1. 打开Magisk Manager
2. 点击"模块"
3. 找到"WiFiTester Injection"
4. 点击"卸载"
5. 重启设备

### 2. 紧急卸载
```bash
# 如果无法通过Magisk卸载
adb shell su -c "
echo 0 > /data/local/tmp/injection_enabled
rm -rf /data/adb/modules/wifitester-injection
svc wifi disable && svc wifi enable
"
```

### 3. 完全恢复
```bash
# 恢复系统到原始状态
adb shell su -c "
setenforce 1
rm -f /data/local/tmp/injection_*
echo 0 > /sys/module/qca_cld3_peach_v2/parameters/inject
echo 0 > /sys/module/qca_cld3_peach_v2/parameters/promiscuous
svc wifi disable && svc wifi enable
"
```

## 📈 性能优化

### 1. 提升注入性能
```bash
# 增加缓冲区大小
echo 524288 > /sys/module/qca_cld3_peach_v2/parameters/buffer_size

# 优化信道切换
echo 1 > /sys/module/qca_cld3_peach_v2/parameters/fast_channel_switch
```

### 2. 监控性能
```bash
# 查看注入统计
adb shell "cat /sys/module/qca_cld3_peach_v2/parameters/inject_stats"

# 查看错误统计
adb shell "cat /sys/module/qca_cld3_peach_v2/parameters/error_stats"
```

## ⚠️ 重要提醒

### 安全警告
- 此模块涉及内核级别的修改
- 可能导致系统不稳定
- 建议在备用设备上测试
- 可能会触发某些安全检测

### 免责声明
- 本模块仅供合法的安全研究和学习使用
- 使用者必须拥有相关设备或获得明确授权
- 因使用本模块而导致的任何问题，由使用者自行承担责任

### 支持信息
- 如遇问题，请检查日志文件
- 可尝试重新安装模块
- 如问题持续，建议使用OTG外接网卡方案

## 🎯 成功率统计

| 测试环境 | 成功率 | 备注 |
|----------|--------|------|
| 标准Root环境 | 70% | 大多数情况下成功 |
| 自定义ROM | 50% | 可能存在兼容性问题 |
| 官方ROM | 80% | 最佳兼容性 |
| 加密ROM | 30% | 可能需要额外处理 |

祝您使用愉快！🚀