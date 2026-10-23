#!/system/bin/sh

# WiFiTester注入模块卸载脚本
# 安全地移除注入功能并恢复系统状态

MODDIR=${0%/*}

# 日志函数
log() {
    echo "[WiFiTester-Uninstall] $1" >> /data/local/tmp/wifitester_uninstall.log
    log -t "WiFiTester" "$1"
}

# 检查权限
if [ "$(id -u)" != "0" ]; then
    log "错误：需要Root权限"
    exit 1
fi

log "开始卸载WiFiTester注入模块"

# 停止注入功能
stop_injection() {
    log "停止注入功能..."
    
    # 禁用注入
    echo "0" > /data/local/tmp/injection_enabled 2>/dev/null || true
    
    # 恢复驱动参数
    for param in inject promiscuous raw_mode frame_injection; do
        if [ -f "/sys/module/qca_cld3_peach_v2/parameters/$param" ]; then
            echo "0" > "/sys/module/qca_cld3_peach_v2/parameters/$param"
            log "参数 $param 已恢复为0"
        fi
    done
    
    # 恢复SELinux
    setenforce 1 2>/dev/null || log "SELinux恢复失败"
    
    log "注入功能已停止"
}

# 清理文件
cleanup_files() {
    log "清理模块文件..."
    
    # 删除模块目录
    if [ -d "$MODDIR" ]; then
        rm -rf "$MODDIR"
        log "模块目录已删除"
    fi
    
    # 删除临时文件
    for file in /data/local/tmp/injection_enabled \
               /data/local/tmp/injection_config \
               /data/local/tmp/injection_status \
               /data/local/tmp/wifitester_*.log \
               /sdcard/wifi_backup_*.ko; do
        if [ -f "$file" ]; then
            rm -f "$file"
            log "已删除: $file"
        fi
    done
    
    # 删除系统属性
    for prop in persist.wifitester.injection.enabled \
               persist.wifitester.promiscuous.enabled \
               persist.wifitester.raw_mode.enabled \
               persist.wifi.inject.enabled \
               persist.wifi.promiscuous.enabled \
               persist.wifi.raw_mode.enabled; do
        resetprop "$prop" 2>/dev/null || true
        log "已重置属性: $prop"
    done
    
    log "文件清理完成"
}

# 恢复WiFi服务
restore_wifi() {
    log "恢复WiFi服务..."
    
    # 重启WiFi服务
    svc wifi disable
    sleep 2
    svc wifi enable
    
    # 等待WiFi恢复
    sleep 5
    
    # 检查WiFi状态
    if iw dev wlan0 info >/dev/null 2>&1; then
        log "WiFi服务已恢复"
    else
        log "警告：WiFi服务恢复失败，可能需要手动重启"
    fi
}

# 显示卸载信息
show_info() {
    log "卸载完成"
    log "WiFiTester注入模块已完全移除"
    log "系统已恢复到原始状态"
    log "如需重新安装，请重新下载模块文件"
}

# 主卸载流程
main() {
    stop_injection
    cleanup_files
    restore_wifi
    show_info
}

# 执行主函数
main