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
