#!/system/bin/sh

# 一加15快速注入测试脚本
# 无需编译，直接测试注入可能性

echo "=== 一加15快速注入测试 ==="
echo "时间: $(date)"
echo

# 1. 检查基本注入参数
echo "=== 1. 检查驱动参数 ==="
for param in inject promiscuous raw_mode frame_injection; do
    if [ -f "/sys/module/qca_cld3_peach_v2/parameters/$param" ]; then
        current_val=$(cat "/sys/module/qca_cld3_peach_v2/parameters/$param")
        echo "✅ $param: $current_val"
        
        # 尝试修改参数
        echo "1" > "/sys/module/qca_cld3_peach_v2/parameters/$param" 2>/dev/null
        new_val=$(cat "/sys/module/qca_cld3_peach_v2/parameters/$param")
        if [ "$new_val" = "1" ]; then
            echo "✅ $param 可设置为1"
        else
            echo "❌ $param 无法设置为1"
        fi
    else
        echo "❌ $param 参数不存在"
    fi
done

echo

# 2. 检查WMI服务
echo "=== 2. 检查WMI服务 ==="
if grep -r "wmi_service_monitor_mode" /sys/kernel/debug/wlan 2>/dev/null; then
    echo "✅ 发现WMI服务监控模式支持"
else
    echo "❌ 未发现WMI服务监控模式支持"
fi

# 3. 检查驱动符号
echo "=== 3. 检查驱动符号 ==="
if grep -r "wlan_hdd_inject_frame" /sys/module/qca_cld3_peach_v2/sections 2>/dev/null; then
    echo "✅ 发现注入符号"
else
    echo "❌ 未发现注入符号"
fi

# 4. 测试iw命令
echo "=== 4. 测试iw命令 ==="
if command -v iw >/dev/null 2>&1; then
    echo "✅ iw命令可用"
    
    # 检查支持的命令
    iw list | grep -E "(inject|frame|monitor)" | head -5
else
    echo "❌ iw命令不可用"
fi

# 5. 测试tcpdump
echo "=== 5. 测试tcpdump ==="
if command -v tcpdump >/dev/null 2>&1; then
    echo "✅ tcpdump可用"
    
    # 测试监听模式
    echo "测试监听模式..."
    timeout 3 tcpdump -i wlan0 -c 1 -n 2>/dev/null || echo "监听模式正常，无数据包"
else
    echo "❌ tcpdump不可用"
fi

echo
echo "=== 测试完成 ==="
echo "建议:"
echo "1. 如果以上测试显示某些参数可用，尝试直接修改"
echo "2. 如果所有参数都不可用，考虑编译内核模块"
echo "3. 最简单方案: 使用OTG外接RTL8188FU适配器"