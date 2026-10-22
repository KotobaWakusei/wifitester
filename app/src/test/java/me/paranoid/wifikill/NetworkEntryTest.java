package me.paranoid.wifikill;

import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkEntryTest {

    private static NetworkEntry entry(String ssid, String capabilities, int level, int frequency) {
        return new NetworkEntry(ssid, "aa:bb:cc:dd:ee:ff", capabilities, level, frequency, 0L);
    }

    @Test public void classifiesSecurityByCapability() {
        assertEquals(NetworkEntry.Security.WPA3, entry("a", "[WPA3]", -50, 5180).securityKind());
        assertEquals(NetworkEntry.Security.WPA2, entry("a", "[WPA2-PSK-CCMP]", -50, 2412).securityKind());
        assertEquals(NetworkEntry.Security.WPA, entry("a", "[WPA-PSK-TKIP]", -50, 2412).securityKind());
        assertEquals(NetworkEntry.Security.ENTERPRISE, entry("a", "[WPA2-EAP+FT/EAP]", -50, 2412).securityKind());
        assertEquals(NetworkEntry.Security.WEP, entry("a", "[WEP]", -50, 2412).securityKind());
        assertEquals(NetworkEntry.Security.OPEN, entry("a", "[ESS]", -50, 2412).securityKind());
        assertEquals(NetworkEntry.Security.UNKNOWN, entry("a", "", -50, 2412).securityKind());
    }

    @Test public void detectsWeaklyProtectedNetworks() {
        assertTrue(entry("a", "[ESS]", -50, 2412).isWeaklyProtected());
        assertTrue(entry("a", "[WEP]", -50, 2412).isWeaklyProtected());
        assertFalse(entry("a", "[WPA2-PSK-CCMP]", -50, 2412).isWeaklyProtected());
    }

    @Test public void mapsFrequenciesToBandsAndChannels() {
        assertEquals(NetworkEntry.Band.GHZ2, entry("a", "[WPA2]", -50, 2412).bandKind());
        assertEquals(1, entry("a", "[WPA2]", -50, 2412).channel());
        assertEquals(6, entry("a", "[WPA2]", -50, 2437).channel());
        assertEquals(14, entry("a", "[WPA2]", -50, 2484).channel());
        assertEquals(NetworkEntry.Band.GHZ5, entry("a", "[WPA2]", -50, 5180).bandKind());
        assertEquals(36, entry("a", "[WPA2]", -50, 5180).channel());
        assertEquals(NetworkEntry.Band.GHZ6, entry("a", "[WPA2]", -50, 5955).bandKind());
        assertEquals(1, entry("a", "[WPA2]", -50, 5955).channel());
        assertEquals(NetworkEntry.Band.UNKNOWN, entry("a", "[WPA2]", -50, 0).bandKind());
        assertEquals(-1, entry("a", "[WPA2]", -50, 0).channel());
    }

    @Test public void labelsSignalQuality() {
        assertEquals(NetworkEntry.Signal.EXCELLENT, entry("a", "[WPA2]", -45, 2412).signalKind());
        assertEquals(NetworkEntry.Signal.STRONG, entry("a", "[WPA2]", -55, 2412).signalKind());
        assertEquals(NetworkEntry.Signal.FAIR, entry("a", "[WPA2]", -65, 2412).signalKind());
        assertEquals(NetworkEntry.Signal.WEAK, entry("a", "[WPA2]", -75, 2412).signalKind());
        assertEquals(NetworkEntry.Signal.VERY_WEAK, entry("a", "[WPA2]", -90, 2412).signalKind());
    }

    @Test public void marksHiddenNetworks() {
        assertTrue(entry("", "[ESS]", -50, 2412).isHidden());
        assertTrue(entry("", "[ESS]", -50, 2412).isHidden());
        assertEquals("", entry("", "[ESS]", -50, 2412).displaySsid());
        assertFalse(entry("Net", "[ESS]", -50, 2412).isHidden());
    }

    @Test public void detectsEnterpriseNetworks() {
        assertTrue(entry("a", "[WPA2-EAP]", -50, 2412).isEnterprise());
        assertFalse(entry("a", "[WPA2-PSK]", -50, 2412).isEnterprise());
    }
}
