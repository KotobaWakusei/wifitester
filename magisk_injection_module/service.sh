#!/system/bin/sh

# Magisk模块安装脚本
# 用于WiFiTester注入模块的安装

MODDIR=${0%/*}

# 日志函数
log() {
    echo "[WiFiTester] $1" >> /data/local/tmp/wifitester_install.log
    log -t "WiFiTester" "$1"
}

# 检查Root权限
if [ "$(id -u)" != "0" ]; then
    log "错误：需要Root权限"
    exit 1
fi

log "开始安装WiFiTester注入模块"

# 备份原始驱动
backup_driver() {
    log "备份原始驱动..."
    if [ -f "/vendor/lib64/wifi/qca_cld3_peach_v2.ko" ]; then
        cp /vendor/lib64/wifi/qca_cld3_peach_v2.ko /sdcard/wifi_backup_$(date +%Y%m%d_%H%M%S).ko
        log "驱动备份完成"
    else
        log "警告：未找到原始驱动文件"
    fi
}

# 检查设备兼容性
check_device() {
    log "检查设备兼容性..."
    
    # 检查设备型号
    DEVICE_MODEL=$(getprop ro.product.model)
    if [ "$DEVICE_MODEL" != "OnePlus 15" ] && [ "$DEVICE_MODEL" != "PLK110" ]; then
        log "警告：设备型号可能不兼容: $DEVICE_MODEL"
    fi
    
    # 检查驱动模块
    if [ -d "/sys/module/qca_cld3_peach_v2" ]; then
        log "✓ 找到QCACLD 3.0驱动模块"
    else
        log "错误：未找到QCACLD 3.0驱动模块"
        exit 1
    fi
    
    # 检查内核版本
    KERNEL_VERSION=$(uname -r)
    log "内核版本: $KERNEL_VERSION"
}

# 创建注入控制文件
create_control_files() {
    log "创建注入控制文件..."
    
    # 创建注入状态文件
    echo "0" > /data/local/tmp/injection_status
    
    # 创建配置文件
    cat > /data/local/tmp/injection_config << EOF
# WiFiTester注入配置
INJECTION_ENABLED=0
PROMISCUOUS_ENABLED=0
RAW_MODE_ENABLED=0
DEBUG_MODE=1
EOF
    
    log "控制文件创建完成"
}

# 设置SELinux策略
setup_selinux() {
    log "设置SELinux策略..."
    
    # 临时设置为permissive模式
    setenforce 0 2>/dev/null || log "SELinux设置失败"
    
    # 创建自定义SELinux策略
    cat > /data/local/tmp/wifitester.te << EOF
module wifitester 1.0;

require {
    type system_server;
    type wifi_prop;
    type sysfs_t;
    type tmpfs_t;
}

# Allow system_server to modify WiFi properties
allow system_server wifi_prop:file write;
allow system_server sysfs_t:file write;

# Allow WiFi injection operations
allow system_server tmpfs_t:file write;
EOF

    log "SELinux策略设置完成"
}

# 修改驱动参数
modify_driver_params() {
    log "修改驱动参数..."
    
    # 尝试修改各种注入参数
    for param in inject promiscuous raw_mode frame_injection; do
        if [ -f "/sys/module/qca_cld3_peach_v2/parameters/$param" ]; then
            echo "1" > "/sys/module/qca_cld3_peach_v2/parameters/$param"
            log "✓ 参数 $param 已设置为1"
        else
            log "- 参数 $param 不存在"
        fi
    done
    
    # 设置WMI服务标志
    echo "1" > /sys/module/qca_cld3_peach_v2/parameters/wmi_monitor 2>/dev/null || log "WMI监控参数不可用"
    
    log "驱动参数修改完成"
}

# 创建启动脚本
create_startup_scripts() {
    log "创建启动脚本..."
    
    # 创建post-fs-data脚本
    cat > $MODDIR/post-fs-data.sh << 'EOF'
#!/system/bin/sh

# WiFi注入启动脚本
# 在系统启动时执行

MODDIR=${0%/*}

# 等待WiFi服务启动
sleep 10

# 检查是否启用注入
if [ -f /data/local/tmp/injection_enabled ] && [ "$(cat /data/local/tmp/injection_enabled)" = "1" ]; then
    # 设置注入参数
    echo "1" > /sys/module/qca_cld3_peach_v2/parameters/inject 2>/dev/null || true
    echo "1" > /sys/module/qca_cld3_peach_v2/parameters/promiscuous 2>/dev/null || true
    
    # 设置SELinux为permissive
    setenforce 0 2>/dev/null || true
    
    log -t "WiFiTester" "注入功能已启用"
fi
EOF

    # 创建服务脚本
    cat > $MODDIR/service.sh << 'EOF'
#!/system/bin/sh

# WiFi注入服务脚本
# 持续监控注入状态

MODDIR=${0%/*}

while true; do
    if [ -f /data/local/tmp/injection_enabled ] && [ "$(cat /data/local/tmp/injection_enabled)" = "1" ]; then
        # 确保注入参数保持启用
        echo "1" > /sys/module/qca_cld3_peach_v2/parameters/inject 2>/dev/null || true
        echo "1" > /sys/module/qca_cld3_peach_v2/parameters/promiscuous 2>/dev/null || true
    fi
    sleep 30
done
EOF

    # 设置执行权限
    chmod 755 $MODDIR/post-fs-data.sh
    chmod 755 $MODDIR/service.sh
    
    log "启动脚本创建完成"
}

# 创建系统属性
create_system_props() {
    log "创建系统属性..."
    
    cat > $MODDIR/system.prop << EOF
# WiFi注入系统属性
persist.wifitester.injection.enabled=1
persist.wifitester.promiscuous.enabled=1
persist.wifitester.raw_mode.enabled=1
ro.wifitester.supported=1
debug.wifitester.verbose=1
EOF
    
    log "系统属性创建完成"
}

# 创建卸载脚本
create_uninstall_script() {
    log "创建卸载脚本..."
    
    cat > $MODDIR/uninstall.sh << 'EOF'
#!/system/bin/sh

# WiFi注入模块卸载脚本

MODDIR=${0%/*}

# 停止注入
echo "0" > /data/local/tmp/injection_enabled

# 恢复SELinux
setenforce 1 2>/dev/null || true

# 清理注入文件
rm -f /data/local/tmp/injection_enabled
rm -f /data/local/tmp/injection_config
rm -f /data/local/tmp/injection_status

# 重启WiFi服务
svc wifi disable
sleep 2
svc wifi enable

log -t "WiFiTester" "注入模块已卸载"
EOF

    chmod 755 $MODDIR/uninstall.sh
    
    log "卸载脚本创建完成"
}

# 主安装流程
main() {
    log "开始安装流程"
    
    check_device
    backup_driver
    create_control_files
    setup_selinux
    modify_driver_params
    create_startup_scripts
    create_system_props
    create_uninstall_script
    
    log "安装完成"
    log "请重启设备以使更改生效"
    log "重启后运行: echo 1 > /data/local/tmp/injection_enabled"
}

# 执行主函数
main