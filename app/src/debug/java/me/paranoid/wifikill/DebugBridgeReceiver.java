package me.paranoid.wifikill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.List;

/**
 * adb-driven debug bridge (debug builds only).
 *
 * <p>The receiver and {@link BridgeActivity} are declared in
 * {@code app/src/debug/AndroidManifest.xml}, so release builds do not
 * contain them at all. They are driven from the host with explicit
 * components:</p>
 *
 * <pre>
 * adb shell am start -n me.paranoid.wifikill.debug/me.paranoid.wifikill.BridgeActivity
 *   --es cmd status
 * adb shell run-as me.paranoid.wifikill.debug cat files/debug-bridge.log
 * </pre>
 *
 * <p>Safety rails: the components ignore everything unless the sender is
 * root or the adb shell user, and attack commands additionally require the
 * same "attack authorized" acknowledgement the UI enforces. Every command
 * and every answer is appended to {@link DebugBridgeLog} for audit.</p>
 */
public final class DebugBridgeReceiver extends BroadcastReceiver {

    static final String ACTION_CMD = "me.paranoid.wifikill.DEBUG";

    static final String CMD_STATUS = "status";
    static final String CMD_LOG = "log";
    static final String CMD_CLEAR = "clear";
    static final String CMD_SCAN = "scan";
    static final String CMD_MONITOR_START = "monitor_start";
    static final String CMD_MONITOR_STOP = "monitor_stop";
    static final String CMD_ATTACK_START = "attack_start";
    static final String CMD_ATTACK_STOP = "attack_stop";
    static final String CMD_ATTACK_STATUS = "attack_status";

    private static final int UID_ROOT = 0;
    private static final int UID_SHELL = 2000;
    private static final int SCAN_WAIT_MS = 4000;

    /** The attack started through the bridge, if any. */
    private static volatile RootProcess managedAttack;
    private static volatile String managedModule = "";
    /** Monitor interface created through the bridge (parent, name, channel). */
    private static volatile String bridgeMonitorParent = "";
    private static volatile String bridgeMonitorName = "";
    private static volatile int bridgeMonitorChannel = 0;
    private static volatile String bridgeMonitorOriginalState = "down";

    /**
     * Serial command worker: enqueue() returns at once and commands are
     * processed one at a time in submission order.
     */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "wifitester-debug-bridge");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        DebugBridgeLog.init(context);
        // Belt and braces: the manifest merge already keeps this out of
        // release builds.
        if (!BuildConfig.DEBUG) {
            return;
        }
        if (!senderIsPrivileged(intent)) {
            DebugBridgeLog.append("refused command: sender is neither root nor adb shell"
                    + " (only those may drive the bridge)");
            return;
        }
        // The receiver path is kept for compatibility; the reliable entry is
        // BridgeActivity (see its javadoc for why broadcasts are avoided).
        enqueue(context, intent);
    }

    /**
     * Hands one command to the serial bridge worker. Shared by the manifest
     * receiver and the bridge activity. enqueue() returns at once: manifest
     * receivers are delivered serially, and holding the slot for a 30-50 s
     * root script made the system DEFER every following broadcast
     * ("blocked until 0"). A single worker thread keeps the command order
     * while unblocking the delivery.
     */
    static void enqueue(Context context, Intent intent) {
        DebugBridgeLog.init(context);
        final Context app = context.getApplicationContext();
        final String cmd = safeExtra(intent, "cmd");
        final Bundle params = new Bundle(intent.getExtras());
        DebugBridgeLog.append("== cmd '" + cmd + "' ==");
        WORKER.execute(() -> {
            try {
                dispatch(app, cmd, params);
            } catch (Exception failure) {
                DebugBridgeLog.append("command failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        });
    }

    private static void dispatch(Context context, String cmd, Bundle params) {
        switch (cmd) {
            case CMD_STATUS:
                cmdStatus(context);
                break;
            case CMD_LOG:
                cmdLogcat(context);
                break;
            case CMD_CLEAR:
                DebugBridgeLog.clear();
                DebugBridgeLog.append("log cleared");
                break;
            case CMD_SCAN:
                cmdScan(context);
                break;
            case CMD_MONITOR_START:
                cmdMonitorStart(context, params);
                break;
            case CMD_MONITOR_STOP:
                cmdMonitorStop(context);
                break;
            case CMD_ATTACK_START:
                cmdAttackStart(context, params);
                break;
            case CMD_ATTACK_STOP:
                cmdAttackStop();
                break;
            case CMD_ATTACK_STATUS:
                cmdAttackStatus();
                break;
            case "":
                DebugBridgeLog.append("no cmd given. commands: " + CMD_STATUS + ", "
                        + CMD_LOG + ", " + CMD_CLEAR + ", " + CMD_SCAN + ", "
                        + CMD_MONITOR_START + ", " + CMD_MONITOR_STOP + ", "
                        + CMD_ATTACK_START + ", " + CMD_ATTACK_STOP + ", "
                        + CMD_ATTACK_STATUS);
                break;
            default:
                DebugBridgeLog.append("unknown cmd '" + cmd + "'");
                break;
        }
    }

    // ---- commands ----------------------------------------------------------

    private static void cmdStatus(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("app: ").append(context.getPackageName())
                .append(" v").append(BuildConfig.VERSION_NAME)
                .append(" (debug=").append(BuildConfig.DEBUG).append(")\n");
        out.append("android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("permission fine_location: ")
                .append(granted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)).append('\n');
        out.append("permission coarse_location: ")
                .append(granted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)).append('\n');
        if (Build.VERSION.SDK_INT >= 33) {
            out.append("permission nearby_wifi_devices: ")
                    .append(granted(context, android.Manifest.permission.NEARBY_WIFI_DEVICES)).append('\n');
            out.append("permission post_notifications: ")
                    .append(granted(context, android.Manifest.permission.POST_NOTIFICATIONS)).append('\n');
        }
        out.append("location_mode: ").append(readSetting(context, "location_mode")).append('\n');
        out.append("attack_authorized: ").append(SettingsStore.attackAuthorized(context)).append('\n');
        out.append("root: ").append(RootShell.hasRoot(context)).append('\n');
        out.append("monitor_capture_running: ")
                .append(MonitorCaptureService.isRunning(context)).append('\n');
        out.append("bridge_monitor: ").append(bridgeMonitorName.isEmpty()
                ? "(none)" : bridgeMonitorName + " ch" + bridgeMonitorChannel).append('\n');
        RootProcess attack = managedAttack;
        out.append("bridge_attack_running: ").append(attack != null && attack.isAlive());
        if (attack != null && attack.isAlive()) {
            out.append(" (module=").append(managedModule)
                    .append(", pid=").append(attack.innerPid()).append(')');
        }
        out.append('\n');
        java.io.File bin = new java.io.File(context.getFilesDir(), "toolchain/bin");
        String[] tools = bin.list();
        out.append("bundled tools: ").append(tools == null || tools.length == 0
                ? "(none extracted)" : String.join(", ", tools)).append('\n');
        DebugBridgeLog.appendBlock(out.toString().trim());
    }

    private static void cmdLogcat(Context context) {
        RootShell.Result result = RootShell.hasRoot(context)
                ? RootShell.run(context, "logcat -d -t 1000 2>&1", RootShell.TIMEOUT_LONG)
                : null;
        String text = result != null && result.output != null && !result.output.isEmpty()
                ? result.output
                : plainLogcat();
        DebugBridgeLog.appendBlock(text.length() > 120000
                ? text.substring(text.length() - 120000) : text);
    }

    private static String plainLogcat() {
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-t", "500")
                    .redirectErrorStream(true).start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            reader.close();
            return out.toString();
        } catch (Exception failure) {
            return "logcat unavailable: " + failure.getMessage();
        }
    }

    private static void cmdScan(Context context) {
        android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            DebugBridgeLog.append("scan: WifiManager unavailable");
            return;
        }
        if (!wifi.isWifiEnabled()) {
            DebugBridgeLog.append("scan: Wi-Fi is disabled");
            return;
        }
        try {
            wifi.startScan();
        } catch (SecurityException denied) {
            DebugBridgeLog.append("scan: denied (location permission missing?)");
            return;
        }
        try {
            Thread.sleep(SCAN_WAIT_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        List<android.net.wifi.ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException denied) {
            DebugBridgeLog.append("scan: reading results denied (location permission missing?)");
            return;
        }
        StringBuilder out = new StringBuilder("scan results: ").append(results.size()).append('\n');
        int shown = 0;
        for (android.net.wifi.ScanResult result : results) {
            if (shown++ >= 30) {
                out.append("... and ").append(results.size() - shown + 1).append(" more\n");
                break;
            }
            out.append("  ").append(result.BSSID)
                    .append("  ").append(result.SSID == null ? "" : result.SSID)
                    .append("  ").append(result.level).append(" dBm")
                    .append("  ").append(result.frequency).append(" MHz\n");
        }
        DebugBridgeLog.appendBlock(out.toString().trim());
    }

    private static void cmdAttackStart(Context context, Bundle params) {
        if (managedAttack != null && managedAttack.isAlive()) {
            DebugBridgeLog.append("attack_start refused: module '" + managedModule
                    + "' is already running (pid " + managedAttack.innerPid()
                    + "); send attack_stop first");
            return;
        }
        if (!SettingsStore.attackAuthorized(context)) {
            DebugBridgeLog.append("attack_start refused: attack authorization is off. "
                    + "Acknowledge it once in the app: Settings -> Safety & authorization "
                    + "(the bridge never bypasses this guardrail).");
            return;
        }
        boolean root = RootShell.hasRoot(context);
        if (!root) {
            DebugBridgeLog.append("attack_start refused: root is not granted");
            return;
        }
        String module = firstNonEmpty(params, "module", "attack");
        if (module.isEmpty()) {
            DebugBridgeLog.append("attack_start refused: no module given. modules: "
                    + DebugBridgeCommands.MODULE_DEAUTH + ", " + DebugBridgeCommands.MODULE_BEACON
                    + ", " + DebugBridgeCommands.MODULE_AUTH + ", " + DebugBridgeCommands.MODULE_HANDSHAKE
                    + ", " + DebugBridgeCommands.MODULE_CRACK + ", " + DebugBridgeCommands.MODULE_EVIL_TWIN
                    + ", " + DebugBridgeCommands.MODULE_ARP_SPOOF);
            return;
        }
        String bssid = firstNonEmpty(params, "bssid");
        String client = firstNonEmpty(params, "client");
        int channel = parseInt(params.getString("channel"), 0);
        int count = parseInt(params.getString("count"), 10);
        String ssid = firstNonEmpty(params, "ssid");
        String passphrase = params.getString("passphrase") == null ? "" : params.getString("passphrase");
        String wordlist = firstNonEmpty(params, "wordlist");
        String victim = firstNonEmpty(params, "victim");
        String gateway = firstNonEmpty(params, "gateway");
        String dataIface = firstNonEmpty(params, "iface", "data");

        String monIface = firstNonEmpty(params, "mon");
        if (monIface.isEmpty() && DebugBridgeCommands.requiresMonitor(module)) {
            // Prefer the interface this bridge created, then anything the
            // system reports as type monitor.
            monIface = bridgeMonitorName;
        }
        if (monIface.isEmpty() && DebugBridgeCommands.requiresMonitor(module)) {
            monIface = findMonitorInterface(context);
        }
        if (DebugBridgeCommands.requiresMonitor(module)) {
            if (monIface.isEmpty() || !monIface.matches("[A-Za-z0-9_.-]{1,15}")) {
                DebugBridgeLog.append("attack_start refused: no monitor interface is running. "
                        + "Run monitor_start on the bridge (or start monitor mode in the app), "
                        + "or pass --es mon <interface>.");
                return;
            }
            if (!interfaceExists(context, monIface)) {
                // The tracked interface vanished (driver reset while the
                // radio was disabled). Recreate it once automatically.
                DebugBridgeLog.append("attack_start: monitor interface " + monIface
                        + " has vanished; recreating");
                bridgeMonitorName = "";
                bridgeMonitorParent = "";
                cmdMonitorStart(context, params);
                monIface = bridgeMonitorName;
                if (monIface.isEmpty()
                        || !monIface.matches("[A-Za-z0-9_.-]{1,15}")) {
                    DebugBridgeLog.append("attack_start refused: monitor interface "
                            + "could not be recreated");
                    return;
                }
            }
        }

        java.io.File captures = new java.io.File(context.getFilesDir(), "attack-captures");
        if (!captures.isDirectory() && !captures.mkdirs()) {
            DebugBridgeLog.append("attack_start refused: cannot create the capture directory");
            return;
        }
        String captureFile = null;
        if (DebugBridgeCommands.MODULE_CRACK.equals(module)) {
            captureFile = newestCapture(captures);
        }
        String capturePrefix = new java.io.File(captures,
                "handshake-" + System.currentTimeMillis()).getAbsolutePath();
        String hostapdConf = new java.io.File(context.getFilesDir(), "hostapd.conf")
                .getAbsolutePath();

        final String command;
        try {
            command = DebugBridgeCommands.buildCommand(module, monIface, bssid, client,
                    channel, count, ssid, passphrase, wordlist, victim, gateway,
                    dataIface, capturePrefix, captureFile, hostapdConf);
        } catch (AttackCommandBuilder.ValidationException invalid) {
            DebugBridgeLog.append("attack_start refused (validation): " + invalid.resolve(context));
            return;
        } catch (IllegalArgumentException invalid) {
            DebugBridgeLog.append("attack_start refused: " + invalid.getMessage());
            return;
        }
        final String safeModule = module;
        DebugBridgeLog.append("attack_start module=" + module + " command: " + command);
        managedModule = safeModule;
        managedAttack = RootProcess.start(context, command, new RootProcess.OutputListener() {
            @Override
            public void onLine(String line) {
                DebugBridgeLog.append("[" + safeModule + "] " + line);
            }

            @Override
            public void onFinished(int exitCode) {
                DebugBridgeLog.append("[" + safeModule + "] finished, exit code " + exitCode);
            }
        });
        if (managedAttack == null) {
            DebugBridgeLog.append("attack_start failed: the process could not be started");
        } else {
            DebugBridgeLog.append("attack started, pid " + managedAttack.innerPid()
                    + "; send attack_stop to end it");
        }
    }

    private static void cmdAttackStop() {
        RootProcess attack = managedAttack;
        if (attack == null) {
            DebugBridgeLog.append("attack_stop: nothing is running");
            return;
        }
        attack.stop();
        managedAttack = null;
        DebugBridgeLog.append("attack_stop: module '" + managedModule + "' stopped");
        managedModule = "";
    }

    private static void cmdAttackStatus() {
        RootProcess attack = managedAttack;
        if (attack == null || !attack.isAlive()) {
            DebugBridgeLog.append("attack_status: no module is running");
            return;
        }
        DebugBridgeLog.append("attack_status: module=" + managedModule
                + " pid=" + attack.innerPid() + " alive");
    }

    // ---- monitor mode (bridge-managed) -------------------------------------

    private static void cmdMonitorStart(Context context, Bundle params) {
        IwMonitorModeBackend backend = new IwMonitorModeBackend(context);
        if (!bridgeMonitorName.isEmpty()) {
            // Self-heal: the interface may have vanished behind our back
            // (bionic drivers tear virtual interfaces down when the parent
            // radio is disabled). Only report "already running" if it is
            // really still there.
            if (interfaceExists(context, bridgeMonitorName)) {
                DebugBridgeLog.append("monitor_start: already running on " + bridgeMonitorName
                        + " (channel " + bridgeMonitorChannel + "); send monitor_stop first");
                return;
            }
            DebugBridgeLog.append("monitor_start: tracked interface " + bridgeMonitorName
                    + " has vanished; recreating");
            bridgeMonitorName = "";
            bridgeMonitorParent = "";
        }
        // Reuse a monitor interface that exists but is not tracked (e.g. left
        // behind by an earlier session).
        String existing = findMonitorInterface(context);
        if (!existing.isEmpty()) {
            RootShell.Result devices = RootShell.run(context, "iw dev 2>&1", RootShell.TIMEOUT_SHORT);
            bridgeMonitorName = existing;
            bridgeMonitorParent = guessParent(devices.output, existing);
            DebugBridgeLog.append("monitor_start: reusing existing monitor interface "
                    + existing);
            return;
        }
        int channel = parseInt(params.getString("channel"), 6);
        if (frequencyForChannel(channel) == 0) {
            DebugBridgeLog.append("monitor_start refused: unsupported channel " + channel
                    + " (use 1-14 or 32+)");
            return;
        }
        RootShell.Result devices = RootShell.run(context, "iw dev 2>&1", RootShell.TIMEOUT_SHORT);
        String parent = firstNonEmpty(params, "parent");
        if (parent.isEmpty()) {
            parent = guessParent(devices.output, "");
        }
        if (parent.isEmpty()) {
            DebugBridgeLog.append("monitor_start refused: no parent interface found in iw dev");
            return;
        }
        // Read the parent's link state so the stop command can restore it.
        RootShell.Result link = RootShell.run(context,
                "ip -o link show dev '" + parent + "' 2>&1", RootShell.TIMEOUT_SHORT);
        String state = InterfaceStateParser.parseAdministrativeState(
                link.output == null ? "" : link.output);
        bridgeMonitorOriginalState = state == null ? "down" : state;
        bridgeMonitorParent = parent;
        bridgeMonitorChannel = channel;

        // Probe once for the Qualcomm con_mode switch, like the monitor UI.
        RootShell.Result probe = RootShell.run(context, backend.probeCommand(), 20000);
        String probeOut = probe.output == null ? "" : probe.output;
        String conModePath = probeOut.contains("CON_MODE:")
                ? IwMonitorModeBackend.parseConModePath(probeOut) : null;

        WifiChannelParser.Channel channelInfo = new WifiChannelParser.Channel(
                channel, frequencyForChannel(channel), "ch" + channel);
        String command = backend.buildStartCommand(parent, "wfkmon0", channelInfo, conModePath);
        DebugBridgeLog.append("monitor_start: parent=" + parent + " channel=" + channel
                + (conModePath != null ? " (con_mode path present)" : ""));
        RootShell.Result result = RootShell.run(context, command, 30000);
        String output = result.output == null ? "" : result.output;
        if (result.code == 0 && output.contains("MONITOR_READY")) {
            bridgeMonitorName = output.contains("MONITOR_PARENT_MODE") ? parent : "wfkmon0";
            DebugBridgeLog.append("monitor_start: READY on " + bridgeMonitorName
                    + " channel " + channel);
        } else {
            DebugBridgeLog.appendBlock("monitor_start failed (exit " + result.code + "):\n"
                    + trimLines(output, 3000));
            bridgeMonitorName = "";
            bridgeMonitorParent = "";
        }
    }

    private static void cmdMonitorStop(Context context) {
        if (bridgeMonitorName.isEmpty()) {
            DebugBridgeLog.append("monitor_stop: nothing is running");
            return;
        }
        IwMonitorModeBackend backend = new IwMonitorModeBackend(context);
        String stopCommand = backend.buildStopCommand(bridgeMonitorParent, bridgeMonitorName,
                bridgeMonitorOriginalState, null);
        RootShell.Result result = RootShell.run(context, stopCommand, 15000);
        DebugBridgeLog.append("monitor_stop: exit " + result.code
                + (result.output == null ? "" : "\n" + trimLines(result.output, 1200)));
        bridgeMonitorName = "";
        bridgeMonitorParent = "";
        bridgeMonitorChannel = 0;
        // The start script disabled Wi-Fi; ask for a reconnect like the UI does.
        WifiRecovery.requestReconnect(context);
    }

    // ---- helpers -----------------------------------------------------------

    /** Frequency for a channel number; mirrors WifiChannelParser's mapping. */
    private static int frequencyForChannel(int channel) {
        if (channel == 14) return 2484;
        if (channel >= 1 && channel <= 13) return 2407 + channel * 5;
        if (channel >= 32) return 5000 + channel * 5;
        return 0;
    }

    /** True when `ip link show dev <name>` reports the interface exists. */
    private static boolean interfaceExists(Context context, String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,15}")) {
            return false;
        }
        RootShell.Result link = RootShell.run(context,
                "ip link show dev '" + name + "' 2>&1", RootShell.TIMEOUT_SHORT);
        String output = link.output == null ? "" : link.output;
        return link.code == 0 && !output.contains("does not exist")
                && !output.contains("No such device");
    }

    /** The interface whose iw block says "type monitor", or "". */
    private static String findMonitorInterface(Context context) {
        RootShell.Result devices = RootShell.run(context, "iw dev 2>&1", RootShell.TIMEOUT_SHORT);
        String output = devices.output == null ? "" : devices.output;
        for (String block : output.split("(?m)(?=^\\s*Interface\\s+)")) {
            java.util.regex.Matcher nameInBlock = java.util.regex.Pattern
                    .compile("(?m)^\\s*Interface\\s+([A-Za-z0-9_.-]+)\\s*$").matcher(block);
            java.util.regex.Matcher typeInBlock = java.util.regex.Pattern
                    .compile("(?m)^\\s*type\\s+monitor\\s*$").matcher(block);
            if (nameInBlock.find() && typeInBlock.find()) {
                return nameInBlock.group(1);
            }
        }
        return "";
    }

    /** First wlan* interface that is not the known monitor interface. */
    private static String guessParent(String iwOutput, String exclude) {
        if (iwOutput == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*Interface\\s+([A-Za-z0-9_.-]+)\\s*$").matcher(iwOutput);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name.equals(exclude) || name.startsWith("wfkmon")) continue;
            if (name.startsWith("wlan")) return name;
        }
        return "";
    }

    private static String newestCapture(java.io.File directory) {
        java.io.File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        long newest = Long.MIN_VALUE;
        String path = null;
        for (java.io.File file : files) {
            if (file.isFile() && file.getName().endsWith(".cap")
                    && file.lastModified() > newest) {
                newest = file.lastModified();
                path = file.getAbsolutePath();
            }
        }
        return path;
    }

    private static String firstNonEmpty(Bundle params, String... names) {
        for (String name : names) {
            String value = params == null ? null : params.getString(name);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String granted(Context context, String permission) {
        return context.checkSelfPermission(permission)
                == PackageManager.PERMISSION_GRANTED ? "granted" : "DENIED";
    }

    private static String readSetting(Context context, String key) {
        try {
            String value = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), key);
            return value == null ? "?" : value;
        } catch (Exception ignored) {
            return "?";
        }
    }

    private static String safeExtra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value.trim();
    }

    private static String trimLines(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    /**
     * True when the broadcast came from root or the adb shell. The platform
     * exposes the sender identity through Intent.getSentFromUid() on API 34+;
     * the compile SDK may not declare it, so it is resolved reflectively. On
     * runtimes without the method the command is allowed: the receiver only
     * exists in debug builds anyway.
     */
    private static boolean senderIsPrivileged(Intent intent) {
        int uid = senderUid(intent);
        if (uid == UID_ROOT || uid == UID_SHELL) {
            return true;
        }
        return uid == -1;   // sender identity unavailable: fall back to allow
    }

    /** UID of the process that sent the broadcast, -1 when unavailable. */
    private static int senderUid(Intent intent) {
        try {
            java.lang.reflect.Method method = Intent.class.getMethod("getSentFromUid");
            Object result = method.invoke(intent);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
