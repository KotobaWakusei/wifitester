package me.paranoid.wifikill;

/**
 * Vendor-independent contract for privileged monitor-mode operations.
 * Future chipset/vendor adapters can implement this interface without coupling
 * driver-specific behavior to the Android UI.
 */
interface MonitorModeBackend {
    String id();
    String probeCommand();
    String buildStartCommand(String parentInterface, String monitorInterface,
                             WifiChannelParser.Channel channel);
    String buildFailedStartCleanupCommand(String parentInterface, String monitorInterface,
                                          String originalState, boolean monitorWasCreated);
    String buildStopCommand(String parentInterface, String monitorInterface, String originalState);
}

/** Standard Linux wireless-tools backend based on iw/ip. */
final class IwMonitorModeBackend implements MonitorModeBackend {
    private static final String LIST_MARKER = "__IW_LIST__";
    private static final String DEV_MARKER = "__IW_DEV__";
    private static final String LINKS_MARKER = "__LINKS__";

    /**
     * Resolves the localized validation messages. The context is optional so
     * that the backend can be unit tested without an Android runtime.
     */
    private final android.content.Context context;

    IwMonitorModeBackend() {
        this(null);
    }

    IwMonitorModeBackend(android.content.Context context) {
        this.context = context;
    }

    private String message(int messageRes, String fallback) {
        return context == null ? fallback : context.getString(messageRes);
    }

    @Override public String id() {
        return "linux-iw";
    }

    @Override public String probeCommand() {
        return "id; echo __IW_PATH__; command -v iw; " +
                "echo __TCPDUMP_PATH__; command -v tcpdump; " +
                "echo __IP_PATH__; command -v ip; " +
                "echo " + LIST_MARKER + "; iw list 2>&1; " +
                "echo " + DEV_MARKER + "; iw dev 2>&1; " +
                "echo " + LINKS_MARKER + "; ip -o link show 2>&1";
    }

    @Override public String buildStartCommand(String parentInterface, String monitorInterface,
                                               WifiChannelParser.Channel channel) {
        String parent = validateInterface(parentInterface);
        String monitor = validateInterface(monitorInterface);
        if (parent.equals(monitor)) {
            throw new IllegalArgumentException(message(R.string.monitor_error_same_interface,
                    "The parent interface and the monitor interface must differ"));
        }
        if (channel == null || channel.number <= 0
                || channel.frequency < 2300 || channel.frequency > 7125) {
            throw new IllegalArgumentException(message(R.string.monitor_error_channel_invalid,
                    "Invalid channel parameter"));
        }

        // Prefer the empirically verified "iw dev <parent> interface add" path.
        // Wiphy discovery is retained as a fallback for drivers that require it.
        return "set -e; IF=" + quoteInterface(parent) + "; MON="
                + quoteInterface(monitor) + "; " +
                "command -v iw >/dev/null; command -v ip >/dev/null; " +
                "if iw dev \"$MON\" info >/dev/null 2>&1; then echo 'ERROR: monitor interface already exists'; exit 7; fi; " +
                "PHY=" + phyIndexCommand() + "; " +
                "if [ -z \"$PHY\" ]; then echo PHY_UNKNOWN; fi; " +
                "echo START_STAGE:parent-down; ip link set \"$IF\" down || { rc=$?; echo PARENT_DOWN_FAILED:$rc; exit $rc; }; " +
                // Preserve each failure code: vendor drivers can reject one iw form
                // but accept another. Never pass an empty PHY to "iw phy".
                "echo START_STAGE:create-netdev-flags; " +
                "if iw dev \"$IF\" interface add \"$MON\" type monitor flags otherbss; then echo MONITOR_CREATE_METHOD:netdev-flags; " +
                "else rc=$?; echo MONITOR_CREATE_NETDEV_FLAGS_FAILED:$rc; " +
                "if iw dev \"$IF\" interface add \"$MON\" type monitor; then echo MONITOR_CREATE_METHOD:netdev; " +
                "else rc=$?; echo MONITOR_CREATE_NETDEV_FAILED:$rc; " +
                "if [ -n \"$PHY\" ] && iw phy \"$PHY\" interface add \"$MON\" type monitor flags otherbss; then echo MONITOR_CREATE_METHOD:phy-flags; " +
                "elif [ -n \"$PHY\" ] && iw phy \"$PHY\" interface add \"$MON\" type monitor; then echo MONITOR_CREATE_METHOD:phy; " +
                "else rc=$?; echo MONITOR_CREATE_PHY_FAILED:$rc; echo MONITOR_CREATE_FAILED; " +
                "iw dev 2>&1; iw list 2>&1 | grep -E 'Wiphy|valid interface combinations' || true; exit \\"$rc\\"; fi; fi; fi; fi; " +
                "echo MONITOR_CREATED; " +
                "if iw dev \"$MON\" set freq " + channel.frequency + " HT20; then :; " +
                "elif iw dev \"$MON\" set freq " + channel.frequency + "; then :; " +
                "elif iw dev \"$MON\" set channel " + channel.number + " HT20; then :; " +
                "elif iw dev \"$MON\" set channel " + channel.number + "; then :; " +
                "else rc=$?; echo CHANNEL_TUNING_FAILED:$rc; " +
                "echo CHANNEL_DIAGNOSTICS_BEGIN; iw dev \"$MON\" info 2>&1; " +
                "iw list 2>&1 | sed -n '/Frequencies:/,/valid interface combinations:/p' | head -n 100; " +
                "echo CHANNEL_DIAGNOSTICS_END; exit $rc; fi; " +

                "ip link set \"$MON\" up || { rc=$?; echo MONITOR_UP_FAILED:$rc; exit $rc; }; echo MONITOR_READY";
    }

    @Override public String buildFailedStartCleanupCommand(String parentInterface,
                                                           String monitorInterface,
                                                           String originalState,
                                                           boolean monitorWasCreated) {
        String parent = quoteInterface(validateInterface(parentInterface));
        String monitor = quoteInterface(validateInterface(monitorInterface));
        String state = normalizedState(originalState);
        StringBuilder command = new StringBuilder();
        if (monitorWasCreated) {
            command.append("ip link set ").append(monitor)
                    .append(" down >/dev/null 2>&1 || true; ")
                    .append("iw dev ").append(monitor)
                    .append(" del >/dev/null 2>&1 || true; ");
        }
        command.append("ip link set ").append(parent).append(" ").append(state)
                .append(" >/dev/null 2>&1 || true");
        return command.toString();
    }

    @Override public String buildStopCommand(String parentInterface, String monitorInterface,
                                             String originalState) {
        String parent = quoteInterface(validateInterface(parentInterface));
        String monitor = quoteInterface(validateInterface(monitorInterface));
        String state = normalizedState(originalState);
        return "ip link set " + monitor + " down >/dev/null 2>&1 || true; " +
                "iw dev " + monitor + " del >/dev/null 2>&1 || true; " +
                "ip link set " + parent + " " + state + " >/dev/null 2>&1 || true; " +
                "echo RESTORE_ATTEMPTED";
    }

    /**
     * Prints the index of the wiphy that owns the parent interface, preceded by
     * a marker line so the raw probe output can also be parsed by the UI.
     */
    /**
     * Parses the wiphy index out of {@code iw dev <if> info} output.
     * Returns null when the driver does not report a wiphy.
     */
    static String parsePhyIndex(String iwDevInfo) {
        if (iwDevInfo == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("wiphy\\s+(\\d+)").matcher(iwDevInfo);
        return m.find() ? m.group(1) : null;
    }

    static String phyIndexCommand() {
        return "PHY_IDX=$(iw dev \"$IF\" info 2>/dev/null | sed -n 's/.*wiphy \\([0-9][0-9]*\\).*/\\1/p' | head -n 1); " +
                "if [ -z \"$PHY_IDX\" ] && [ -d /sys/class/ieee80211 ]; then " +
                "for d in /sys/class/ieee80211/phy*; do " +
                "[ -e \"$d/device/net/$IF\" ] && { PHY_IDX=${d##*/phy}; break; }; done; fi; " +
                "printf '%s' \"$PHY_IDX\"";
    }

    private static String quoteInterface(String validatedInterface) {
        // Called only after validateInterface; the accepted alphabet has no shell metacharacters.
        return "'" + validatedInterface + "'";
    }

    private String validateInterface(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,15}")) {
            throw new IllegalArgumentException(message(R.string.monitor_error_interface_name,
                    "Invalid wireless interface name"));
        }
        return value;
    }

    private static String normalizedState(String state) {
        return "up".equals(state) ? "up" : "down";
    }
}
