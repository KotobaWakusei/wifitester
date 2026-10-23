#!/system/bin/sh

# WiFiTester注入模块测试脚本
# 验证注入功能是否正常工作

echo "=== WiFiTester注入模块测试 ==="
echo "时间: $(date)"
echo

# 测试函数
test_result() {
    local test_name="$1"
    local command="$2"
    local expected="$3"
    
    echo "测试: $test_name"
    
    if eval "$command" >/dev/null 2>&1; then
        echo "✅ $test_name - 通过"
        return 0
    else
        echo "❌ $test_name - 失败"
        return 1
    fi
}

# 1. 检查模块安装
echo "=== 1. 检查模块安装 ==="
test_result "模块目录存在" "[ -d '/data/adb/modules/wifitester-injection' ]" "true"
test_result "service.sh存在" "[ -f '/data/adb/modules/wifitester-injection/service.sh' ]" "true"

# 2. 检查注入参数
echo "=== 2. 检查注入参数 ==="
for param in inject promiscuous raw_mode frame_injection; do
    if [ -f "/sys/module/qca_cld3_peach_v2/parameters/$param" ]; then
        current_val=$(cat "/sys/module/qca_cld3_peach_v2/parameters/$param")
        echo "参数 $param: $current_val"
        
        # 尝试设置为1
        echo "1" > "/sys/module/qca_cld3_peach_v2/parameters/$param" 2>/dev/null
        new_val=$(cat "/sys/module/qca_cld3_peach_v2/parameters/$param")
        
        if [ "$new_val" = "1" ]; then
            echo "✅ $param 可设置为1"
        else
            echo "❌ $param 无法设置为1"
        fi
    else
        echo "❌ 参数 $param 不存在"
    fi
done

# 3. 检查系统属性
echo "=== 3. 检查系统属性 ==="
for prop in persist.wifitester.injection.enabled \
           persist.wifitester.promiscuous.enabled \
           ro.wifitester.supported; do
    value=$(getprop "$prop" 2>/dev/null || echo "未设置")
    echo "属性 $prop: $value"
done

# 4. 检查WiFi状态
echo "=== 4. 检查WiFi状态 ==="
if iw dev wlan0 info >/dev/null 2>&1; then
    echo "✅ WiFi接口正常"
    
    # 检查接口类型
    interface_type=$(iw dev wlan0 info | grep type | awk '{print $2}')
    echo "接口类型: $interface_type"
    
    if [ "$interface_type" = "managed" ]; then
        echo "当前为管理模式，尝试切换到监听模式..."
        
        # 切换到监听模式
        ip link set wlan0 down
        echo 4 > /sys/module/qca_cld3_peach_v2/parameters/con_mode
        ip link set wlan0 up
        
        sleep 2
        
        # 再次检查接口类型
        new_interface_type=$(iw dev wlan0 info | grep type | awk '{print $2}')
        echo "切换后接口类型: $new_interface_type"
        
        if [ "$new_interface_type" = "monitor" ]; then
            echo "✅ 成功切换到监听模式"
        else
            echo "❌ 切换到监听模式失败"
        fi
    fi
else
    echo "❌ WiFi接口异常"
fi

# 5. 测试注入功能
echo "=== 5. 测试注入功能 ==="
if [ -f "/data/local/tmp/injection_enabled" ] && [ "$(cat /data/local/tmp/injection_enabled)" = "1" ]; then
    echo "✅ 注入功能已启用"
    
    # 测试tcpdump
    echo "测试tcpdump监听..."
    timeout 3 tcpdump -i wlan0 -c 1 -n 2>/dev/null || echo "监听正常，无数据包"
    
    # 测试iw命令
    echo "测试iw命令..."
    iw list | grep -E "(inject|frame|monitor)" | head -3 || echo "未找到相关命令"
    
else
    echo "❌ 注入功能未启用"
    echo "运行: echo 1 > /data/local/tmp/injection_enabled"
fi

# 6. 检查SELinux状态
echo "=== 6. 检查SELinux状态 ==="
sestatus=$(getenforce 2>/dev/null || echo "未知")
echo "SELinux状态: $sestatus"

if [ "$sestatus" = "Permissive" ] || [ "$sestatus" = "Disabled" ]; then
    echo "✅ SELinux允许注入操作"
else
    echo "❌ SELinux可能阻止注入操作"
fi

# 7. 测试 aireplay-ng
echo "=== 7. 测试 aireplay-ng ==="
if command -v aireplay-ng >/dev/null 2>&1; then
    echo "aireplay-ng可用"
    
    # 测试注入
    echo "测试注入功能..."
    aireplay-ng --test wlan0 2>&1 | head -5 || echo "注入测试完成"
else
    echo "aireplay-ng不可用"
fi

echo
echo "=== 测试完成 ==="
echo "如果所有测试都通过，注入功能应该已启用"
echo "如需验证，请使用: aireplay-ng --test wlan0"

# 生成测试报告
cat > /data/local/tmp/wifitester_test_report.txt << EOF
WiFiTester注入模块测试报告
测试时间: $(date)
设备信息: $(getprop ro.product.model)
系统版本: $(getprop ro.build.version.release)
内核版本: $(uname -r)

测试结果摘要:
- 模块安装: [通过/失败]
- 注入参数: [通过/失败]
- 系统属性: [通过/失败]
- WiFi状态: [通过/失败]
- 注入功能: [通过/失败]
- SELinux状态: [通过/失败]
- aireplay-ng: [通过/失败]

建议操作:
1. 如测试通过，尝试使用aireplay-ng进行实际测试
2. 如测试失败，检查日志文件
3. 如需要，重新安装模块
EOF

echo "测试报告已保存到: /data/local/tmp/wifitester_test_report.txt"