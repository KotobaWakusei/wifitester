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
                "if [ -n \"$p\" ]; then " +
                "out=$(\"$p\" --help 2>&1 | head -2); " +
                "case \"$out\" in " +
                "*'CANNOT LINK EXECUTABLE'*|*'underaligned'*|*'Permission denied'*) " +
                "p=\"CANNOT RUN\";; " +
                "esac; fi; " +
                "printf 'WIFITESTER_TOOL\\t%s\\t%s\\n' \"$t\" \"$p\"; done";
    }

    /**
     * Runs every bundled tool once and reports whether the loader accepted it.
     *
     * <p>A file can be present and executable and still fail to start: an
     * under-aligned TLS segment, a missing shared library or a CPU feature the
     * binary assumes all show up only when the binary is executed. The
     * inventory used to stop at "found", which hid exactly those failures.</p>
     */
    static String selfTestCommand() {
        return "for t in aircrack-ng airodump-ng aireplay-ng airmon-ng tcpdump arpspoof; do "
                + "out=$(\"$t\" --help 2>&1 | head -2); "
                + "case \"$out\" in "
                + "*'CANNOT LINK EXECUTABLE'*|*'underaligned'*|*'not found'*|*'Permission denied'*) "
                + "printf 'WIFITESTER_SELFTEST\\t%s\\tFAIL\\t%s\\n' \"$t\" \"$(echo \"$out\" | head -1)\";; "
                + "*) printf 'WIFITESTER_SELFTEST\\t%s\\tOK\\t\\n' \"$t\";; "
                + "esac; done";
    }

    /** Formats the output of {@link #selfTestCommand()} for the log view. */
    static String formatSelfTest(Context context, String output) {
        StringBuilder result = new StringBuilder();
        if (output != null) {
            for (String line : output.split("\\r?\\n")) {
                String[] parts = line.split("\\t", 4);
                if (parts.length < 3 || !"WIFITESTER_SELFTEST".equals(parts[0])) continue;
                boolean ok = "OK".equals(parts[2]);
                result.append(ok ? "✓ " : "✗ ").append(parts[1]);
                if (!ok) {
                    result.append("  ").append(parts.length > 3 ? parts[3] : "");
                }
                result.append('\n');
            }
        }
        if (result.length() == 0) {
            result.append(context.getString(R.string.inventory_selftest_empty));
        }
        return result.toString().trim();
    }

    static List<Tool> parse(String output) {
        Map<String, String> paths = new LinkedHashMap<>();
        Map<String, String> statuses = new LinkedHashMap<>();
        if (output != null) {
            for (String line : output.split("\\r?\\n")) {
                String[] parts = line.split("\\t", 3);
                if (parts.length != 3 || !MARKER.equals(parts[0])) continue;
                String name = parts[1].trim();
                if (!isKnownName(name)) continue;
                String path = parts[2].trim();
                if ("CANNOT RUN".equals(path)) {
                    statuses.put(name, path);
                    path = null;
                } else if (path.isEmpty() || "not found".equalsIgnoreCase(path)) {
                    path = null;
                }
                paths.put(name, path);
            }
        }
        ArrayList<Tool> result = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            String name = NAMES[i];
            result.add(new Tool(name, PURPOSE_RES[i], paths.get(name), statuses.get(name)));
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
            if (tool.isAvailable()) {
                result.append(tool.name).append("  ")
                        .append(context.getString(R.string.tool_found))
                        .append("\n    ").append(context.getString(tool.purposeRes));
                String path = tool.path;
                String origin = path.contains("/files/toolchain/bin/")
                        ? context.getString(R.string.tool_origin_bundled)
                        : path.contains("/com.termux/")
                        ? context.getString(R.string.tool_origin_termux)
                        : context.getString(R.string.tool_origin_system);
                result.append("\n    ").append(context.getString(R.string.tool_path_label))
                        .append(path).append("(").append(origin).append(")");
            } else if (tool.isPresent() && "CANNOT RUN".equals(tool.status)) {
                result.append(tool.name).append("  ")
                        .append(context.getString(R.string.tool_cannot_run))
                        .append("\n    ").append(context.getString(tool.purposeRes));
                result.append("\n    ").append(context.getString(R.string.tool_cannot_run_detail));
            } else {
                result.append(tool.name).append("  ")
                        .append(context.getString(R.string.tool_missing))
                        .append("\n    ").append(context.getString(tool.purposeRes));
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
        final String status; // null=missing, "CANNOT RUN"=present but won't start, otherwise the path

        Tool(String name, int purposeRes, String path, String status) {
            this.name = name;
            this.purposeRes = purposeRes;
            this.path = path;
            this.status = status;
        }

        boolean isAvailable() { return path != null && path.startsWith("/"); }
        /** True when the tool was found on disk, even if it cannot start. */
        boolean isPresent() { return path != null || status != null; }
    }
}
