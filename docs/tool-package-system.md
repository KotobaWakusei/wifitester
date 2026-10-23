# WiFiTester 工具包管理系统

## 概述

WiFiTester 采用分模块工具包管理系统，支持按需下载和安装工具包，减少基础APK体积。

## 系统架构

### 核心组件

1. **ToolPackageManager** - 工具包状态管理
2. **ToolInstallerService** - 后台下载服务
3. **ToolSelectionActivity** - 首次启动引导界面
4. **SettingsFragment** - 工具包管理界面

### 工具包类型

#### 核心工具包（内置）
- tcpdump - 网络包捕获工具
- arpspoof - ARP欺骗工具
- 大小：约2.1MB
- 不可卸载

#### 可选工具包（按需下载）
- **Aircrack-ng 套件** (15.3MB)
  - aircrack-ng - WiFi密码破解
  - airodump-ng - WiFi网络监控
  
- **HostAPd 虚拟接入点** (8.7MB)
  - hostapd - 创建虚拟WiFi接入点
  - iw - 无线网络工具
  
- **Reaver WPS 攻击** (12.4MB)
  - reaver - WPS PIN攻击
  - bully - WPS暴力攻击

## 设备适配

### 支持的设备类型

1. **Qualcomm QCACLD-3.0**
   - 监听模式：✅
   - 帧注入：❌
   - 代表设备：一加15等新机型

2. **Qualcomm QCACLD-2.0**
   - 监听模式：✅
   - 帧注入：✅
   - 代表设备：一加3T等老机型

3. **Broadcom + NexMon**
   - 监听模式：✅
   - 帧注入：✅
   - 代表设备：Galaxy S10/S20

4. **MediaTek**
   - 监听模式：✅
   - 帧注入：❌
   - 代表设备：部分小米/Redmi设备

5. **华为**
   - 监听模式：❌
   - 帧注入：❌
   - 建议：使用OTG外接网卡

### 设备检测机制

```java
// 设备能力检测
DeviceMonitorCapability capability = toolManager.getDeviceMonitorCapability();

switch (capability) {
    case FULL_MONITOR_INJECTION:
        // 支持完整功能
        break;
    case MONITOR_ONLY:
        // 仅支持监听
        break;
    case NO_MONITOR:
        // 不支持监听
        break;
    case REQUIRES_OTG:
        // 需要外接网卡
        break;
}
```

## 使用流程

### 1. 首次启动
1. 显示免责声明
2. 选择要安装的工具包
3. 核心工具包默认选中
4. 点击"开始使用"

### 2. 工具包管理
1. 进入设置 → 工具包管理
2. 查看已安装工具包
3. 下载/卸载可选工具包
4. 查看占用空间

### 3. 攻击模块使用
1. 选择攻击模块
2. 检查工具可用性
3. 缺少工具时提示下载
4. 下载完成后继续使用

## 开发指南

### 添加新工具包

1. 在ToolPackageManager中定义新工具包
```java
NEW_PACKAGE("new", "新工具包", Arrays.asList("tool1", "tool2"), false)
```

2. 更新工具包配置文件
```json
{
  "new": {
    "name": "新工具包",
    "description": "工具描述",
    "tools": ["tool1", "tool2"],
    "preinstalled": false,
    "size": "5.0MB",
    "download_url": "https://..."
  }
}
```

3. 更新字符串资源
```xml
<string name="new_package_name">新工具包</string>
<string name="new_package_desc">工具描述</string>
```

### 构建变体

#### Standard 版本
- 包含核心工具包
- 支持工具包下载
- 适合一般用户

#### Professional 版本
- 包含所有工具包
- 支持高级功能
- 适合专业用户

#### Lite 版本
- 仅包含核心工具包
- 最小体积
- 有限设备支持

### 发布流程

1. 更新版本号
2. 运行 `./release.sh`
3. 生成发布文件
4. 上传到GitHub

## 故障排除

### 常见问题

1. **工具下载失败**
   - 检查网络连接
   - 确认存储空间充足
   - 查看日志文件

2. **设备不支持监听模式**
   - 检查设备芯片组
   - 尝试使用OTG外接网卡
   - 查看设备能力说明

3. **Root权限问题**
   - 确认设备已Root
   - 检查SuperSU/Magisk状态
   - 重启应用

### 日志文件

- 应用日志：`/sdcard/wifitester.log`
- 工具下载日志：`/sdcard/wifitester_downloads/`
- ADB日志：`adb logcat | grep WiFiTester`

## 性能优化

### APK体积优化
- 分模块构建
- 按需下载
- 资源压缩

### 运行时优化
- 异步下载
- 后台服务
- 缓存管理

### 内存优化
- 工具按需加载
- 及时释放资源
- 避免内存泄漏

## 安全考虑

### 权限管理
- 最小权限原则
- 动态权限申请
- 权限使用说明

### 数据安全
- 敏感数据加密
- 安全的存储方式
- 防止数据泄露

### 代码安全
- 代码混淆
- 防逆向工程
- 安全审计

## 未来规划

### 计划功能
1. **工具包版本管理**
   - 自动更新
   - 版本回滚
   - 冲突解决

2. **更多设备支持**
   - 设备数据库
   - 社区贡献
   - 自动适配

3. **高级功能**
   - 工具包组合
   - 自定义工具包
   - 云端同步

### 技术改进
1. **下载优化**
   - 断点续传
   - 多线程下载
   - 下载限速

2. **UI改进**
   - 下载进度显示
   - 工具包详情
   - 使用统计

3. **测试完善**
   - 单元测试
   - 集成测试
   - 自动化测试