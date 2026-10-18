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
