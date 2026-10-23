#!/bin/bash

# WiFiTester 工具包构建脚本
# 支持分模块打包，减少基础APK体积

set -e

echo "=== WiFiTester 工具包构建脚本 ==="

# 配置
GRADLE="./gradlew"
MODULES=("core" "aircrack" "hostapd" "reaver")
BUILD_TYPES=("debug" "release")
BASE_APK_SIZE_THRESHOLD="5M"  # 基础APK最大大小限制

# 创建工具包目录
mkdir -p app/src/main/assets/tool_packages
mkdir -p app/src/main/res/raw

echo "1. 检查基础APK大小..."
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK_SIZE=$(stat -c%s "app/build/outputs/apk/debug/app-debug.apk")
    echo "基础APK大小: $(($APK_SIZE / 1024 / 1024))MB"
    
    if [ $APK_SIZE -gt $(numfmt --from=iec $BASE_APK_SIZE_THRESHOLD) ]; then
        echo "警告: 基础APK大小超过阈值 $BASE_APK_SIZE_THRESHOLD"
    fi
fi

echo "2. 生成工具包配置文件..."

# 生成工具包配置文件
cat > app/src/main/assets/tool_packages/config.json << EOF
{
  "version": "1.0.0",
  "packages": {
    "core": {
      "name": "核心工具包",
      "description": "tcpdump, arpspoof - 网络监控和ARP欺骗工具",
      "tools": ["tcpdump", "arpspoof"],
      "preinstalled": true,
      "size": "2.1MB",
      "download_url": "https://github.com/KotobaWakusei/wifitester/releases/download/v1.0.0/core-tools.tar.gz"
    },
    "aircrack": {
      "name": "Aircrack-ng 套件",
      "description": "aircrack-ng, airodump-ng - WiFi网络审计工具",
      "tools": ["aircrack-ng", "airodump-ng"],
      "preinstalled": false,
      "size": "15.3MB",
      "download_url": "https://github.com/KotobaWakusei/wifitester/releases/download/v1.0.0/aircrack-tools.tar.gz"
    },
    "hostapd": {
      "name": "HostAPd 虚拟接入点",
      "description": "hostapd, iw - 创建虚拟WiFi接入点",
      "tools": ["hostapd", "iw"],
      "preinstalled": false,
      "size": "8.7MB",
      "download_url": "https://github.com/KotobaWakusei/wifitester/releases/download/v1.0.0/hostapd-tools.tar.gz"
    },
    "reaver": {
      "name": "Reaver WPS 攻击",
      "description": "reaver, bully - WPS PIN攻击工具",
      "tools": ["reaver", "bully"],
      "preinstalled": false,
      "size": "12.4MB",
      "download_url": "https://github.com/KotobaWakusei/wifitester/releases/download/v1.0.0/reaver-tools.tar.gz"
    }
  },
  "supported_devices": {
    "qualcomm_qcald_3.0": {
      "monitor_mode": true,
      "injection": false,
      "description": "Qualcomm QCACLD-3.0 芯片组，支持监听模式但不支持注入"
    },
    "qualcomm_qcald_2.0": {
      "monitor_mode": true,
      "injection": true,
      "description": "Qualcomm QCACLD-2.0 芯片组，支持监听和注入"
    },
    "broadcom_nexmon": {
      "monitor_mode": true,
      "injection": true,
      "description": "Broadcom + NexMon，支持监听和注入"
    },
    "mediatek": {
      "monitor_mode": true,
      "injection": false,
      "description": "MediaTek 芯片组，支持监听但不支持注入"
    },
    "huawei": {
      "monitor_mode": false,
      "injection": false,
      "description": "华为设备，不支持监听模式"
    }
  }
}
EOF

echo "3. 生成工具包下载脚本..."

# 生成工具包下载脚本
cat > app/src/main/assets/tool_packages/download.sh << 'EOF'
#!/system/bin/sh

# 工具包下载和安装脚本
# 需要Root权限

PACKAGE_NAME="$1"
PACKAGE_URL="$2"
INSTALL_DIR="/data/data/me.paranoid.wifikill/files/toolchain"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> /dev/sdcard/wifitester.log
}

log "开始下载工具包: $PACKAGE_NAME"

# 创建下载目录
mkdir -p /sdcard/wifitester_downloads
cd /sdcard/wifitester_downloads

# 下载工具包
if ! curl -L -o "$PACKAGE_NAME.tar.gz" "$PACKAGE_URL"; then
    log "下载失败: $PACKAGE_URL"
    exit 1
fi

log "下载完成，开始解压..."

# 解压工具包
if ! tar -xzf "$PACKAGE_NAME.tar.gz"; then
    log "解压失败"
    exit 1
fi

# 设置执行权限
chmod +x "$INSTALL_DIR"/*

log "工具包安装完成"

# 清理下载文件
rm -f "$PACKAGE_NAME.tar.gz"

exit 0
EOF

chmod +x app/src/main/assets/tool_packages/download.sh

echo "4. 生成设备检测脚本..."

# 生成设备检测脚本
cat > app/src/main/assets/tool_packages/detect_device.sh << 'EOF'
#!/system/bin/sh

# 设备能力检测脚本

detect_chipset() {
    local chipset="unknown"
    
    # 检查Qualcomm芯片组
    if [ -f "/sys/module/qca_cld3_peach_v2/parameters/con_mode" ]; then
        chipset="qualcomm_qcald_3.0"
        echo "4" > /sys/module/qca_cld3_peach_v2/parameters/con_mode
    elif [ -f "/sys/module/qca_cld2_peach/parameters/con_mode" ]; then
        chipset="qualcomm_qcald_2.0"
        echo "4" > /sys/module/qca_cld2_peach/parameters/con_mode
    fi
    
    # 检查Broadcom芯片组
    if [ -n "$(nexutil -m 2>&1 | grep -E 'Monitor|Injection')" ]; then
        chipset="broadcom_nexmon"
    fi
    
    # 检查MediaTek芯片组
    if [ "$(getprop ro.mediatek.platform)" != "" ]; then
        chipset="mediatek"
    fi
    
    # 检查华为设备
    if [ "$(getprop ro.product.brand)" = "huawei" ]; then
        chipset="huawei"
    fi
    
    echo "$chipset"
}

detect_monitor_mode() {
    local chipset=$(detect_chipset)
    
    case $chipset in
        "qualcomm_qcald_3.0"|"qualcomm_qcald_2.0"|"broadcom_nexmon"|"mediatek")
            echo "true"
            ;;
        *)
            echo "false"
            ;;
    esac
}

detect_injection() {
    local chipset=$(detect_chipset)
    
    case $chipset in
        "qualcomm_qcald_2.0"|"broadcom_nexmon")
            echo "true"
            ;;
        *)
            echo "false"
            ;;
    esac
}

# 输出检测结果
echo "Chipset: $(detect_chipset)"
echo "MonitorMode: $(detect_monitor_mode)"
echo "Injection: $(detect_injection)"
EOF

chmod +x app/src/main/assets/tool_packages/detect_device.sh

echo "5. 生成可选构建变体配置..."
# 注意: 不要覆盖 app/build.gradle。真机/CI 构建配置由 app/build.gradle 维护，
# 这里只生成一个可选的、通过 -I 显式启用的扩展配置文件，以及各变体的说明。
mkdir -p gradle

cat > gradle/tool-packages.gradle << 'EOF'
// 可选的工具包构建扩展。
//
// 默认构建不使用本文件。需要分变体构建时显式启用:
//   ./gradlew -I gradle/tool-packages.gradle assembleStandardDebug
//
// 变体通过 buildConfigField 区分；BuildConfig 在 app/build.gradle 中被关闭，
// 若需要使用这些字段，请先在 app/build.gradle 中打开 buildFeatures.buildConfig。
allprojects {
    afterEvaluate { project ->
        if (project.plugins.hasPlugin('com.android.application')) {
            project.android {
                buildTypes {
                    debug {
                        // 允许在调试版本中直接下载工具包。
                    }
                }
            }
        }
    }
}
EOF

cat > gradle/tool-variants.md << 'EOF'
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
EOF

echo "  -> gradle/tool-packages.gradle (可选扩展，默认不启用)"
echo "  -> gradle/tool-variants.md"

echo "6. 生成可选 ProGuard 规则..."
# app/build.gradle 已经引用 proguard-rules.pro；不要覆盖它。
# 这里生成的规则通过 -keep 形式独立存放在 gradle/ 下，
# 需要时在 app/build.gradle 的 proguardFiles 中追加即可。
cat > gradle/tool-packages.pro << 'EOF'
# 工具包管理系统附加混淆规则（可选）
-keep class me.paranoid.wifikill.ToolPackageManager { *; }
-keep class me.paranoid.wifikill.ToolInstallerService { *; }
-keep class me.paranoid.wifikill.ToolSelectionActivity { *; }
# JSON 与枚举的反射访问
-keep class org.json.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
EOF

echo "  -> gradle/tool-packages.pro (可选)"

echo "7. 生成CI/CD配置..."

# 生成GitHub Actions配置
mkdir -p .github/workflows
cat > .github/workflows/build.yml << EOF
name: Build WiFiTester

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'
    
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
    
    - name: Build debug APK
      run: ./gradlew assembleDebug
    
    - name: Build release APK
      run: ./gradlew assembleRelease
    
    - name: Upload debug APK
      uses: actions/upload-artifact@v3
      with:
        name: debug-apk
        path: app/build/outputs/apk/debug/
    
    - name: Upload release APK
      uses: actions/upload-artifact@v3
      with:
        name: release-apk
        path: app/build/outputs/apk/release/
    
    - name: Build APKs with different tool packages
      run: |
        ./gradlew assembleStandardDebug
        ./gradlew assembleProfessionalDebug
        ./gradlew assembleLiteDebug
    
    - name: Upload tool package APKs
      uses: actions/upload-artifact@v3
      with:
        name: tool-package-apks
        path: app/build/outputs/apk/

  test:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'
    
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
    
    - name: Run unit tests
      run: ./gradlew test
    
    - name: Run integration tests
      run: ./gradlew connectedAndroidTest
    
    - name: Generate test report
      run: ./gradlew jacocoTestReport
EOF

echo "8. 生成发布脚本..."

# 生成发布脚本
cat > release.sh << 'EOF'
#!/bin/bash

# WiFiTester 发布脚本

set -e

echo "=== WiFiTester 发布脚本 ==="

# 检查版本号
if [ ! -f "app/build.gradle" ]; then
    echo "错误: 找不到 build.gradle 文件"
    exit 1
fi

VERSION_CODE=$(grep -oP 'versionCode\s+\K\d+' app/build.gradle)
VERSION_NAME=$(grep -oP 'versionName\s+["\']([^"\']+)["\']' app/build.gradle)

echo "版本号: $VERSION_NAME ($VERSION_CODE)"

# 构建所有变体
echo "构建所有变体..."
./gradlew clean
./gradlew assembleStandardDebug
./gradlew assembleStandardRelease
./gradlew assembleProfessionalDebug
./gradlew assembleProfessionalRelease
./gradlew assembleLiteDebug
./gradlew assembleLiteRelease

# 创建发布目录
mkdir -p releases

# 复制APK文件
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk releases/wifitester-$VERSION_NAME-standard-debug.apk
cp app/build/outputs/apk/standard/release/app-standard-release.apk releases/wifitester-$VERSION_NAME-standard-release.apk
cp app/build/outputs/apk/professional/debug/app-professional-debug.apk releases/wifitester-$VERSION_NAME-professional-debug.apk
cp app/build/outputs/apk/professional/release/app-professional-release.apk releases/wifitester-$VERSION_NAME-professional-release.apk
cp app/build/outputs/apk/lite/debug/app-lite-debug.apk releases/wifitester-$VERSION_NAME-lite-debug.apk
cp app/build/outputs/apk/lite/release/app-lite-release.apk releases/wifitester-$VERSION_NAME-lite-release.apk

# 生成校验和
cd releases
for apk in *.apk; do
    sha256sum "$apk" > "$apk.sha256"
done
cd ..

# 生成发布说明
cat > releases/RELEASE_NOTES.md << EOF
# WiFiTester $VERSION_NAME 发布说明

## 版本信息
- 版本号: $VERSION_NAME
- 构建号: $VERSION_CODE
- 发布日期: $(date '+%Y-%m-%d')

## 构建变体

### Standard 版本
- 包含核心工具包
- 支持工具包下载
- 适合一般用户

### Professional 版本
- 包含所有工具包
- 支持高级功能
- 适合专业用户

### Lite 版本
- 仅包含核心工具包
- 最小体积
- 有限设备支持

## 下载链接
- [Standard Debug](releases/wifitester-$VERSION_NAME-standard-debug.apk)
- [Standard Release](releases/wifitester-$VERSION_NAME-standard-release.apk)
- [Professional Debug](releases/wifitester-$VERSION_NAME-professional-debug.apk)
- [Professional Release](releases/wifitester-$VERSION_NAME-professional-release.apk)
- [Lite Debug](releases/wifitester-$VERSION_NAME-lite-debug.apk)
- [Lite Release](releases/wifitester-$VERSION_NAME-lite-release.apk)

## 更新内容
- 新增工具包管理系统
- 支持按需下载工具包
- 设备能力检测
- 优化APK体积
- 修复已知问题

## 安装说明
1. 下载对应版本的APK文件
2. 安装到Android设备
3. 首次启动时选择需要的工具包
4. 授予Root权限以使用完整功能

## 注意事项
- 需要Root权限才能使用完整功能
- 部分设备可能不支持Wi-Fi监听模式
- 请确保从可信来源下载APK文件
EOF

echo "发布文件已生成: releases/ 目录"
echo "发布说明: releases/RELEASE_NOTES.md"

# 发布文件已就绪。提交与上传请手动执行:
#   git add releases/ && git commit -m "Release WiFiTester $VERSION_NAME"
EOF

chmod +x release.sh

echo "9. 生成开发文档..."

# 生成开发文档
cat > docs/tool-package-system.md << 'EOF'
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
EOF

echo "=== 构建完成 ==="
echo "生成的文件："
echo "- app/src/main/assets/tool_packages/config.json"
echo "- app/src/main/assets/tool_packages/download.sh"
echo "- app/src/main/assets/tool_packages/detect_device.sh"
echo "- gradle/tool-packages.gradle (可选扩展，默认不启用)"
echo "- gradle/tool-packages.pro    (可选混淆规则)"
echo "- gradle/tool-variants.md"
echo "- .github/workflows/build.yml"
echo "- release.sh"
echo "- docs/tool-package-system.md"
echo ""
echo "注意: app/build.gradle 与 proguard-rules.pro 由项目自身维护，本脚本不会覆盖。"
echo ""
echo "使用方法："
echo "1. 运行 'gradle :app:assembleDebug' 构建调试版本"
echo "2. 运行 'gradle :app:assembleRelease' 构建发布版本"
echo "3. 运行 './release.sh' 生成发布文件"
echo ""
echo "工具包管理系统已就绪！🎉"
