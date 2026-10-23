#!/bin/bash

# WiFiTester 发布脚本

set -e

echo "=== WiFiTester 发布脚本 ==="

# 检查版本号
if [ ! -f "app/build.gradle" ]; then
    echo "错误: 找不到 build.gradle 文件"
    exit 1
fi

VERSION_CODE=$(sed -n "s/.*versionCode[[:space:]]*\([0-9]*\).*/\1/p" app/build.gradle | head -1)
VERSION_NAME=$(sed -n "s/.*versionName[[:space:]]*[\"']\([^\"']*\)[\"'].*/\1/p" app/build.gradle | head -1)

echo "版本号: $VERSION_NAME ($VERSION_CODE)"

# 构建所有变体
echo "构建所有变体..."
GRADLE="${GRADLE:-/home/kali/.ac-toolchain/gradle-8.14.5/bin/gradle}"
JAVA_HOME="${JAVA_HOME:-/home/kali/.ac-toolchain/jdk-17}"
export JAVA_HOME
GRADLE_OPTS="-Dorg.gradle.java.home=${JAVA_HOME}"
"$GRADLE" $GRADLE_OPTS :app:clean :app:assembleDebug :app:assembleRelease

# 创建发布目录
mkdir -p releases

# 复制APK文件
cp app/build/outputs/apk/debug/app-debug.apk releases/wifitester-$VERSION_NAME-debug.apk
cp app/build/outputs/apk/release/app-release.apk releases/wifitester-$VERSION_NAME-release.apk

# 生成校验和
cd releases
for apk in *.apk; do
    sha256sum "$apk" > "$apk.sha256"
done
cd ..

# 可选工具包（aircrack-ng / hostapd / reaver）作为 Release 附件发布。
# 由 tools/build-toolpacks.sh 生成；存在时一并上传，保证应用内的下载地址可用。
TOOLPACKS=(build/toolpacks/*.tar.gz)
if [ -e "${TOOLPACKS[0]}" ]; then
    cp "${TOOLPACKS[@]}" releases/
    echo "已复制工具包: $(ls build/toolpacks/*.tar.gz | wc -l) 个"
else
    echo "提示: 未找到 build/toolpacks/*.tar.gz，跳过工具包上传"
fi

# 生成发布说明
cat > releases/RELEASE_NOTES.md << EOF
# WiFiTester $VERSION_NAME 发布说明

## 版本信息
- 版本号: $VERSION_NAME
- 构建号: $VERSION_CODE
- 发布日期: $(date '+%Y-%m-%d')

## 构建产物

### Debug 版本
- 包含调试标识，用于开发测试
- 支持工具包按需下载

### Release 版本
- 未签名（或按 keystore.properties 签名）
- 适合正式分发

## 下载链接
- [Debug APK](releases/wifitester-$VERSION_NAME-debug.apk)
- [Release APK](releases/wifitester-$VERSION_NAME-release-unsigned.apk)

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
