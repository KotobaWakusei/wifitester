package me.paranoid.wifikill;

import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    /** The OnePlus 15's verified parent-netdev form must be tried first. */
    @Test public void createsTheInterfaceThroughParentNetdevFirst() {
        String command = backend.buildStartCommand("wlan0", "wfkmon0", channel);
        assertTrue(command.contains("PHY_IDX=$(iw dev \"$IF\" info"));
        assertTrue(command.contains("/sys/class/ieee80211/phy*"));
        assertTrue(command.indexOf("iw dev \"$IF\" interface add \"$MON\" type monitor flags otherbss")
                < command.indexOf("iw phy \"$PHY\" interface add \"$MON\" type monitor flags otherbss"));
        assertTrue(command.contains("if iw dev \"$IF\" interface add \"$MON\" type monitor; then"));
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
        assertTrue(command.contains("MONITOR_CREATE_FAILED"));
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

    /**
     * A failed start may stop before MONITOR_CREATED was printed, so the
     * cleanup checks the live state; an interface this attempt did not create
     * is only removed when it really exists.
     */
    @Test public void cleanupOnlyDeletesAnInterfaceThatExists() {
        String noMonitor = backend.buildFailedStartCleanupCommand("wlan0", "wfkmon0", "up", false);
        assertTrue(noMonitor.contains("if iw dev 'wfkmon0' info"));
        assertTrue(noMonitor.contains("ip link set 'wlan0' up"));
        // The radio was switched off for the attempt and must come back.
        assertTrue(noMonitor.contains("svc wifi enable"));

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

    // ---- Qualcomm QCACLD global con_mode ---------------------------------

    private static final String CON_MODE =
            "/sys/module/qca_cld3_peach_v2/parameters/con_mode";

    @Test public void probeDiscoversTheGlobalMonitorSwitch() {
        String probe = backend.probeCommand();
        assertTrue(probe.contains("CON_MODE:"));
        assertTrue(probe.contains("/sys/module/*/parameters/con_mode"));
    }

    /** The module name differs per platform, so it must not be hard coded. */
    @Test public void conModePathIsDiscoveredAndParsed() {
        assertEquals(CON_MODE, IwMonitorModeBackend.parseConModePath(
                "some output\nCON_MODE:" + CON_MODE + "\nmore output\n"));
        assertNull(IwMonitorModeBackend.parseConModePath("CON_MODE:none\n"));
        assertNull(IwMonitorModeBackend.parseConModePath("no discovery line"));
        assertNull(IwMonitorModeBackend.parseConModePath(null));
        assertEquals("CON_MODE:none", IwMonitorModeBackend.conModeDiscoveryLine(""));
    }

    @Test public void rejectsUnsafeConModePaths() {
        String[] unsafe = {
                "/sys/module/x/parameters/con_mode;reboot",
                "/tmp/con_mode",
                "/sys/module/../etc/passwd",
                "",
                null,
        };
        for (String path : unsafe) {
            assertNull(path, IwMonitorModeBackend.validatedConModePath(path));
        }
        assertEquals(CON_MODE, IwMonitorModeBackend.validatedConModePath(CON_MODE));
    }

    @Test public void conModePathIsQuotedAndUsedByStartAndRestore() {
        String start = backend.buildStartCommand("wlan0", "wfkmon0", channel, CON_MODE);
        assertTrue(start.contains("MODE='" + CON_MODE + "'"));
        assertTrue(start.contains("echo 4 > \"$MODE\""));
        assertTrue(start.contains("MONITOR_PARENT_MODE"));
        // Entered only after every virtual-interface creation form failed.
        assertTrue(start.indexOf("MONITOR_CREATE_PHY_FAILED") < start.indexOf("echo 4 > \"$MODE\""));

        String stop = backend.buildStopCommand("wlan0", "wlan0", "up", CON_MODE);
        assertTrue(stop.contains("MODE='" + CON_MODE + "'"));
        assertTrue(stop.contains("cat \"$BACKUP\" > \"$MODE\""));
        assertTrue(stop.contains("CON_MODE_RESTORED:"));
        // The con_mode branch exits before the virtual-interface branch runs.
        assertTrue(stop.indexOf("cat \"$BACKUP\" > \"$MODE\"")
                < stop.indexOf("iw dev 'wlan0' del"));

        String cleanup = backend.buildFailedStartCleanupCommand("wlan0", "wlan0", "up", false, CON_MODE);
        assertTrue(cleanup.contains("cat \"$BACKUP\" > \"$MODE\""));
        assertTrue(cleanup.contains("ip link set 'wlan0' up"));
        assertTrue(cleanup.contains("svc wifi enable"));
    }

    /**
     * Most failed starts stop before the con_mode fallback, so no backup file
     * exists. Everything except the locked-backup restore must still run in
     * that case: deleting the monitor interface, bringing the parent back and
     * re-enabling Wi-Fi used to be nested inside the backup guard.
     */
    @Test public void failedStartCleanupRunsEvenWithoutABackupFile() {
        String cleanup = backend.buildFailedStartCleanupCommand(
                "wlan0", "wfkmon0", "up", true, CON_MODE);
        int guard = cleanup.indexOf("if [ -f \"$BACKUP\" ]; then");
        int closeGuard = cleanup.indexOf("fi; ");
        int deleteMonitor = cleanup.indexOf("iw dev 'wfkmon0' del");
        int parentUp = cleanup.indexOf("ip link set 'wlan0' up");
        int wifiEnable = cleanup.indexOf("svc wifi enable");
        assertTrue("a con_mode prelude implies a guarded backup restore", guard > 0);
        assertTrue("the guard must close before the interface cleanup",
                closeGuard > guard && deleteMonitor > closeGuard);
        assertTrue("parent restore must run outside the guard", parentUp > closeGuard);
        assertTrue("Wi-Fi must be re-enabled on every failed start", wifiEnable > closeGuard);
    }

    /**
     * Verified order on peach_v2 / Android 16: con_mode, then "ip link up",
     * then channel tuning. Tuning first fails with EBUSY.
     */
    @Test public void conModeSequenceBringsTheLinkUpBeforeTuning() {
        String start = backend.buildStartCommand("wlan0", "wfkmon0", channel, CON_MODE);
        int setMode = start.indexOf("echo 4 > \"$MODE\"");
        int waitForType = start.indexOf("until iw dev \"$IF\" info 2>/dev/null | grep -q 'type monitor'");
        int bringUp = start.indexOf("ip link set \"$IF\" up 2>/dev/null");
        int tune = start.indexOf("iw dev \"$IF\" set freq 5180 HT20");
        assertTrue("setMode=" + setMode, setMode > 0);
        assertTrue("waitForType=" + waitForType, waitForType > setMode);
        assertTrue("bringUp=" + bringUp, bringUp > waitForType);
        assertTrue("tune=" + tune + " bringUp=" + bringUp, tune > bringUp);
        assertTrue(start.contains("CON_MODE_SET_FAILED"));
        assertTrue(start.contains("CON_MODE_NOT_APPLIED"));
        assertTrue(start.contains("CHANNEL_TUNING_FAILED"));
    }

    /**
     * peach_v2 only accepts a monitor vdev and the channel tune once the
     * framework released the PHY, so Wi-Fi is switched off first, and the
     * interface is brought up before it is tuned.
     */
    @Test public void wifiIsSwitchedOffBeforeThePhyIsTouched() {
        String start = backend.buildStartCommand("wlan0", "wfkmon0", channel, CON_MODE);
        int wifiOff = start.indexOf("svc wifi disable");
        int parentDown = start.indexOf("ip link set \"$IF\" down");
        int create = start.indexOf("interface add \"$MON\"");
        assertTrue(wifiOff > 0 && parentDown > wifiOff && create > parentDown);
        assertTrue(start.contains("WIFI_WAS_ENABLED:"));

        int up = start.indexOf("ip link set \"$MON\" up");
        int tune = start.indexOf("iw dev \"$MON\" set freq 5180 HT20 2>/dev/null; then");
        assertTrue("the monitor interface must be up before tuning", tune > up && up > 0);

        String stop = backend.buildStopCommand("wlan0", "wfkmon0", "up", CON_MODE);
        assertTrue(stop.contains("svc wifi enable"));
        assertTrue(stop.indexOf("iw dev 'wfkmon0' del") < stop.indexOf("svc wifi enable"));
    }

    /**
     * The generated scripts are executed by a root shell, so a quoting or
     * "fi" mistake must be caught here rather than on a device.
     */
    @Test public void everyGeneratedScriptIsSyntacticallyValidShell() throws Exception {
        String[][] cases = {
                {"start", backend.buildStartCommand("wlan0", "wfkmon0", channel, CON_MODE)},
                {"stop", backend.buildStopCommand("wlan0", "wfkmon0", "up", CON_MODE)},
                {"cleanup", backend.buildFailedStartCleanupCommand("wlan0", "wfkmon0", "up", false, CON_MODE)},
                {"start-no-con-mode", backend.buildStartCommand("wlan0", "wfkmon0", channel, null)},
                {"stop-no-con-mode", backend.buildStopCommand("wlan0", "wfkmon0", "up", null)},
                {"cleanup-no-con-mode", backend.buildFailedStartCleanupCommand("wlan0", "wfkmon0", "up", false, null)},
                {"cleanup-created", backend.buildFailedStartCleanupCommand("wlan0", "wfkmon0", "down", true, CON_MODE)},
        };
        for (String[] entry : cases) {
            shDashN(entry[0], entry[1]);
        }
    }

    private static void shDashN(String name, String script) throws Exception {
        File file = File.createTempFile("wifitester-", ".sh");
        try {
            try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                out.write(script);
            }
            List<String> command = new ArrayList<>();
            for (String shell : new String[]{"/bin/sh", "/bin/dash"}) {
                if (!new File(shell).canExecute()) continue;
                command.add(shell);
            }
            if (command.isEmpty()) return; // no POSIX shell available (e.g. Windows CI)
            for (String shell : command) {
                Process process = new ProcessBuilder(shell, "-n", file.getAbsolutePath())
                        .redirectErrorStream(true).start();
                process.getOutputStream().close();
                String output = readAll(process);
                assertEquals(name + " is invalid shell:\n" + output, 0, process.waitFor());
            }
        } finally {
            file.delete();
        }
    }

    private static String readAll(Process process) throws Exception {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    @Test public void withoutAConModeSwitchTheCommandsStayVirtualInterfaceOnly() {
        String start = backend.buildStartCommand("wlan0", "wfkmon0", channel, null);
        assertFalse(start.contains("con_mode"));
        assertFalse(start.contains("MONITOR_PARENT_MODE"));
        String stop = backend.buildStopCommand("wlan0", "wfkmon0", "up", null);
        assertTrue(stop.contains("iw dev 'wfkmon0' del"));
        assertFalse(stop.contains("con_mode"));
    }
}
