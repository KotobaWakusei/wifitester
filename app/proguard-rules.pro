# Keep rules for the release build.
#
# The workbenches are created by their fully qualified class name
# (Fragment.instantiate) and the activities are referenced from the
# manifest, so neither may be renamed or stripped.

-keep public class me.paranoid.wifikill.MainActivity
-keep public class me.paranoid.wifikill.NetworkDetailActivity
-keep public class me.paranoid.wifikill.MonitorModeActivity
-keep public class me.paranoid.wifikill.AttackActivity
-keep public class me.paranoid.wifikill.AircrackToolkitActivity
-keep public class me.paranoid.wifikill.FrameAnalysisActivity
-keep public class me.paranoid.wifikill.WiFiAuditActivity
-keep public class me.paranoid.wifikill.AircrackNgActivity

-keep public class me.paranoid.wifikill.WifiListFragment
-keep public class me.paranoid.wifikill.ToolsFragment
-keep public class me.paranoid.wifikill.SettingsFragment

# The fragments are instantiated reflectively.
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <init>();
}

# Keep the line numbers of the diagnostic log for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
