package me.paranoid.wifikill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only inventory of tools commonly used by the Aircrack-ng workflow. */
final class ToolCatalog {
    private static final String MARKER = "WIFITESTER_TOOL";
    private static final String[] NAMES = {
            "aircrack-ng", "airodump-ng", "airmon-ng", "tcpdump",
            "iw", "ip", "capinfos", "tshark"
    };
    private static final String[] PURPOSES = {
            "离线抓包审计；本应用不会自动发起口令尝试",
            "被动无线捕获；能力依赖驱动与接口",
            "监听接口辅助；不替代驱动能力验证",
            "被动捕获与 PCAP 读取",
            "无线接口、模式与频道管理",
            "网络接口状态管理",
            "抓包元数据检查",
            "抓包协议与流量统计"
    };

    private ToolCatalog() { }

    static String probeCommand() {
        return "for t in aircrack-ng airodump-ng airmon-ng tcpdump iw ip capinfos tshark; do " +
                "p=$(command -v \"$t\" 2>/dev/null || true); " +
                "if [ -z \"$p\" ]; then " +
                "for q in /data/data/com.termux/files/usr/bin/\"$t\" /system/bin/\"$t\" " +
                "/usr/bin/\"$t\" /usr/sbin/\"$t\"; do " +
                "[ -x \"$q\" ] && { p=\"$q\"; break; }; done; fi; " +
                "printf 'WIFITESTER_TOOL\\t%s\\t%s\\n' \"$t\" \"$p\"; done";
    }

    static List<Tool> parse(String output) {
        Map<String, String> paths = new LinkedHashMap<>();
        if (output != null) {
            for (String line : output.split("\\r?\\n")) {
                String[] parts = line.split("\\t", 3);
                if (parts.length != 3 || !MARKER.equals(parts[0])) continue;
                String name = parts[1].trim();
                if (!isKnownName(name)) continue;
                String path = parts[2].trim();
                paths.put(name, path.isEmpty() || "not found".equalsIgnoreCase(path) ? null : path);
            }
        }
        ArrayList<Tool> result = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            String name = NAMES[i];
            result.add(new Tool(name, PURPOSES[i], paths.get(name)));
        }
        return result;
    }

    static String format(String output) {
        StringBuilder result = new StringBuilder();
        for (Tool tool : parse(output)) {
            if (result.length() > 0) result.append('\n');
            result.append(tool.name).append("  ")
                    .append(tool.isAvailable() ? "✓ 已找到" : "× 未找到")
                    .append("\n    ").append(tool.purpose);
            if (tool.isAvailable()) {
                String path = tool.path;
                String origin = path.contains("/files/toolchain/bin/") ? "APK 内置"
                        : path.contains("/com.termux/") ? "Termux/第三方"
                        : "系统或外部工具";
                result.append("\n    路径：").append(path).append("（").append(origin).append("）");
            }
        }
        return result.toString();
    }

    private static boolean isKnownName(String value) {
        for (String name : NAMES) if (name.equals(value)) return true;
        return false;
    }

    static final class Tool {
        final String name;
        final String purpose;
        final String path;

        Tool(String name, String purpose, String path) {
            this.name = name;
            this.purpose = purpose;
            this.path = path;
        }

        boolean isAvailable() { return path != null && path.startsWith("/"); }
    }
}
