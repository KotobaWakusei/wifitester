package me.paranoid.wifikill;

import org.junit.Test;
import static org.junit.Assert.*;

public class IwMonitorModeBackendTest {
    private final IwMonitorModeBackend backend = new IwMonitorModeBackend();
    private final WifiChannelParser.Channel channel =
            new WifiChannelParser.Channel(36, 5180, "Ch 36 · 5180 MHz · 5 GHz");

    @Test public void identifiesTheStandardBackendAndProbeMarkers() {
        assertEquals("linux-iw", backend.id());
        String command = backend.probeCommand();
        assertTrue(command.contains("__IW_LIST__"));
        assertTrue(command.contains("__IW_DEV__"));
        assertTrue(command.contains("__LINKS__"));
        assertTrue(command.contains("command -v iw"));
    }

    @Test public void buildsMonitorCommandUsingValidatedValues() {
        String command = backend.buildStartCommand("wlan0", "wfkmon0", channel);
        assertTrue(command.contains("interface add"));
        assertTrue(command.contains("set freq 5180 HT20"));
        assertTrue(command.contains("set channel 36 HT20"));
        assertTrue(command.contains("MONITOR_READY"));
    }

    /**
     * Dual-PHY devices only accept a monitor interface in the PHY that owns the
     * parent interface, so the command must resolve it and use "iw phy".
     */
    @Test public void createsTheInterfaceInTheParentPhy() {
        String command = backend.buildStartCommand("wlan0", "wfkmon0", channel);
        assertTrue(command.contains("PHY_IDX=$(iw dev \"$IF\" info"));
        assertTrue(command.contains("/sys/class/ieee80211/phy*"));
        assertTrue(command.contains("iw phy \"$PHY\" interface add \"$MON\" type monitor"));
        // A missing wiphy must not abort the attempt: the plain fallback stays.
        assertTrue(command.contains("elif iw dev \"$IF\" interface add \"$MON\" type monitor"));
    }

    @Test public void parsesTheWiphyOfTheParentInterface() {
        String info = "\tInterface wlan1\n\t\tifindex 22\n\t\twiphy 1\n\t\ttype managed\n";
        assertEquals("1", IwMonitorModeBackend.parsePhyIndex(info));
        assertEquals("0", IwMonitorModeBackend.parsePhyIndex("\tInterface wlan0\n\t\twiphy 0\n"));
        assertNull(IwMonitorModeBackend.parsePhyIndex("Interface lo\n\ttype loopback"));
        assertNull(IwMonitorModeBackend.parsePhyIndex(null));
    }

    /** A failed creation has to print diagnostics instead of dying silently. */
    @Test public void creationFailureEmitsDiagnostics() {
        String command = backend.buildStartCommand("wlan0", "wfkmon0", channel);
        assertTrue(command.contains("MONITOR_CREATE_FAILED:$rc"));
        assertTrue(command.contains("valid interface combinations"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShellMetacharactersInInterfaceName() {
        backend.buildStartCommand("wlan0;reboot", "wfkmon0", channel);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSameParentAndMonitorInterface() {
        backend.buildStartCommand("wlan0", "wlan0", channel);
    }

    @Test public void cleanupOnlyDeletesAnInterfaceThisAttemptCreated() {
        String noMonitor = backend.buildFailedStartCleanupCommand("wlan0", "wfkmon0", "up", false);
        assertFalse(noMonitor.contains("iw dev 'wfkmon0' del"));
        assertTrue(noMonitor.contains("ip link set 'wlan0' up"));

        String withMonitor = backend.buildFailedStartCleanupCommand("wlan0", "wfkmon0", "down", true);
        assertTrue(withMonitor.contains("iw dev 'wfkmon0' del"));
        assertTrue(withMonitor.contains("ip link set 'wlan0' down"));
    }

    @Test public void stopCommandRestoresOriginalStateAndUsesFixedMarkers() {
        String command = backend.buildStopCommand("wlan0", "wfkmon0", "up");
        assertTrue(command.contains("iw dev 'wfkmon0' del"));
        assertTrue(command.contains("ip link set 'wlan0' up"));
        assertTrue(command.contains("RESTORE_ATTEMPTED"));
    }
}
