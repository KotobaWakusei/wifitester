package me.paranoid.wifikill;

import java.util.ArrayList;

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
    /** Single tune command used by the channel-hopping loop. Null when the channel is invalid. */
    String buildTuneCommand(String monitorInterface, WifiChannelParser.Channel channel);
}

/** Standard Linux wireless-tools backend based on iw/ip. */
final class IwMonitorModeBackend implements MonitorModeBackend {
    private static final String LIST_MARKER = "__IW_LIST__";
    private static final String DEV_MARKER = "__IW_DEV__";
    private static final String LINKS_MARKER = "__LINKS__";

    /**
     * Reports the Qualcomm QCACLD global monitor switch as {@code CON_MODE:<path>}
     * or {@code CON_MODE:none}. The module name differs between platforms
     * (peach_v2, kiwi_v2, ...), so the parameter is discovered instead of
     * hard coded. Only the exact "parameters/con_mode" file is accepted.
     */
    static final String CON_MODE_DISCOVERY =
            "CM=$(for p in /sys/module/*/parameters/con_mode; do "
                    + "[ -w \"$p\" ] && { printf '%s' \"$p\"; break; }; done 2>/dev/null); "
                    + "echo CON_MODE:${CM:-none}; ";

    /** Prints a discovery result in the same format as {@link #CON_MODE_DISCOVERY}. */
    static String conModeDiscoveryLine(String path) {
        return "CON_MODE:" + (path == null || path.trim().isEmpty() ? "none" : path.trim());
    }

    /**
     * Returns the con_mode path when {@code path} is an absolute, plain path
     * below /sys/module, or null. The value ends up in a root shell, so the
     * accepted alphabet is deliberately narrow.
     */
    static String validatedConModePath(String path) {
        if (path == null) return null;
        String trimmed = path.trim();
        if (!trimmed.matches("/sys/module/[A-Za-z0-9_]+/parameters/con_mode")) return null;
        return trimmed;
    }

    /**
     * Shell variable (MODE) holding the validated con_mode path, or null when
     * the path is unusable. Every restore path keys off this variable.
     */
    private static String conModePrelude(String conModePath) {
        String validated = validatedConModePath(conModePath);
        if (validated == null) return null;
        return "MODE='" + validated + "'; BACKUP=/data/local/tmp/wifitester-con_mode.backup; ";
    }

    /**
     * Parses {@code CON_MODE:<path>} (or {@code CON_MODE:none}) out of a probe
     * report. Returns null when the report holds no discovery line at all.
     */
    static String parseConModePath(String probeOutput) {
        if (probeOutput == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^CON_MODE:(\\S*)\\s*$").matcher(probeOutput);
        if (!m.find()) return null;
        String value = m.group(1);
        return "none".equals(value) ? null : validatedConModePath(value);
    }

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
                "echo " + LINKS_MARKER + "; ip -o link show 2>&1; " +
                CON_MODE_DISCOVERY;
    }

    @Override public String buildStartCommand(String parentInterface, String monitorInterface,
                                               WifiChannelParser.Channel channel) {
        return buildStartCommand(parentInterface, monitorInterface, channel, null);
    }

    /**
     * Builds the start command. {@code conModePath} is the QCACLD global monitor
     * switch discovered by {@link #CON_MODE_DISCOVERY}; when present, a global
     * con_mode start can be attempted after every virtual-interface form failed.
     *
     * <p>Sequence verified on a OnePlus 15 (peach_v2, Android 16):</p>
     * <ul>
     *   <li>A virtual monitor interface is created through the parent netdev,
     *       but only while the framework is not driving the PHY. With Wi-Fi
     *       left on, "interface add" fails with EINVAL and the channel tune of
     *       the new interface fails with EBUSY, so Wi-Fi is switched off first
     *       (the same state a user reaches through the Wi-Fi quick setting).</li>
     *   <li>The interface must be brought up before "set channel"; tuning a
     *       DOWN interface fails with EBUSY.</li>
     *   <li>If every virtual interface was refused, con_mode 4 turns the
     *       existing parent netdev into a monitor interface instead. It is
     *       receive-only, captures on the parent, and leaves no interface to
     *       delete on stop.</li>
     * </ul>
     */
    String buildStartCommand(String parentInterface, String monitorInterface,
                             WifiChannelParser.Channel channel, String conModePath) {
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

        String conModePrelude = conModePrelude(conModePath);
        String conModeStep = conModePrelude == null ? "" : buildConModeStartStep(channel, conModePrelude);

        // Prefer the empirically verified "iw dev <parent> interface add" path.
        // Wiphy discovery is retained as a fallback for drivers that require it.
        return "set -e; IF=" + quoteInterface(parent) + "; ORIG_STATE=down; MON="
                + quoteInterface(monitor) + "; " + conModePrelude +
                "command -v iw >/dev/null; command -v ip >/dev/null; " +
                "if iw dev \"$MON\" info >/dev/null 2>&1; then echo 'ERROR: monitor interface already exists'; exit 7; fi; " +
                "PHY=$(" + phyIndexCommand() + "); " +
                "if [ -z \"$PHY\" ]; then echo PHY_UNKNOWN; fi; " +
                // The framework keeps a station vdev on the PHY; while it does,
                // peach_v2 rejects a monitor vdev (EINVAL) and refuses to tune
                // one (EBUSY). Switching Wi-Fi off first is what makes the
                // virtual interface path work. WifiRecovery re-enables it after
                // the stop; the state is logged so cleanup can report it.
                "echo START_STAGE:wifi-off; " +
                "WIFI_WAS_ON=$(cmd wifi status 2>/dev/null | head -n 1 | grep -c 'Wifi is enabled'); " +
                "echo WIFI_WAS_ENABLED:$WIFI_WAS_ON; " +
                "if [ \"$WIFI_WAS_ON\" = 1 ]; then svc wifi disable >/dev/null 2>&1 || true; " +
                "N=0; while [ \"$N\" -lt 15 ] && cmd wifi status 2>/dev/null | head -n 1 | grep -q 'Wifi is enabled'; do N=$((N+1)); sleep 0.2; done; fi; " +
                "sleep 0.5; " +
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
                "else rc=$?; echo MONITOR_CREATE_PHY_FAILED:$rc; " +
                // Qualcomm QCACLD builds may expose only a global con_mode switch and
                // reject every secondary monitor vdev. Use that receive-only path only
                // when the driver exposes the parameter; the previous mode is saved.
                conModeStep +
                "echo MONITOR_CREATE_FAILED; iw dev 2>&1; iw list 2>&1 | grep -E 'Wiphy|valid interface combinations' || true; exit \"$rc\"; fi; fi; fi; " +
                "echo MONITOR_CREATED; " +
                // A DOWN interface refuses "set channel" with EBUSY on peach_v2,
                // so the link comes up first and is tuned afterwards.
                "ip link set \"$MON\" up || { rc=$?; echo MONITOR_UP_FAILED:$rc; exit $rc; }; " +
                "if iw dev \"$MON\" set freq " + channel.frequency + " HT20 2>/dev/null; then echo MONITOR_TUNED:freq; " +
                "elif iw dev \"$MON\" set freq " + channel.frequency + " 2>/dev/null; then echo MONITOR_TUNED:freq; " +
                "elif iw dev \"$MON\" set channel " + channel.number + " HT20 2>/dev/null; then echo MONITOR_TUNED:channel; " +
                "elif iw dev \"$MON\" set channel " + channel.number + " 2>/dev/null; then echo MONITOR_TUNED:channel; " +
                "else rc=$?; echo CHANNEL_TUNING_FAILED:$rc; " +
                "echo CHANNEL_DIAGNOSTICS_BEGIN; iw dev \"$MON\" info 2>&1; " +
                "iw list 2>&1 | sed -n '/Frequencies:/,/#{/p' | head -n 100; " +
                "echo CHANNEL_DIAGNOSTICS_END; exit $rc; fi; " +
                "echo MONITOR_READY";
    }

    @Override public String buildFailedStartCleanupCommand(String parentInterface,
                                                           String monitorInterface,
                                                           String originalState,
                                                           boolean monitorWasCreated) {
        return buildFailedStartCleanupCommand(parentInterface, monitorInterface, originalState,
                monitorWasCreated, null);
    }

    String buildFailedStartCleanupCommand(String parentInterface, String monitorInterface,
                                          String originalState, boolean monitorWasCreated,
                                          String conModePath) {
        String parent = quoteInterface(validateInterface(parentInterface));
        String monitor = quoteInterface(validateInterface(monitorInterface));
        String state = normalizedState(originalState);
        String prelude = conModePrelude(conModePath);
        StringBuilder command = new StringBuilder();
        if (prelude != null) {
            // Only the con_mode backup restore belongs inside the guard. The
            // interface cleanup and the Wi-Fi re-enable below must run for
            // every failed start, including the common case where the start
            // never reached the con_mode fallback (no backup file exists).
            command.append(prelude)
                    .append("if [ -f \"$BACKUP\" ]; then ")
                    .append("ip link set ").append(parent).append(" down >/dev/null 2>&1 || true; ")
                    .append("cat \"$BACKUP\" > \"$MODE\" 2>/dev/null || true; rm -f \"$BACKUP\"; ")
                    .append("fi; ");
        }
        // A failed start may have stopped halfway through the creation chain,
        // before "MONITOR_CREATED" was printed. Check the live state instead of
        // trusting only the marker, otherwise a stray monitor interface keeps
        // the PHY and makes every retry fail.
        if (monitorWasCreated) {
            command.append("ip link set ").append(monitor)
                    .append(" down >/dev/null 2>&1 || true; ")
                    .append("iw dev ").append(monitor)
                    .append(" del >/dev/null 2>&1 || true; ");
        } else {
            command.append("if iw dev ").append(monitor).append(" info >/dev/null 2>&1; then ")
                    .append("ip link set ").append(monitor).append(" down >/dev/null 2>&1 || true; ")
                    .append("iw dev ").append(monitor).append(" del >/dev/null 2>&1 || true; fi; ");
        }
        command.append("ip link set ").append(parent).append(" ").append(state)
                .append(" >/dev/null 2>&1 || true;");
        // The start switched Wi-Fi off before touching the PHY; a failed start
        // must bring the radio back before reporting the error.
        command.append(" svc wifi enable >/dev/null 2>&1 || true;");
        command.append(" echo FAILED_START_CLEANUP");
        return command.toString();
    }

    @Override public String buildStopCommand(String parentInterface, String monitorInterface,
                                             String originalState) {
        return buildStopCommand(parentInterface, monitorInterface, originalState, null);
    }

    @Override public String buildTuneCommand(String monitorInterface,
                                             WifiChannelParser.Channel channel) {
        if (channel == null || channel.number <= 0
                || channel.frequency < 2300 || channel.frequency > 7125) {
            return null;
        }
        String monitor = validateInterface(monitorInterface);
        // Same fallback chain as the start command: "set freq" is accepted by
        // more drivers than "set channel", and HT20 must be dropped on some.
        return "iw dev " + monitor + " set freq " + channel.frequency + " HT20 2>/dev/null || "
                + "iw dev " + monitor + " set freq " + channel.frequency + " 2>/dev/null || "
                + "iw dev " + monitor + " set channel " + channel.number + " HT20 2>/dev/null || "
                + "iw dev " + monitor + " set channel " + channel.number + " 2>/dev/null";
    }

    String buildStopCommand(String parentInterface, String monitorInterface,
                            String originalState, String conModePath) {
        String parent = quoteInterface(validateInterface(parentInterface));
        String monitor = quoteInterface(validateInterface(monitorInterface));
        String state = normalizedState(originalState);
        String prelude = conModePrelude(conModePath);
        StringBuilder command = new StringBuilder();
        if (prelude != null) command.append(prelude);
        command.append("if ").append(prelude == null ? "false" : "[ -f \"$BACKUP\" ]")
                .append("; then ")
                .append("ip link set ").append(parent).append(" down >/dev/null 2>&1 || true; ")
                .append("cat \"$BACKUP\" > \"$MODE\"; RC=$?; rm -f \"$BACKUP\"; ")
                .append("ip link set ").append(parent).append(" ").append(state).append(" >/dev/null 2>&1 || true; ")
                .append("echo CON_MODE_RESTORED:$RC; ")
                .append("else ip link set ").append(monitor).append(" down >/dev/null 2>&1 || true; ")
                .append("iw dev ").append(monitor).append(" del >/dev/null 2>&1 || true; ")
                .append("ip link set ").append(parent).append(" ").append(state).append(" >/dev/null 2>&1 || true; fi; ")
                // The start switched Wi-Fi off to free the PHY; restore it here
                // instead of relying on the framework to notice the new vdev.
                .append("svc wifi enable >/dev/null 2>&1 || echo WIFI_ENABLE_FAILED; ")
                .append("echo RESTORE_ATTEMPTED");
        return command.toString();
    }

    /**
     * The global con_mode fallback step. Entered only after every "interface
     * add" form failed, and only when the driver exposes the parameter.
     */
    private static String buildConModeStartStep(WifiChannelParser.Channel channel, String prelude) {
        return "if [ ! -e \"$BACKUP\" ] && [ -w \"$MODE\" ]; then " +
                "echo START_STAGE:con-mode; " +
                "OLD_MODE=$(cat \"$MODE\" 2>/dev/null | tr -cd '0-7'); [ -n \"$OLD_MODE\" ] || OLD_MODE=0; " +
                "printf '%s\\n' \"$OLD_MODE\" > \"$BACKUP\" || exit 31; " +
                // Restores the saved mode and the original link state.
                "CM_FAIL() { RC=$1; MSG=$2; echo \"$MSG\"; ip link set \"$IF\" down >/dev/null 2>&1 || true; " +
                "cat \"$BACKUP\" > \"$MODE\" 2>/dev/null || true; rm -f \"$BACKUP\"; " +
                "ip link set \"$IF\" \"$ORIG_STATE\" >/dev/null 2>&1 || true; exit \"$RC\"; }; " +
                // con_mode 4 = monitor. The parent netdev turns into a monitor
                // interface; nothing new is created, so cleanup must not delete it.
                "echo 4 > \"$MODE\" 2>/dev/null || CM_FAIL 32 CON_MODE_SET_FAILED; " +
                // The interface needs a moment (sub-second on peach_v2) to be
                // re-registered as radiotap/monitor; tuning before that fails.
                "N=0; until iw dev \"$IF\" info 2>/dev/null | grep -q 'type monitor'; do " +
                "N=$((N+1)); if [ \"$N\" -ge 30 ]; then CM_FAIL 33 CON_MODE_NOT_APPLIED; fi; sleep 0.2; done; " +
                "ip link set \"$IF\" up 2>/dev/null || CM_FAIL 34 MONITOR_UP_FAILED; " +
                // "set channel" before the link is up fails with EBUSY.
                "if iw dev \"$IF\" set freq " + channel.frequency + " HT20 2>/dev/null || " +
                "iw dev \"$IF\" set freq " + channel.frequency + " 2>/dev/null || " +
                "iw dev \"$IF\" set channel " + channel.number + " 2>/dev/null; then " +
                "echo MONITOR_PARENT_MODE; echo MONITOR_READY; exit 0; " +
                "else CM_FAIL 35 CHANNEL_TUNING_FAILED; fi; " +
                "fi; ";
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
