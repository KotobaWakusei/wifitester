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
    private JSONObject config;
    
    public enum ToolPackage {
        CORE("core", "核心工具包", Arrays.asList("tcpdump", "arpspoof"), true),
        AIRCRACK("aircrack", "Aircrack-ng 套件", Arrays.asList("aircrack-ng", "airodump-ng"), false),
        HOSTAPD("hostapd", "HostAPd 虚拟接入点", Arrays.asList("hostapd", "iw"), false),
        REAVER("reaver", "Reaver WPS 攻击", Arrays.asList("reaver", "bully"), false);

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

        public long getSizeBytes(Context context) {
            long total = 0;
            for (String tool : tools) {
                File toolFile = new File(context.getFilesDir(), "toolchain/" + tool);
                if (toolFile.exists()) {
                    total += toolFile.length();
                }
            }
            return total;
        }

        public String getSizeFormatted(Context context) {
            long bytes = getSizeBytes(context);
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
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

    /** 检查工具包是否已安装 */
    public boolean isPackageInstalled(ToolPackage pkg) {
        if (pkg.preInstalled) return true;
        return prefs.getBoolean("pkg_" + pkg.id, false);
    }

    /** 安装工具包 */
    public void installPackage(ToolPackage pkg) {
        prefs.edit().putBoolean("pkg_" + pkg.id, true).apply();
    }

    /** 卸载工具包 */
    public void uninstallPackage(ToolPackage pkg) {
        if (pkg.preInstalled) return; // 核心工具包不可卸载
        prefs.edit().remove("pkg_" + pkg.id).apply();
        deletePackageFiles(pkg);
    }

    /** 检查工具是否可用 */
    public boolean isToolAvailable(String toolName) {
        for (ToolPackage pkg : ToolPackage.values()) {
            if (isPackageInstalled(pkg) && pkg.tools.contains(toolName)) {
                File toolFile = new File(context.getFilesDir(), "toolchain/" + toolName);
                return toolFile.exists() && toolFile.canExecute();
            }
        }
        return false;
    }
    
    /** 检查工具包是否完整安装 */
    public boolean isPackageComplete(ToolPackage pkg) {
        if (!isPackageInstalled(pkg)) return false;
        
        for (String tool : pkg.tools) {
            File toolFile = new File(context.getFilesDir(), "toolchain/" + tool);
            if (!toolFile.exists() || !toolFile.canExecute()) {
                return false;
            }
        }
        return true;
    }
    
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
            File toolFile = new File(context.getFilesDir(), "toolchain/" + tool);
            if (toolFile.exists()) {
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