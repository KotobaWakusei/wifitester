# WiFiTester Injection Module for OnePlus 15

## 模块简介
这是一个专门为一加15设计的Magisk模块，旨在绕过Qualcomm QCACLD 3.0 Wi-Fi驱动的帧注入限制。

## 功能特性
- ✅ 绕过固件级别的注入限制
- ✅ 启用完整的监听+注入功能
- ✅ 自动检测和修复驱动参数
- ✅ 支持热插拔和重启恢复
- ✅ 完整的卸载功能

## 支持的设备
- OnePlus 15 (PLK110)
- SM8550芯片组
- QCACLD 3.0驱动

## 安装方法
1. 下载模块文件：`WiFiTester-Injection-v1.0.zip`
2. 打开Magisk Manager
3. 点击"模块" -> "安装 from storage"
4. 选择下载的ZIP文件
5. 重启设备

## 使用方法
### 启用注入功能
```bash
# 模块安装后会自动启用
# 如需手动控制：
echo 1 > /data/local/tmp/injection_enabled
svc wifi disable && svc wifi enable
```

### 验证注入功能
```bash
# 检查注入状态
cat /sys/module/qca_cld3_peach_v2/parameters/inject

# 测试注入
iwpriv wlan0 set inject=1
aireplay-ng --test wlan0
```

### 禁用注入功能
```bash
# 临时禁用
echo 0 > /data/local/tmp/injection_enabled
svc wifi disable && svc wifi enable

# 或完全卸载模块
```

## 技术原理
### 方法1: 驱动参数修改
- 修改`/sys/module/qca_cld3_peach_v2/parameters/`下的参数
- 强制启用`inject`、`promiscuous`等参数

### 方法2: WMI服务欺骗
- 修改WMI服务列表，让驱动认为固件支持注入
- 绕过固件级别的检查

### 方法3: 内核符号补丁
- 修改内核符号表，允许注入相关函数
- 绕过内核级别的安全检查

## 风险提示
⚠️ **重要提醒**：
- 此模块涉及内核级别的修改
- 可能导致系统不稳定
- 建议在备用设备上测试
- 可能会触发某些安全检测

## 故障排除
### 常见问题
1. **模块安装失败**
   - 检查是否为Root设备
   - 确认Magisk版本兼容性

2. **注入功能未启用**
   - 重启设备
   - 检查日志：`logcat | grep wifitester`

3. **WiFi连接异常**
   - 尝试重新连接WiFi
   - 如问题持续，卸载模块并恢复

### 日志查看
```bash
# 查看模块日志
logcat | grep wifitester

# 查看驱动日志
logcat | grep qca_cld3
```

## 版本历史
- v1.0 (2026-10-22): 初始版本，支持基本注入功能

## 免责声明
本模块仅供合法的安全研究和学习使用。使用者必须拥有相关设备或获得明确授权。因使用本模块而导致的任何问题，由使用者自行承担责任。

## 支持
如有问题或建议，请提交GitHub Issue。