#!/usr/bin/env python3
"""
测试工具包管理系统的基本功能
"""

import subprocess
import sys
import os

def test_tool_package_manager():
    """测试ToolPackageManager类的基本功能"""
    print("=== 测试工具包管理系统 ===")
    
    # 检查Java文件是否存在
    tool_manager_path = "app/src/main/java/me/paranoid/wifikill/ToolPackageManager.java"
    if not os.path.exists(tool_manager_path):
        print("❌ ToolPackageManager.java 文件不存在")
        return False
    
    # 检查主要功能
    with open(tool_manager_path, 'r', encoding='utf-8') as f:
        content = f.read()
        
    # 检查关键功能（使用更宽松的匹配）
    required_functions = [
        "isFirstLaunch()",
        "markFirstLaunchCompleted()",
        "isPackageInstalled(ToolPackage pkg)",
        "installPackage(ToolPackage pkg)",
        "uninstallPackage(ToolPackage pkg)",
        "isToolAvailable(String toolName)",
        "getDeviceMonitorCapability()"
    ]
    
    missing_functions = []
    for func in required_functions:
        if func not in content:
            missing_functions.append(func)
    
    if missing_functions:
        print(f"❌ 缺少函数: {missing_functions}")
        return False
    
    print("✅ ToolPackageManager.java 包含所有必需函数")
    
    # 检查工具包定义
    tool_packages = [
        "CORE", "AIRCRACK", "HOSTAPD", "REAVER"
    ]
    
    for pkg in tool_packages:
        if f'{pkg}("' not in content:
            print(f"❌ 缺少工具包定义: {pkg}")
            return False
    
    print("✅ 所有工具包已定义")
    
    # 检查设备能力检测
    if "DeviceMonitorCapability" not in content:
        print("❌ 缺少设备能力检测")
        return False
    
    print("✅ 设备能力检测已实现")
    
    return True

def test_tool_selection_activity():
    """测试ToolSelectionActivity的基本功能"""
    print("\n=== 测试工具选择界面 ===")
    
    # 检查Java文件
    activity_path = "app/src/main/java/me/paranoid/wifikill/ToolSelectionActivity.java"
    if not os.path.exists(activity_path):
        print("❌ ToolSelectionActivity.java 文件不存在")
        return False
    
    with open(activity_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查关键功能
    required_methods = [
        "initPackageSelection()",
        "completeSetup()"
    ]
    
    missing_methods = []
    for method in required_methods:
        if f"private void {method}" not in content:
            missing_methods.append(method)
    
    if missing_methods:
        print(f"❌ 缺少方法: {missing_methods}")
        return False
    
    print("✅ ToolSelectionActivity 包含所有必需方法")
    
    # 检查布局文件
    layout_path = "app/src/main/res/layout/activity_tool_selection.xml"
    if not os.path.exists(layout_path):
        print("❌ activity_tool_selection.xml 布局文件不存在")
        return False
    
    print("✅ 工具选择界面布局文件存在")
    
    # 检查工具包项布局
    item_layout_path = "app/src/main/res/layout/item_tool_package.xml"
    if not os.path.exists(item_layout_path):
        print("❌ item_tool_package.xml 布局文件不存在")
        return False
    
    print("✅ 工具包项布局文件存在")
    
    return True

def test_tool_installer_service():
    """测试ToolInstallerService的基本功能"""
    print("\n=== 测试工具安装服务 ===")
    
    service_path = "app/src/main/java/me/paranoid/wifikill/ToolInstallerService.java"
    if not os.path.exists(service_path):
        print("❌ ToolInstallerService.java 文件不存在")
        return False
    
    with open(service_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查关键功能
    required_methods = [
        "start(Context context, ToolPackageManager.ToolPackage pkg)",
        "downloadPackage(String packageId, String packageName)",
        "createNotificationChannel()",
        "createNotification(String packageName, int progress)"
    ]
    
    missing_methods = []
    for method in required_methods:
        if method not in content:
            missing_methods.append(method)
    
    if missing_methods:
        print(f"❌ 缺少方法: {missing_methods}")
        return False
    
    print("✅ ToolInstallerService 包含所有必需方法")
    
    return True

def test_attack_activity_integration():
    """测试AttackActivity中的工具检查集成"""
    print("\n=== 测试攻击模块集成 ===")
    
    activity_path = "app/src/main/java/me/paranoid/wifikill/AttackActivity.java"
    if not os.path.exists(activity_path):
        print("❌ AttackActivity.java 文件不存在")
        return False
    
    with open(activity_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查工具检查逻辑
    if "isToolAvailable(String tool)" not in content:
        print("❌ AttackActivity 缺少工具检查方法")
        return False
    
    print("✅ AttackActivity 包含工具检查方法")
    
    # 检查设备检测
    if "getDeviceMonitorCapability()" not in content:
        print("❌ AttackActivity 缺少设备检测")
        return False
    
    print("✅ AttackActivity 包含设备检测")
    
    # 检查工具包下载提示
    if "attack_needs_tool_download" not in content:
        print("❌ AttackActivity 缺少工具下载提示")
        return False
    
    print("✅ AttackActivity 包含工具下载提示")
    
    return True

def test_settings_fragment_integration():
    """测试SettingsFragment中的工具包管理集成"""
    print("\n=== 测试设置界面集成 ===")
    
    fragment_path = "app/src/main/java/me/paranoid/wifikill/SettingsFragment.java"
    if not os.path.exists(fragment_path):
        print("❌ SettingsFragment.java 文件不存在")
        return False
    
    with open(fragment_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查工具包管理界面
    if "buildToolsCard()" not in content:
        print("❌ SettingsFragment 缺少工具包管理界面")
        return False
    
    print("✅ SettingsFragment 包含工具包管理界面")
    
    # 检查卸载对话框
    if "showUninstallDialog" not in content:
        print("❌ SettingsFragment 缺少卸载对话框")
        return False
    
    print("✅ SettingsFragment 包含卸载对话框")
    
    return True

def test_string_resources():
    """测试字符串资源"""
    print("\n=== 测试字符串资源 ===")
    
    # 检查中文资源
    zh_strings = "app/src/main/res/values/strings.xml"
    if not os.path.exists(zh_strings):
        print("❌ 中文字符串资源文件不存在")
        return False
    
    with open(zh_strings, 'r', encoding='utf-8') as f:
        content = f.read()
    
    required_strings = [
        "setup_title",
        "setup_disclaimer",
        "setup_agreement",
        "setup_tools_title",
        "setup_continue",
        "settings_tools_title",
        "tools_downloading",
        "tools_uninstall_title",
        "tools_uninstall_confirm",
        "tool_download_started",
        "attack_needs_tool_download",
        "attack_tool_missing_title",
        "attack_tool_missing",
        "action_download",
        "action_recheck"
    ]
    
    missing_strings = []
    for string in required_strings:
        if f'name="{string}"' not in content:
            missing_strings.append(string)
    
    if missing_strings:
        print(f"❌ 缺少中文字符串: {missing_strings}")
        return False
    
    print("✅ 中文字符串资源完整")
    
    # 检查英文资源
    en_strings = "app/src/main/res/values-en/strings.xml"
    if not os.path.exists(en_strings):
        print("❌ 英文字符串资源文件不存在")
        return False
    
    with open(en_strings, 'r', encoding='utf-8') as f:
        content = f.read()
    
    for string in required_strings:
        if f'name="{string}"' not in content:
            print(f"❌ 缺少英文字符串: {string}")
            return False
    
    print("✅ 英文字符串资源完整")
    
    return True

def test_android_manifest():
    """测试AndroidManifest.xml配置"""
    print("\n=== 测试AndroidManifest.xml配置 ===")
    
    manifest_path = "app/src/main/AndroidManifest.xml"
    if not os.path.exists(manifest_path):
        print("❌ AndroidManifest.xml 文件不存在")
        return False
    
    with open(manifest_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 检查ToolSelectionActivity是否注册
    if "ToolSelectionActivity" not in content:
        print("❌ ToolSelectionActivity 未在AndroidManifest.xml中注册")
        return False
    
    print("✅ ToolSelectionActivity 已注册")
    
    return True

def main():
    """运行所有测试"""
    print("开始测试WiFiTester工具包管理系统...")
    
    tests = [
        test_tool_package_manager,
        test_tool_selection_activity,
        test_tool_installer_service,
        test_attack_activity_integration,
        test_settings_fragment_integration,
        test_string_resources,
        test_android_manifest
    ]
    
    passed = 0
    total = len(tests)
    
    for test in tests:
        if test():
            passed += 1
        print()
    
    print(f"\n=== 测试结果 ===")
    print(f"通过: {passed}/{total}")
    
    if passed == total:
        print("🎉 所有测试通过！工具包管理系统实现完整。")
        return 0
    else:
        print("❌ 部分测试失败，请检查实现。")
        return 1

if __name__ == "__main__":
    sys.exit(main())