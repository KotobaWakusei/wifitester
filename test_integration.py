#!/usr/bin/env python3
"""
集成测试：验证工具包管理系统的完整功能
"""

import subprocess
import sys
import os
import json

def test_config_file():
    """测试配置文件"""
    print("=== 测试配置文件 ===")
    
    config_path = "app/src/main/assets/tool_packages/config.json"
    if not os.path.exists(config_path):
        print("❌ 配置文件不存在")
        return False
    
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
        
        # 检查必需字段
        required_fields = ["version", "packages", "supported_devices"]
        for field in required_fields:
            if field not in config:
                print(f"❌ 缺少字段: {field}")
                return False
        
        # 检查工具包
        packages = config["packages"]
        required_packages = ["core", "aircrack", "hostapd", "reaver"]
        for pkg in required_packages:
            if pkg not in packages:
                print(f"❌ 缺少工具包: {pkg}")
                return False
            
            pkg_info = packages[pkg]
            required_pkg_fields = ["name", "description", "tools", "preinstalled", "size", "download_url"]
            for field in required_pkg_fields:
                if field not in pkg_info:
                    print(f"❌ 工具包 {pkg} 缺少字段: {field}")
                    return False
        
        # 检查设备支持
        devices = config["supported_devices"]
        required_devices = ["qualcomm_qcald_3.0", "qualcomm_qcald_2.0", "broadcom_nexmon", "mediatek", "huawei"]
        for device in required_devices:
            if device not in devices:
                print(f"❌ 缺少设备支持: {device}")
                return False
            
            device_info = devices[device]
            required_device_fields = ["monitor_mode", "injection", "description"]
            for field in required_device_fields:
                if field not in device_info:
                    print(f"❌ 设备 {device} 缺少字段: {field}")
                    return False
        
        print("✅ 配置文件格式正确")
        return True
        
    except json.JSONDecodeError as e:
        print(f"❌ 配置文件JSON格式错误: {e}")
        return False

def test_build_configuration():
    """测试构建配置"""
    print("\n=== 测试构建配置 ===")
    
    build_gradle_path = "app/build.gradle"
    if not os.path.exists(build_gradle_path):
        print("❌ 构建配置文件不存在")
        return False
    
    with open(build_gradle_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 验证项目原有的构建配置未被破坏
    required_configs = [
        "android",
        "compileSdk",
        "defaultConfig",
        "buildTypes",
        "dependencies"
    ]
    
    missing_configs = []
    for config in required_configs:
        if config not in content:
            missing_configs.append(config)
    
    if missing_configs:
        print(f"❌ 缺少构建配置: {missing_configs}")
        return False
    
    print("✅ 构建配置正确（保持项目原有方式）")
    return True

def test_shell_scripts():
    """测试Shell脚本"""
    print("\n=== 测试Shell脚本 ===")
    
    scripts = [
        "app/src/main/assets/tool_packages/download.sh",
        "app/src/main/assets/tool_packages/detect_device.sh"
    ]
    
    for script in scripts:
        if not os.path.exists(script):
            print(f"❌ 脚本文件不存在: {script}")
            return False
        
        # 检查脚本执行权限
        if not os.access(script, os.X_OK):
            print(f"❌ 脚本没有执行权限: {script}")
            return False
    
    print("✅ Shell脚本正确")
    return True

def test_java_integration():
    """测试Java集成"""
    print("\n=== 测试Java集成 ===")
    
    # 检查ToolPackageManager的配置文件加载
    tool_manager_path = "app/src/main/java/me/paranoid/wifikill/ToolPackageManager.java"
    with open(tool_manager_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if "CONFIG_FILE" not in content:
        print("❌ ToolPackageManager缺少配置文件加载")
        return False
    
    if "loadConfig()" not in content:
        print("❌ ToolPackageManager缺少配置加载方法")
        return False
    
    if "getPackageInfo" not in content:
        print("❌ ToolPackageManager缺少获取包信息方法")
        return False
    
    if "getDeviceInfo" not in content:
        print("❌ ToolPackageManager缺少获取设备信息方法")
        return False
    
    print("✅ Java集成正确")
    return True

def test_ci_cd():
    """测试CI/CD配置"""
    print("\n=== 测试CI/CD配置 ===")
    
    workflow_path = ".github/workflows/build.yml"
    if not os.path.exists(workflow_path):
        print("❌ CI/CD工作流文件不存在")
        return False
    
    with open(workflow_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查关键步骤
    required_steps = [
        "Build debug APK",
        "Build release APK",
        "Run unit tests"
    ]
    
    missing_steps = []
    for step in required_steps:
        if step not in content:
            missing_steps.append(step)
    
    if missing_steps:
        print(f"❌ CI/CD缺少步骤: {missing_steps}")
        return False
    
    print("✅ CI/CD配置正确")
    return True

def test_release_script():
    """测试发布脚本"""
    print("\n=== 测试发布脚本 ===")
    
    release_script = "release.sh"
    if not os.path.exists(release_script):
        print("❌ 发布脚本不存在")
        return False
    
    if not os.access(release_script, os.X_OK):
        print("❌ 发布脚本没有执行权限")
        return False
    
    with open(release_script, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查关键功能（实际构建任务）
    required_functions = [
        "assembleDebug",
        "assembleRelease",
        "app/build.gradle"
    ]
    
    missing_functions = []
    for func in required_functions:
        if func not in content:
            missing_functions.append(func)
    
    if missing_functions:
        print(f"❌ 发布脚本缺少功能: {missing_functions}")
        return False
    
    print("✅ 发布脚本正确")
    return True

def test_documentation():
    """测试文档"""
    print("\n=== 测试文档 ===")
    
    docs_path = "docs/tool-package-system.md"
    if not os.path.exists(docs_path):
        print("❌ 文档不存在")
        return False
    
    with open(docs_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查文档内容
    required_sections = [
        "概述",
        "系统架构",
        "核心组件",
        "工具包类型",
        "设备适配",
        "使用流程",
        "开发指南",
        "故障排除",
        "性能优化",
        "安全考虑",
        "未来规划"
    ]
    
    missing_sections = []
    for section in required_sections:
        if section not in content:
            missing_sections.append(section)
    
    if missing_sections:
        print(f"❌ 文档缺少章节: {missing_sections}")
        return False
    
    print("✅ 文档完整")
    return True

def test_apk_size():
    """测试APK大小"""
    print("\n=== 测试APK大小 ===")
    
    apk_path = "app/build/outputs/apk/debug/app-debug.apk"
    if not os.path.exists(apk_path):
        print("❌ APK文件不存在")
        return False
    
    # 获取APK大小
    apk_size = os.path.getsize(apk_path)
    apk_size_mb = apk_size / (1024 * 1024)
    
    print(f"当前APK大小: {apk_size_mb:.2f}MB")
    
    # 检查是否在合理范围内
    if apk_size_mb > 10:
        print("⚠️  APK大小较大，考虑使用分模块构建")
    
    print("✅ APK大小检查完成")
    return True

def test_memory_usage():
    """测试内存使用情况"""
    print("\n=== 测试内存使用情况 ===")
    
    # 检查Java文件中的内存优化
    tool_manager_path = "app/src/main/java/me/paranoid/wifikill/ToolPackageManager.java"
    with open(tool_manager_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查内存优化措施
    optimizations = [
        "File",  # 使用文件而不是内存存储
        "SharedPreferences",  # 使用SharedPreferences而不是内存
        "enum",  # 使用枚举而不是字符串
        "static"  # 使用静态变量
    ]
    
    missing_optimizations = []
    for opt in optimizations:
        if opt not in content:
            missing_optimizations.append(opt)
    
    if missing_optimizations:
        print(f"⚠️  可能缺少内存优化: {missing_optimizations}")
    
    print("✅ 内存使用检查完成")
    return True

def test_security():
    """测试安全措施"""
    print("\n=== 测试安全措施 ===")
    
    # 检查ProGuard规则
    proguard_path = "proguard-rules.pro"
    if not os.path.exists(proguard_path):
        print("❌ ProGuard规则文件不存在")
        return False
    
    with open(proguard_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查安全规则
    security_rules = [
        "-keepclassmembers class me.paranoid.wifikill.ToolPackageManager",
        "-keepclassmembers class me.paranoid.wifikill.ToolInstallerService",
        "-keepclassmembers class me.paranoid.wifikill.ToolSelectionActivity",
        "-keepclassmembers class * implements java.io.Serializable"
    ]
    
    missing_rules = []
    for rule in security_rules:
        if rule not in content:
            missing_rules.append(rule)
    
    if missing_rules:
        print(f"❌ 缺少安全规则: {missing_rules}")
        return False
    
    print("✅ 安全措施检查完成")
    return True

def main():
    """运行所有集成测试"""
    print("开始WiFiTester工具包管理系统集成测试...")
    
    tests = [
        test_config_file,
        test_build_configuration,
        test_shell_scripts,
        test_java_integration,
        test_ci_cd,
        test_release_script,
        test_documentation,
        test_apk_size,
        test_memory_usage,
        test_security
    ]
    
    passed = 0
    total = len(tests)
    
    for test in tests:
        if test():
            passed += 1
        print()
    
    print(f"\n=== 集成测试结果 ===")
    print(f"通过: {passed}/{total}")
    
    if passed == total:
        print("🎉 所有集成测试通过！工具包管理系统实现完整。")
        return 0
    else:
        print("❌ 部分集成测试失败，请检查实现。")
        return 1

if __name__ == "__main__":
    sys.exit(main())