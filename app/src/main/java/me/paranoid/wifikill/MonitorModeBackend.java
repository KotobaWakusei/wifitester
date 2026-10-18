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
            throw new IllegalArgumentException("父接口与监听接口不能相同");
        }
        if (channel == null || channel.number <= 0
                || channel.frequency < 2300 || channel.frequency > 7125) {
            throw new IllegalArgumentException("频道参数无效");
        }

        return "set -e; IF=" + BundledToolchain.quote(parent) + "; MON="
                + BundledToolchain.quote(monitor) + "; " +
                "command -v iw >/dev/null; command -v ip >/dev/null; " +
                "if iw dev \"$MON\" info >/dev/null 2>&1; then echo 'ERROR: monitor interface already exists'; exit 7; fi; " +
                "ip link set \"$IF\" down; " +
                "if ! iw dev \"$IF\" interface add \"$MON\" type monitor flags otherbss; then " +
                "iw dev \"$IF\" interface add \"$MON\" type monitor || { rc=$?; echo MONITOR_CREATE_FAILED:$rc; exit $rc; }; fi; " +
                "echo MONITOR_CREATED; " +
                "iw dev \"$MON\" set freq " + channel.frequency + " HT20 || { freq_rc=$?; echo CHANNEL_FREQ_FAILED:$freq_rc; " +
                "iw dev \"$MON\" set channel " + channel.number + " HT20 || { rc=$?; echo CHANNEL_SET_FAILED:$rc; " +
                "echo CHANNEL_DIAGNOSTICS_BEGIN; iw dev \"$MON\" info 2>&1; " +
                "iw list 2>&1 | sed -n '/Frequencies:/,/valid interface combinations:/p' | head -n 100; " +
                "echo CHANNEL_DIAGNOSTICS_END; exit $rc; }; }; " +
                "ip link set \"$MON\" up || { rc=$?; echo MONITOR_UP_FAILED:$rc; exit $rc; }; echo MONITOR_READY";
    }

    @Override public String buildFailedStartCleanupCommand(String parentInterface,
                                                           String monitorInterface,
                                                           String originalState,
                                                           boolean monitorWasCreated) {
        String parent = BundledToolchain.quote(validateInterface(parentInterface));
        String monitor = BundledToolchain.quote(validateInterface(monitorInterface));
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
        String parent = BundledToolchain.quote(validateInterface(parentInterface));
        String monitor = BundledToolchain.quote(validateInterface(monitorInterface));
        String state = normalizedState(originalState);
        return "ip link set " + monitor + " down >/dev/null 2>&1 || true; " +
                "iw dev " + monitor + " del >/dev/null 2>&1 || true; " +
                "ip link set " + parent + " " + state + " >/dev/null 2>&1 || true; " +
                "echo RESTORE_ATTEMPTED";
    }

    private static String quoteInterface(String validatedInterface) {
        // Called only after validateInterface; the accepted alphabet has no shell metacharacters.
        return "'" + validatedInterface + "'";
    }

    private static String validateInterface(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,15}")) {
            throw new IllegalArgumentException("无线接口名格式无效");
        }
        return value;
    }

    private static String normalizedState(String state) {
        return "up".equals(state) ? "up" : "down";
    }
}
