package me.paranoid.wifikill;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.Arrays;
import java.util.List;

/**
 * 管理工具包的下载、删除和状态检查。
 */
public final class ToolPackageManager {
    private static final String PREFS_NAME = "tool_packages";
    private static final String KEY_FIRST_LAUNCH = "first_launch";
    private static final String CONFIG_FILE = "tool_packages/config.json";
    /**
     * Packages installed by versions <= 2.5.1 only set a flag while creating
     * empty placeholder files, so the flag alone must not be trusted.
     */
    private static final String KEY_VERIFIED_PREFIX = "verified_";
    private static final String KEY_PENDING_PREFIX = "pending_";
    private JSONObject config;
    
    public enum ToolPackage {
        CORE("core", "核心工具包", Arrays.asList("tcpdump", "arpspoof"), true),
        AIRCRACK("aircrack", "Aircrack-ng 套件", Arrays.asList("aircrack-ng", "airodump-ng", "aireplay-ng", "airmon-ng"), true),
        // iw is provided by Android/system tools and is not part of this package archive.
        HOSTAPD("hostapd", "HostAPd 虚拟接入点", Arrays.asList("hostapd"), true);

        public final String id;
        public final String name;
        public final List<String> tools;
        public final boolean preInstalled;

        ToolPackage(String id, String name, List<String> tools, boolean preInstalled) {
            this.id = id;
            this.name = name;
            this.tools = tools;
            this.preInstalled = preInstalled;
        }

        /** Total size of the tool files actually extracted for this package. */
        public long getSizeBytes(Context context) {
            long total = 0;
            for (String tool : tools) {
                File toolFile = new File(context.getFilesDir(), "toolchain/bin/" + tool);
                if (toolFile.isFile()) {
                    total += toolFile.length();
                }
            }
            return total;
        }

        public String getSizeFormatted(Context context) {
            return formatSize(getSizeBytes(context));
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private final Context context;
    private final SharedPreferences prefs;

    public ToolPackageManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadConfig();
    }
    
    private void loadConfig() {
        try {
            InputStream inputStream = context.getAssets().open(CONFIG_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            reader.close();
            inputStream.close();
            
            config = new JSONObject(stringBuilder.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 是否首次启动 */
    public boolean isFirstLaunch() {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true);
    }

    /** 标记已启动过 */
    public void markFirstLaunchCompleted() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
    }

    /**
     * 检查工具包是否已安装。
     *
     * <p>A package only counts as installed when every one of its tools exists
     * as a real, non-empty executable. Older releases recorded the flag without
     * downloading anything, so the flag is verified against the files the first
     * time it is seen.</p>
     */
    public boolean isPackageInstalled(ToolPackage pkg) {
        // BundledToolchain extracts optional binaries shipped inside the APK.
        // Detect those files even when no download preference has ever been set;
        // otherwise the first-launch UI incorrectly offers a download for tools
        // that are already present locally.
        if (isPackageComplete(pkg)) {
            if (!prefs.getBoolean("pkg_" + pkg.id, false)
                    || !prefs.getBoolean(KEY_VERIFIED_PREFIX + pkg.id, false)) {
                prefs.edit()
                        .putBoolean("pkg_" + pkg.id, true)
                        .putBoolean(KEY_VERIFIED_PREFIX + pkg.id, true)
                        .remove(KEY_PENDING_PREFIX + pkg.id)
                        .apply();
            }
            return true;
        }
        // A previous verification is not proof that the files still exist.
        if (prefs.getBoolean("pkg_" + pkg.id, false)) {
            prefs.edit()
                    .remove("pkg_" + pkg.id)
                    .remove(KEY_VERIFIED_PREFIX + pkg.id)
                    .apply();
        }
        return false;
    }

    /** Records that the user asked for a package; it is not installed yet. */
    public void requestInstall(ToolPackage pkg) {
        prefs.edit().putBoolean(KEY_PENDING_PREFIX + pkg.id, true).apply();
    }

    /** Whether a download was requested but has not finished yet. */
    public boolean hasPendingInstall(ToolPackage pkg) {
        return prefs.getBoolean(KEY_PENDING_PREFIX + pkg.id, false)
                && !isPackageInstalled(pkg);
    }

    /** 标记工具包已安装，仅在文件全部就位后调用 */
    public void installPackage(ToolPackage pkg) {
        prefs.edit()
                .putBoolean("pkg_" + pkg.id, true)
                .putBoolean(KEY_VERIFIED_PREFIX + pkg.id, true)
                .remove(KEY_PENDING_PREFIX + pkg.id)
                .apply();
    }

    /** 卸载工具包，返回被删除的字节数 */
    public long uninstallPackage(ToolPackage pkg) {
        if (pkg.preInstalled) return 0; // 核心工具包不可卸载
        long freed = getSizeBytes(pkg);
        prefs.edit()
                .remove("pkg_" + pkg.id)
                .remove(KEY_VERIFIED_PREFIX + pkg.id)
                .remove(KEY_PENDING_PREFIX + pkg.id)
                .apply();
        deletePackageFiles(pkg);
        return freed;
    }

    /** 工具包当前占用的空间 */
    public long getSizeBytes(ToolPackage pkg) {
        return pkg.getSizeBytes(context);
    }

    /**
     * Deletes the 0 byte placeholder files written by releases <= 2.5.1 and
     * clears the install flag that was set without any download. Their size is
     * returned so callers can report what was reclaimed.
     */
    /**
     * Only files the app owns are touched; a leftover file created with other
     * permissions (for example by a root shell) is deleted lazily on install.
     */
    public long cleanUpLegacyPlaceholders() {
        long removed = 0;
        for (ToolPackage pkg : ToolPackage.values()) {
            for (String tool : pkg.tools) {
                File toolFile = new File(context.getFilesDir(), "toolchain/bin/" + tool);
                if (!toolFile.isFile()) continue;
                if (isToolFileReady(toolFile)) continue;
                long length = toolFile.length();
                if (toolFile.canWrite() && toolFile.delete()) removed += length;
            }
            if (prefs.getBoolean("pkg_" + pkg.id, false) && !isPackageComplete(pkg)) {
                prefs.edit()
                        .remove("pkg_" + pkg.id)
                        .remove(KEY_VERIFIED_PREFIX + pkg.id)
                        .apply();
            }
            // 内置工具包的文件由应用自己解包，无需下载标记即可视为已安装
            if (pkg.preInstalled && isPackageComplete(pkg)) {
                prefs.edit()
                        .putBoolean("pkg_" + pkg.id, true)
                        .putBoolean(KEY_VERIFIED_PREFIX + pkg.id, true)
                        .apply();
            }
        }
        return removed;
    }

    /** 检查工具是否可用 */
    public boolean isToolAvailable(String toolName) {
        for (ToolPackage pkg : ToolPackage.values()) {
            if (isPackageInstalled(pkg) && pkg.tools.contains(toolName)) {
                return isToolFileReady(new File(context.getFilesDir(), "toolchain/bin/" + toolName));
            }
        }
        return false;
    }
    
    /** 检查工具包是否完整安装 */
    public boolean isPackageComplete(ToolPackage pkg) {
        for (String tool : pkg.tools) {
            File toolFile = new File(context.getFilesDir(), "toolchain/bin/" + tool);
            if (!isToolFileReady(toolFile)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A usable tool is a real executable binary. The 0 byte placeholders written
     * by earlier releases, and files that happen to be executable but are not
     * ELF binaries (or scripts), are rejected.
     */
    private static boolean isToolFileReady(File toolFile) {
        if (!toolFile.isFile() || toolFile.length() < MIN_TOOL_BYTES || !toolFile.canExecute()) {
            return false;
        }
        byte[] magic = new byte[4];
        int read;
        try (java.io.FileInputStream in = new java.io.FileInputStream(toolFile)) {
            read = in.read(magic);
        } catch (java.io.IOException e) {
            return false;
        }
        if (read < 2) return false;
        boolean elf = read == 4 && (magic[0] & 0xff) == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        boolean script = magic[0] == '#' && magic[1] == '!';
        return elf || script;
    }

    /** Smallest plausible binary size; placeholders are 0 bytes. */
    private static final int MIN_TOOL_BYTES = 64;
    
    /** 获取设备监听模式支持情况 */
    public DeviceMonitorCapability getDeviceMonitorCapability() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        
        // 检查Qualcomm芯片组
        if (isQualcommDevice()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // QCACLD-3.0 通常支持监听但不支持注入
                return DeviceMonitorCapability.MONITOR_ONLY;
            } else {
                // 较老的QCACLD-2.0可能支持监听+注入
                return DeviceMonitorCapability.FULL_MONITOR_INJECTION;
            }
        }
        
        // 检查Broadcom芯片组
        if (manufacturer.contains("samsung") || manufacturer.contains("lg")) {
            return DeviceMonitorCapability.FULL_MONITOR_INJECTION; // 假设有NexMon支持
        }
        
        // 检查MediaTek芯片组
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || 
            manufacturer.contains("realme") || manufacturer.contains("oppo")) {
            return DeviceMonitorCapability.MONITOR_ONLY;
        }
        
        // 华为等封闭系统
        if (manufacturer.contains("huawei")) {
            return DeviceMonitorCapability.NO_MONITOR;
        }
        
        // 默认情况
        return DeviceMonitorCapability.UNKNOWN;
    }
    
    /** 检查是否为Qualcomm设备 */
    private boolean isQualcommDevice() {
        String board = Build.BOARD;
        String brand = Build.BRAND;
        String device = Build.DEVICE;
        
        return board.contains("qcom") || brand.contains("qcom") || 
               device.contains("qcom") || device.contains("msm");
    }
    
    /** 设备监听模式支持能力 */
    public enum DeviceMonitorCapability {
        FULL_MONITOR_INJECTION,    // 监听+注入
        MONITOR_ONLY,              // 仅监听
        NO_MONITOR,                // 不支持
        REQUIRES_OTG,              // 需要外接网卡
        UNKNOWN                    // 未知
    }
    
    /** 获取设备监听模式支持描述 */
    public String getDeviceMonitorDescription() {
        DeviceMonitorCapability capability = getDeviceMonitorCapability();
        
        switch (capability) {
            case FULL_MONITOR_INJECTION:
                return "设备支持完整的监听和注入功能";
            case MONITOR_ONLY:
                return "设备支持监听模式，但不支持帧注入";
            case NO_MONITOR:
                return "设备不支持Wi-Fi监听模式，建议使用OTG外接网卡";
            case REQUIRES_OTG:
                return "建议使用OTG外接WiFi适配器以获得完整功能";
            case UNKNOWN:
            default:
                return "无法确定设备监听模式支持情况";
        }
    }

    private void deletePackageFiles(ToolPackage pkg) {
        for (String tool : pkg.tools) {
            File toolFile = new File(context.getFilesDir(), "toolchain/bin/" + tool);
            if (toolFile.isFile()) {
                toolFile.delete();
            }
        }
    }

    /** 获取所有工具包状态 */
    public List<ToolPackage> getAllPackages() {
        return Arrays.asList(ToolPackage.values());
    }
    
    /** 从配置文件获取工具包信息 */
    public JSONObject getPackageInfo(String packageId) {
        try {
            if (config != null && config.has("packages")) {
                return config.getJSONObject("packages").getJSONObject(packageId);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 工具包的下载地址；未配置时返回空字符串。
     * 地址中的 {version} 会替换为当前发布标签（BuildConfig.TOOL_PACKAGE_TAG）。
     */
    public String getDownloadUrl(ToolPackage pkg) {
        JSONObject info = getPackageInfo(pkg.id);
        if (info == null) return "";
        String url = info.optString("download_url", "").trim();
        return url.replace("{version}", BuildConfig.TOOL_PACKAGE_TAG);
    }
    
    /** 获取设备支持信息 */
    public JSONObject getDeviceInfo(String chipset) {
        try {
            if (config != null && config.has("supported_devices")) {
                return config.getJSONObject("supported_devices").getJSONObject(chipset);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    /** 获取所有支持的设备类型 */
    public String[] getSupportedDeviceTypes() {
        try {
            if (config != null && config.has("supported_devices")) {
                org.json.JSONArray names = config.getJSONObject("supported_devices").names();
                if (names != null) {
                    String[] result = new String[names.length()];
                    for (int i = 0; i < names.length(); i++) {
                        result[i] = names.getString(i);
                    }
                    return result;
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return new String[0];
    }
}