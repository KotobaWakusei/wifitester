package me.paranoid.wifikill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the privileged environment of the attack workbench: the default
 * route, the station address, the ARP table and the visible access points.
 *
 * <p>Everything here is a pure function over shell output so the target
 * autofill can be unit tested without Android or a device. Nothing is
 * invented: a field without evidence simply stays empty.</p>
 */
final class AttackTargetResolver {

    /** A visible access point with its BSSID, SSID and channel. */
    static final class AccessPoint {
        final String bssid;
        final String ssid;
        final int channel;
        final int rssi;

        AccessPoint(String bssid, String ssid, int channel, int rssi) {
            this.bssid = bssid;
            this.ssid = ssid;
            this.channel = channel;
            this.rssi = rssi;
        }
    }

    /** The device's own view of the network it is attached to. */
    static final class LocalNetwork {
        String gateway = "";
        String routeInterface = "";
        String localAddress = "";
        int prefixLength = -1;

        boolean hasGateway() {
            return !gateway.isEmpty();
        }
    }

    private AttackTargetResolver() {
    }

    // ---- parsing ----------------------------------------------------------

    private static final Pattern LINK_PREFIX = Pattern.compile("\\b(?:PREFIXLEN|prefixlen)\\s+(\\d{1,2})\\b");

    /** "74: wlan0: <BROADCAST,...> mtu 1500" — the interface block header. */
    private static final Pattern LINK_HEADER = Pattern.compile("^\\d+:\\s*([A-Za-z0-9_.-]+?)(?:@\\S+)?:");
    static LocalNetwork parseLocalNetwork(String routeOutput, String addressOutput) {
        LocalNetwork network = new LocalNetwork();
        String firstGateway = "";
        String firstInterface = "";
        if (routeOutput != null) {
            // Android keeps one table per network ("table wlan0", "table
            // rmnet_data4"), so "route show default" is usually empty while
            // "route show table all" lists several defaults. Prefer the Wi-Fi
            // table; otherwise keep the first default route.
            String wifiGateway = "";
            String wifiInterface = "";
            Pattern route = Pattern.compile(
                    "(?m)^default\\s+via\\s+((?:[0-9]{1,3}\\.){3}[0-9]{1,3})\\b[^\\n]*\\bdev\\s+([A-Za-z0-9_.-]+)");
            Matcher matcher = route.matcher(routeOutput);
            while (matcher.find()) {
                String gateway = matcher.group(1);
                String iface = matcher.group(2);
                boolean wifi = iface.startsWith("wlan") || iface.startsWith("wl")
                        || routeOutput.substring(matcher.start(), matcher.end()).contains("table wlan");
                if (firstGateway.isEmpty()) {
                    firstGateway = gateway;
                    firstInterface = iface;
                }
                if (wifi) {
                    wifiGateway = gateway;
                    wifiInterface = iface;
                    break;
                }
            }
            if (!wifiGateway.isEmpty()) {
                network.gateway = wifiGateway;
                network.routeInterface = wifiInterface;
            } else {
                network.gateway = firstGateway;
                network.routeInterface = firstInterface;
            }
        }
        if (addressOutput != null) {
            // Prefer the interface the default route points at: "ip -4 addr
            // show" lists rmnet/vgate addresses first, and the device's own
            // Wi-Fi address is the only one a victim can reach it on.
            String preferred = network.routeInterface;
            String chosenIp = "";
            int chosenPrefix = -1;
            String fallbackIp = "";
            int fallbackPrefix = -1;
            String currentInterface = "";
            String[] lines = addressOutput.split("\\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                java.util.regex.Matcher header = LINK_HEADER.matcher(line);
                if (header.find()) currentInterface = header.group(1);
                int inet = line.indexOf("inet ");
                if (inet < 0) continue;
                String address = line.substring(inet + 5).trim().split("\\s+")[0];
                String ip = address;
                int prefix = -1;
                int slash = address.indexOf('/');
                if (slash > 0) {
                    ip = address.substring(0, slash);
                    try {
                        prefix = Integer.parseInt(address.substring(slash + 1));
                    } catch (NumberFormatException ignored) {
                        prefix = -1;
                    }
                }
                if (!ip.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")) continue;
                if (prefix < 0) {
                    Matcher linkPrefix = LINK_PREFIX.matcher(line);
                    if (linkPrefix.find()) {
                        try {
                            prefix = Integer.parseInt(linkPrefix.group(1));
                        } catch (NumberFormatException ignored) {
                            prefix = -1;
                        }
                    }
                }
                if (ip.startsWith("127.")) continue;
                if (fallbackIp.isEmpty()) {
                    fallbackIp = ip;
                    fallbackPrefix = prefix;
                }
                if (!preferred.isEmpty() && currentInterface.equals(preferred)) {
                    chosenIp = ip;
                    chosenPrefix = prefix;
                    break;
                }
            }
            if (chosenIp.isEmpty()) {
                chosenIp = fallbackIp;
                chosenPrefix = fallbackPrefix;
            }
            network.localAddress = chosenIp;
            network.prefixLength = chosenPrefix;
        }
        return network;
    }

    /**
     * Extracts the IPv4 neighbours from {@code ip -4 neigh show}. Only
     * REACHABLE/STALE/DELAY/PROBE entries without a cache entry are returned;
     * FAILED/INCOMPLETE neighbours have no MAC address.
     */
    static List<String> parseNeighbours(String neighOutput, String exclude) {
        List<String> result = new ArrayList<>();
        if (neighOutput == null) return result;
        Pattern entry = Pattern.compile(
                "(?m)^((?:[0-9]{1,3}\\.){3}[0-9]{1,3})\\s+dev\\s+\\S+\\s+lladdr\\s+([0-9A-Fa-f:]{17})\\s+(\\w+)");
        Matcher matcher = entry.matcher(neighOutput);
        while (matcher.find()) {
            String ip = matcher.group(1);
            String state = matcher.group(3).toUpperCase(Locale.US);
            if ("FAILED".equals(state) || "INCOMPLETE".equals(state)) continue;
            if (exclude != null && exclude.equals(ip)) continue;
            if (!result.contains(ip)) result.add(ip);
        }
        return result;
    }

    /**
     * Parses {@code iw dev <if> scan} output into access points. iw prints one
     * "BSS <mac>" header per access point followed by an SSID line and the
     * channel in the DS Parameter Set IE. Hidden SSIDs are skipped because an
     * empty name cannot be used as a target.
     */
    static List<AccessPoint> parseScan(String scanOutput) {
        List<AccessPoint> result = new ArrayList<>();
        if (scanOutput == null || scanOutput.isEmpty()) return result;
        Pattern bss = Pattern.compile("(?m)^BSS ([0-9A-Fa-f:]{17})\\b");
        Matcher headers = bss.matcher(scanOutput);
        List<int[]> positions = new ArrayList<>();
        List<String> macs = new ArrayList<>();
        while (headers.find()) {
            positions.add(new int[]{headers.start(), headers.end()});
            macs.add(headers.group(1).toLowerCase(Locale.US));
        }
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i)[1];
            int end = i + 1 < positions.size() ? positions.get(i + 1)[0] : scanOutput.length();
            String block = scanOutput.substring(start, end);
            Matcher ds = Pattern.compile("(?m)^\\s*DS Parameter set: channel (\\d{1,3})").matcher(block);
            Matcher primary = Pattern.compile("(?m)^\\s*\\* channel (\\d{1,3})").matcher(block);
            Matcher ssid = Pattern.compile("(?m)^\\s*SSID: (.+)$").matcher(block);
            Matcher signal = Pattern.compile("(?m)^\\s*signal: (-?\\d+(?:\\.\\d+)?) dBm").matcher(block);
            String name = "";
            if (ssid.find()) name = decodeIwdEscapes(ssid.group(1)).trim();
            if (name.isEmpty()) continue;
            int channel = 0;
            if (ds.find()) {
                channel = Integer.parseInt(ds.group(1));
            } else if (primary.find()) {
                // OFDM/hostapd-style block: the first "* channel N" is primary.
                channel = Integer.parseInt(primary.group(1));
            }
            int rssi = Integer.MIN_VALUE;
            if (signal.find()) {
                try {
                    rssi = (int) Math.round(Double.parseDouble(signal.group(1)));
                } catch (NumberFormatException ignored) {
                    rssi = Integer.MIN_VALUE;
                }
            }
            result.add(new AccessPoint(macs.get(i), name, channel, rssi));
        }
        return result;
    }

    /**
     * iw escapes non-ASCII bytes in SSIDs as {@code \xNN} (for example
     * {@code \xe5\x8b\x87}), so a Chinese SSID would otherwise be filled in as
     * literal backslashes. The bytes are decoded as UTF-8; an invalid sequence
     * is left untouched instead of producing replacement characters.
     */
    static String decodeIwdEscapes(String value) {
        if (value == null || value.indexOf("\\x") < 0) return value == null ? "" : value;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 3 < value.length() && value.charAt(i + 1) == 'x') {
                String hex = value.substring(i + 2, i + 4);
                try {
                    bytes.write(Integer.parseInt(hex, 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException notHex) {
                    // Not an escape; keep the backslash.
                }
            }
            byte[] chunk = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            bytes.write(chunk, 0, chunk.length);
        }
        String decoded = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        return decoded.indexOf('\uFFFD') >= 0 ? value : decoded;
    }

    // ---- selection --------------------------------------------------------

    /**
     * Chooses the target access point: the exact BSSID first, then the SSID,
     * then the strongest visible network. Returns null when the scan is empty.
     */
    static AccessPoint chooseTarget(List<AccessPoint> candidates, String preferredBssid, String preferredSsid) {
        if (candidates == null || candidates.isEmpty()) return null;
        String bssid = preferredBssid == null ? "" : preferredBssid.trim().toLowerCase(Locale.US);
        String ssid = preferredSsid == null ? "" : preferredSsid.trim();
        if (!bssid.isEmpty()) {
            for (AccessPoint candidate : candidates) {
                if (candidate.bssid.equals(bssid)) return candidate;
            }
        }
        if (!ssid.isEmpty()) {
            for (AccessPoint candidate : candidates) {
                if (candidate.ssid.equals(ssid)) return candidate;
            }
        }
        AccessPoint best = null;
        for (AccessPoint candidate : candidates) {
            if (candidate.rssi == Integer.MIN_VALUE) continue;
            if (best == null || candidate.rssi > best.rssi) best = candidate;
        }
        return best != null ? best : candidates.get(0);
    }

    /**
     * The regular hosts of the local subnet, without the network/broadcast
     * addresses and without the device itself. Only /24 and shorter prefixes
     * are expanded; the address space is capped at 254 entries.
     */
    static List<String> subnetHosts(LocalNetwork network) {
        List<String> hosts = new ArrayList<>();
        if (network == null || network.localAddress.isEmpty()) return hosts;
        int prefix = network.prefixLength;
        if (prefix < 0) prefix = 24;
        if (prefix < 8 || prefix > 30) return hosts;
        long ip = ipv4ToLong(network.localAddress);
        if (ip < 0) return hosts;
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long networkAddress = ip & mask;
        long broadcast = networkAddress | (~mask & 0xFFFFFFFFL);
        long start = networkAddress + 1;
        long end = broadcast - 1;
        for (long address = start; address <= end && hosts.size() < 254; address++) {
            String candidate = longToIpv4(address);
            if (!candidate.equals(network.localAddress)) hosts.add(candidate);
        }
        return hosts;
    }

    /**
     * Picks the victim candidate for the ARP module: the first neighbour that
     * is neither the gateway nor the device, otherwise the first subnet host
     * that is not the gateway. Returns an empty string when nothing is known.
     */
    static String chooseVictim(LocalNetwork network, List<String> neighbours) {
        if (network == null) return "";
        if (neighbours != null) {
            for (String neighbour : neighbours) {
                if (neighbour.equals(network.gateway) || neighbour.equals(network.localAddress)) continue;
                return neighbour;
            }
        }
        for (String host : subnetHosts(network)) {
            if (host.equals(network.gateway)) continue;
            return host;
        }
        return "";
    }

    static long ipv4ToLong(String address) {
        if (address == null) return -1;
        String[] parts = address.trim().split("\\.");
        if (parts.length != 4) return -1;
        long value = 0;
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return -1;
                value = (value << 8) | octet;
            } catch (NumberFormatException invalid) {
                return -1;
            }
        }
        return value;
    }

    static String longToIpv4(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "."
                + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
