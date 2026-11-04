package me.paranoid.wifikill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Pure command assembly for the adb debug bridge.
 *
 * <p>Kept free of Android framework calls (only the paths are handed in) so
 * the parameter handling and validation can be covered by unit tests. The
 * commands are exactly what {@link AttackCommandBuilder} validates; this
 * class only maps bridge parameter names onto that builder.</p>
 */
final class DebugBridgeCommands {

    /** Recognised attack module ids (mirrors AttackCatalog.ID_*). */
    static final String MODULE_DEAUTH = "deauth";
    static final String MODULE_BEACON = "beacon";
    static final String MODULE_AUTH = "auth";
    static final String MODULE_HANDSHAKE = "handshake";
    static final String MODULE_CRACK = "crack";
    static final String MODULE_EVIL_TWIN = "evil_twin";
    static final String MODULE_ARP_SPOOF = "arp_spoof";

    private DebugBridgeCommands() {
    }

    /** Whether the module has to run through a monitor-mode interface. */
    static boolean requiresMonitor(String module) {
        return MODULE_DEAUTH.equals(module) || MODULE_BEACON.equals(module)
                || MODULE_AUTH.equals(module) || MODULE_HANDSHAKE.equals(module)
                || MODULE_EVIL_TWIN.equals(module);
    }

    /**
     * Builds the shell command for one attack module.
     *
     * @param module        one of the MODULE_* ids
     * @param monIface      detected monitor interface (required when {@link
     *                      #requiresMonitor(String)} is true)
     * @param bssid         target BSSID
     * @param client        optional client MAC (deauth only)
     * @param channel       channel (handshake, evil_twin)
     * @param count         frame count (deauth, beacon, auth)
     * @param ssid          SSID (evil_twin; falls back to the BSSID)
     * @param passphrase    WPA passphrase (evil_twin)
     * @param wordlist      dictionary path (crack)
     * @param victim        victim IPv4 (arp_spoof)
     * @param gateway       gateway IPv4 (arp_spoof)
     * @param dataIface     data interface (arp_spoof)
     * @param capturePrefix absolute output prefix for handshake captures
     * @param captureFile   absolute .cap path for the crack module (may be null)
     * @param hostapdConf   absolute hostapd.conf path for evil_twin
     * @return the validated shell command
     * @throws AttackCommandBuilder.ValidationException on invalid parameters
     * @throws IllegalArgumentException on invalid parameters / unknown module
     */
    static String buildCommand(String module, String monIface, String bssid, String client,
                               int channel, int count, String ssid, String passphrase,
                               String wordlist, String victim, String gateway,
                               String dataIface, String capturePrefix, String captureFile,
                               String hostapdConf) {
        switch (module) {
            case MODULE_DEAUTH:
                return AttackCommandBuilder.deauth("aireplay-ng", monIface,
                        bssid, client, count);
            case MODULE_BEACON:
                // The beacon module runs the bundled dedicated injector
                // (beaconflood); the ssid extra is the SSID to advertise and
                // count maps to the number of access points.
                return AttackCommandBuilder.beaconFlood("beaconflood", monIface,
                        ssid, count);
            case MODULE_AUTH:
                return AttackCommandBuilder.authFlood("aireplay-ng", monIface,
                        bssid, count);
            case MODULE_HANDSHAKE:
                return AttackCommandBuilder.captureHandshake("airodump-ng", monIface,
                        bssid, channel, capturePrefix);
            case MODULE_CRACK:
                if (captureFile == null || captureFile.isEmpty()) {
                    throw new IllegalArgumentException(
                            "no capture file: run the handshake capture first");
                }
                return AttackCommandBuilder.crackHandshake("aircrack-ng",
                        captureFile, wordlist);
            case MODULE_EVIL_TWIN: {
                String content = AttackCommandBuilder.buildHostapdConfig(monIface,
                        "nl80211",
                        ssid == null || ssid.trim().isEmpty() ? bssid : ssid,
                        channel, false, passphrase, false);
                writeConfig(hostapdConf, content);
                return AttackCommandBuilder.evilTwin("hostapd", hostapdConf);
            }
            case MODULE_ARP_SPOOF:
                return AttackCommandBuilder.arpSpoof("arpspoof", dataIface,
                        victim, gateway);
            default:
                throw new IllegalArgumentException("unknown module: " + module);
        }
    }

    private static void writeConfig(String path, String content) {
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(path), java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(content);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("cannot write hostapd config: "
                    + failure.getMessage());
        }
    }
}
