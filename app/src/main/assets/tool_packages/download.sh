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
