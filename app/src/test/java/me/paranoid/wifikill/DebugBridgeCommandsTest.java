package me.paranoid.wifikill;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Parameter mapping and validation of the adb debug bridge. */
public class DebugBridgeCommandsTest {

    private static final String MON = "wfkmon0";

    @Test public void deauthPlacesTheInterfaceLast() {
        String command = DebugBridgeCommands.buildCommand(
                DebugBridgeCommands.MODULE_DEAUTH, MON,
                "aa:bb:cc:dd:ee:ff", null, 6, 10,
                null, null, null, null, null, null,
                "/data/prefix", null, "/data/hostapd.conf");
        assertEquals("aireplay-ng --ignore-negative-one --deauth 10 -a aa:bb:cc:dd:ee:ff wfkmon0", command);
    }

    @Test public void deauthWithClientKeepsTheInterfaceLast() {
        String command = DebugBridgeCommands.buildCommand(
                DebugBridgeCommands.MODULE_DEAUTH, MON,
                "aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66", 6, 0,
                null, null, null, null, null, null,
                "/data/prefix", null, "/data/hostapd.conf");
        assertTrue(command.endsWith("-c 11:22:33:44:55:66 wfkmon0"));
    }

    @Test public void invalidBssidIsRejected() {
        try {
            DebugBridgeCommands.buildCommand(
                    DebugBridgeCommands.MODULE_DEAUTH, MON,
                    "aa:bb:cc:dd:ee", null, 6, 10,
                    null, null, null, null, null, null,
                    "/data/prefix", null, "/data/hostapd.conf");
            throw new AssertionError("expected validation to fail");
        } catch (IllegalArgumentException expected) {
            // AttackCommandBuilder.ValidationException extends IAE.
        }
    }

    @Test public void handshakeAcceptsSixGigahertzChannels() {
        String command = DebugBridgeCommands.buildCommand(
                DebugBridgeCommands.MODULE_HANDSHAKE, MON,
                "aa:bb:cc:dd:ee:ff", null, 233, 0,
                null, null, null, null, null, null,
                "/data/prefix", null, "/data/hostapd.conf");
        assertTrue(command.contains("-c 233"));
        assertTrue(command.endsWith("wfkmon0"));
        assertFalse(command.contains(" -i "));
    }

    @Test public void crackUsesTheProvidedCaptureFile() {
        String command = DebugBridgeCommands.buildCommand(
                DebugBridgeCommands.MODULE_CRACK, MON,
                null, null, 0, 0,
                null, null, "/data/wordlist.txt", null, null, null,
                "/data/prefix", "/data/capture.cap", "/data/hostapd.conf");
        assertEquals("aircrack-ng -w /data/wordlist.txt /data/capture.cap", command);
    }

    @Test public void crackWithoutACaptureFileIsRefused() {
        try {
            DebugBridgeCommands.buildCommand(
                    DebugBridgeCommands.MODULE_CRACK, MON,
                    null, null, 0, 0,
                    null, null, "/data/wordlist.txt", null, null, null,
                    "/data/prefix", null, "/data/hostapd.conf");
            throw new AssertionError("expected refusal without a capture file");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no capture file"));
        }
    }

    @Test public void arpSpoofDoesNotNeedTheMonitorInterface() {
        assertFalse(DebugBridgeCommands.requiresMonitor(DebugBridgeCommands.MODULE_ARP_SPOOF));
        String command = DebugBridgeCommands.buildCommand(
                DebugBridgeCommands.MODULE_ARP_SPOOF, "",
                null, null, 0, 0,
                null, null, null, "192.168.1.100", "192.168.1.1", "wlan0",
                "/data/prefix", null, "/data/hostapd.conf");
        assertEquals("arpspoof -i wlan0 -t 192.168.1.100 192.168.1.1", command);
    }

    @Test public void unknownModuleIsRefused() {
        try {
            DebugBridgeCommands.buildCommand(
                    "nuclear", MON, null, null, 0, 0,
                    null, null, null, null, null, null,
                    "/data/prefix", null, "/data/hostapd.conf");
            throw new AssertionError("expected refusal for an unknown module");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknown module"));
        }
    }
}
