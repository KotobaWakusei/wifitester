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
