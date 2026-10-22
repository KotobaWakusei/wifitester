package me.paranoid.wifikill;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only inventory of tools commonly used by the Aircrack-ng workflow. */
final class ToolCatalog {
    private static final String MARKER = "WIFITESTER_TOOL";
    private static final String[] NAMES = {
            "aircrack-ng", "airodump-ng", "aireplay-ng", "airmon-ng", "tcpdump",
            "iw", "ip", "capinfos", "tshark", "hostapd", "arpspoof"
    };
    private static final int[] PURPOSE_RES = {
            R.string.tool_purpose_aircrack,
            R.string.tool_purpose_airodump,
            R.string.tool_purpose_aireplay,
            R.string.tool_purpose_airmon,
            R.string.tool_purpose_tcpdump,
            R.string.tool_purpose_iw,
            R.string.tool_purpose_ip,
            R.string.tool_purpose_capinfos,
            R.string.tool_purpose_tshark,
            R.string.tool_purpose_hostapd,
            R.string.tool_purpose_arpspoof
    };

    private ToolCatalog() { }

    static String probeCommand() {
        return "for t in aircrack-ng airodump-ng aireplay-ng airmon-ng tcpdump iw ip " +
                "capinfos tshark hostapd arpspoof; do " +
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
            result.add(new Tool(name, PURPOSE_RES[i], paths.get(name)));
        }
        return result;
    }

    /**
     * Renders the inventory report.
     *
     * @param context used to resolve the localized labels
     * @param output  the raw output of {@link #probeCommand()}
     */
    static String format(Context context, String output) {
        StringBuilder result = new StringBuilder();
        for (Tool tool : parse(output)) {
            if (result.length() > 0) result.append('\n');
            result.append(tool.name).append("  ")
                    .append(tool.isAvailable()
                            ? context.getString(R.string.tool_found)
                            : context.getString(R.string.tool_missing))
                    .append("\n    ").append(context.getString(tool.purposeRes));
            if (tool.isAvailable()) {
                String path = tool.path;
                String origin = path.contains("/files/toolchain/bin/")
                        ? context.getString(R.string.tool_origin_bundled)
                        : path.contains("/com.termux/")
                        ? context.getString(R.string.tool_origin_termux)
                        : context.getString(R.string.tool_origin_system);
                result.append("\n    ").append(context.getString(R.string.tool_path_label))
                        .append(path).append("(").append(origin).append(")");
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
        final int purposeRes;
        final String path;

        Tool(String name, int purposeRes, String path) {
            this.name = name;
            this.purposeRes = purposeRes;
            this.path = path;
        }

        boolean isAvailable() { return path != null && path.startsWith("/"); }
    }
}
