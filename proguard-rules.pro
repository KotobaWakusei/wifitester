# WiFiTester ProGuard 规则

# 保留工具包管理相关类
-keep class me.paranoid.wifikill.ToolPackageManager { *; }
-keep class me.paranoid.wifikill.ToolInstallerService { *; }
-keep class me.paranoid.wifikill.ToolSelectionActivity { *; }
-keepclassmembers class me.paranoid.wifikill.ToolPackageManager { *; }
-keepclassmembers class me.paranoid.wifikill.ToolInstallerService { *; }
-keepclassmembers class me.paranoid.wifikill.ToolSelectionActivity { *; }

# 保留设备检测相关类
-keep class me.paranoid.wifikill.** { *; }

# 保留字符串资源
-keepclassmembers class me.paranoid.wifikill.R$string { *; }

# 保留BuildConfig
-keep class me.paranoid.wifikill.BuildConfig { *; }

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留序列化
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留反射使用
-keepattributes *Annotation*, InnerClasses
-keepattributes Signature
-keepattributes EnclosingMethod

# 保留Native方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留JSON处理相关类
-keep class org.json.** { *; }

# 保留HTTP客户端相关类
-keep class com.squareup.okhttp3.** { *; }
-dontwarn com.squareup.okhttp3.**
