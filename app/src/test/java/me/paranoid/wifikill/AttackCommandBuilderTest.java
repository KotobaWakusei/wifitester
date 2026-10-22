package me.paranoid.wifikill;

import org.junit.Test;
import static org.junit.Assert.*;

public class AttackCommandBuilderTest {

    // ---- validation -------------------------------------------------------

    @Test public void rejectsMalformedMacAddresses() {
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireMac("aa:bb:cc:dd:ee", R.string.attack_label_target_bssid));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireMac("aa:bb:cc:dd:ee:gg", R.string.attack_label_target_bssid));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireMac("; rm -rf /", R.string.attack_label_target_bssid));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireMac(null, R.string.attack_label_target_bssid));
    }

    @Test public void normalisesMacAddressesToLowerCase() {
        assertEquals("aa:bb:cc:dd:ee:ff",
                AttackCommandBuilder.requireMac("AA:BB:CC:DD:EE:FF", R.string.attack_label_target_bssid));
    }

    @Test public void rejectsInterfaceNamesWithShellMetacharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireInterface("wlan0; reboot"));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireInterface("`id`"));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireInterface("a".repeat(16)));
        assertEquals("wlan0", AttackCommandBuilder.requireInterface("wlan0"));
    }

    @Test public void rejectsPathsThatAreNotAbsoluteOrSafe() {
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requirePath("relative/path", R.string.attack_label_capture_file));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requirePath("/tmp/$(id)", R.string.attack_label_capture_file));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requirePath("/tmp/a b", R.string.attack_label_capture_file));
        assertEquals("/data/local/tmp/x.pcap",
                AttackCommandBuilder.requirePath("/data/local/tmp/x.pcap", R.string.attack_label_capture_file));
    }

    @Test public void rejectsSsidsThatAreTooLongOrContainControlCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireSsid("a".repeat(33)));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireSsid("bad\nssid"));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireSsid("   "));
        assertEquals("MyNetwork", AttackCommandBuilder.requireSsid("MyNetwork"));
    }

    @Test public void rejectsInvalidIpv4Gateways() {
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireIpv4("192.168.1"));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireIpv4("192.168.1.256"));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireIpv4("192.168.1.1 && reboot"));
        assertEquals("192.168.1.1", AttackCommandBuilder.requireIpv4(" 192.168.1.1 "));
    }

    @Test public void rejectsFrameCountsOutsideTheAllowedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireCount(-1, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> AttackCommandBuilder.requireCount(100, 0, 10));
        assertEquals(0, AttackCommandBuilder.requireCount(0, 0, 100));
    }

    // ---- module commands --------------------------------------------------

    @Test public void buildsDeauthCommandWithAndWithoutClient() {
        String command = AttackCommandBuilder.deauth(
                "aireplay-ng", "wfkmon0", "aa:bb:cc:dd:ee:ff", null, 10);
        assertEquals("aireplay-ng --deauth 10 -a aa:bb:cc:dd:ee:ff -i wfkmon0", command);

        String targeted = AttackCommandBuilder.deauth(
                "aireplay-ng", "wfkmon0", "aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66", 0);
        assertTrue(targeted.endsWith("-c 11:22:33:44:55:66"));
        assertTrue(targeted.contains("--deauth 0"));
    }

    @Test public void buildsBeaconAndAuthFloodCommands() {
        String beacon = AttackCommandBuilder.beaconFlood(
                "aireplay-ng", "wfkmon0", "aa:bb:cc:dd:ee:ff", 5);
        assertEquals("aireplay-ng --fakeauth 5 -i wfkmon0 -a aa:bb:cc:dd:ee:ff", beacon);

        String auth = AttackCommandBuilder.authFlood(
                "aireplay-ng", "wfkmon0", "aa:bb:cc:dd:ee:ff", 5);
        assertTrue(auth.contains("--auth"));
        assertTrue(auth.contains("-a aa:bb:cc:dd:ee:ff"));
    }

    @Test public void buildsHandshakeCaptureCommand() {
        String command = AttackCommandBuilder.captureHandshake(
                "airodump-ng", "wfkmon0", "aa:bb:cc:dd:ee:ff", 6, "/data/user/0/app/files/out");
        assertTrue(command.contains("--bssid aa:bb:cc:dd:ee:ff"));
        assertTrue(command.contains("-c 6"));
        assertTrue(command.contains("-w /data/user/0/app/files/out"));
    }

    @Test public void rejectsInvalidChannelForHandshakeCapture() {
        assertThrows(IllegalArgumentException.class, () -> AttackCommandBuilder.captureHandshake(
                "airodump-ng", "wfkmon0", "aa:bb:cc:dd:ee:ff", 0, "/data/local/tmp/out"));
        assertThrows(IllegalArgumentException.class, () -> AttackCommandBuilder.captureHandshake(
                "airodump-ng", "wfkmon0", "aa:bb:cc:dd:ee:ff", 6, "relative"));
    }

    @Test public void buildsOfflineCrackCommand() {
        String command = AttackCommandBuilder.crackHandshake(
                "aircrack-ng", "/data/local/tmp/cap.pcap", "/data/local/tmp/wordlist.txt");
        assertEquals("aircrack-ng -w /data/local/tmp/wordlist.txt /data/local/tmp/cap.pcap", command);
    }

    @Test public void buildsHostapdConfigurationForAPrivateNetwork() {
        String config = AttackCommandBuilder.buildHostapdConfig(
                "wfkmon0", "nl80211", "TestNet", 6, false, "supersecret", false);
        assertTrue(config.contains("interface=wfkmon0"));
        assertTrue(config.contains("ssid=TestNet"));
        assertTrue(config.contains("channel=6"));
        assertTrue(config.contains("wpa=2"));
        assertTrue(config.contains("wpa_passphrase=supersecret"));
        assertTrue(config.contains("hw_mode=g"));
    }

    @Test public void hostapdConfigurationRejectsWeakPassphrases() {
        assertThrows(IllegalArgumentException.class, () -> AttackCommandBuilder.buildHostapdConfig(
                "wfkmon0", "nl80211", "TestNet", 6, false, "short", false));
        assertThrows(IllegalArgumentException.class, () -> AttackCommandBuilder.buildHostapdConfig(
                "wfkmon0", "nl80211", "TestNet", 6, false, null, false));
    }

    @Test public void hostapdConfigurationSupportsOpenNetworks() {
        String config = AttackCommandBuilder.buildHostapdConfig(
                "wfkmon0", "nl80211", "OpenNet", 1, true, null, true);
        assertTrue(config.contains("ignore_broadcast_ssid=1"));
        assertTrue(config.contains("wpa=0"));
        assertTrue(config.contains("auth_algs=1"));
    }

    @Test public void buildsArpSpoofAndForwardingCommands() {
        String spoof = AttackCommandBuilder.arpSpoof(
                "arpspoof", "wlan0", "192.168.1.100", "192.168.1.1");
        assertEquals("arpspoof -i wlan0 -t 192.168.1.100 192.168.1.1", spoof);

        String enable = AttackCommandBuilder.enableForwarding("wlan0", "192.168.1.1");
        assertTrue(enable.contains("net.ipv4.ip_forward=1"));
        assertTrue(enable.contains("MASQUERADE"));

        String disable = AttackCommandBuilder.disableForwarding("wlan0", "192.168.1.1");
        assertTrue(disable.contains("net.ipv4.ip_forward=0"));
        assertTrue(disable.contains("-D POSTROUTING"));
    }

    @Test public void arpSpoofRejectsInvalidAddresses() {
        assertThrows(IllegalArgumentException.class, () -> AttackCommandBuilder.arpSpoof(
                "arpspoof", "wlan0", "not-an-ip", "192.168.1.1"));
        assertThrows(IllegalArgumentException.class, () -> AttackCommandBuilder.arpSpoof(
                "arpspoof", "wlan0", "192.168.1.100", "999.1.1.1"));
        assertThrows(IllegalArgumentException.class, () -> AttackCommandBuilder.arpSpoof(
                "arpspoof", "wlan0; reboot", "192.168.1.100", "192.168.1.1"));
    }

    @Test public void quotesValuesSafely() {
        assertEquals("'plain'", AttackCommandBuilder.quote("plain"));
        assertEquals("'a'\\''b'", AttackCommandBuilder.quote("a'b"));
    }
}
