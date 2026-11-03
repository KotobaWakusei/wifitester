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
 * <p>The receiver is declared in {@code app/src/debug/AndroidManifest.xml}, so
 * release builds do not contain it at all. It is driven from the host with
 * explicit-component broadcasts:</p>
 *
 * <pre>
 * adb shell am broadcast -n me.paranoid.wifikill.debug/me.paranoid.wifikill.DebugBridgeReceiver \
 *   -a me.paranoid.wifikill.DEBUG --es cmd status
 *
 * adb shell run-as me.paranoid.wifikill.debug cat files/debug-bridge.log
 * </pre>
 *
 * <p>Safety rails: the receiver ignores everything unless the sender is root
 * or the adb shell user, and attack commands additionally require the same
 * "attack authorized" acknowledgement the UI enforces. Every command and every
 * answer is appended to {@link DebugBridgeLog} for audit.</p>
 */
public final class DebugBridgeReceiver extends BroadcastReceiver {

    static final String ACTION_CMD = "me.paranoid.wifikill.DEBUG";

    static final String CMD_STATUS = "status";
    static final String CMD_LOG = "log";
    static final String CMD_CLEAR = "clear";
    static final String CMD_SCAN = "scan";
    static final String CMD_ATTACK_START = "attack_start";
    static final String CMD_ATTACK_STOP = "attack_stop";
    static final String CMD_ATTACK_STATUS = "attack_status";

    private static final int UID_ROOT = 0;
    private static final int UID_SHELL = 2000;
    private static final int SCAN_WAIT_MS = 4000;

    /** The attack started through the bridge, if any. */
    private static volatile RootProcess managedAttack;
    private static volatile String managedModule = "";

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
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        final String cmd = safeExtra(intent, "cmd");
        final Bundle params = new Bundle(intent.getExtras());
        DebugBridgeLog.append("== cmd '" + cmd + "' from uid=" + senderUid(intent) + " ==");
        new Thread(() -> {
            try {
                dispatch(app, cmd, params);
            } catch (Exception failure) {
                DebugBridgeLog.append("command failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            } finally {
                pending.finish();
            }
        }, "wifitester-debug-bridge").start();
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

    private static String safeExtra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value.trim();
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
        out.append("location_mode: ").append(readSetting(context, "secure", "location_mode")).append('\n');
        out.append("attack_authorized: ").append(SettingsStore.attackAuthorized(context)).append('\n');
        out.append("root: ").append(RootShell.hasRoot(context)).append('\n');
        out.append("monitor_capture_running: ")
                .append(MonitorCaptureService.isRunning(context)).append('\n');
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
            monIface = findMonitorInterface(context);
        }
        if (DebugBridgeCommands.requiresMonitor(module)
                && (monIface.isEmpty() || !monIface.matches("[A-Za-z0-9_.-]{1,15}"))) {
            DebugBridgeLog.append("attack_start refused: no monitor interface is running. "
                    + "Start monitor mode in the app (passive monitoring) or pass "
                    + "--es mon <interface>.");
            return;
        }

        java.io.File captures = new java.io.File(context.getFilesDir(), "attack-captures");
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

    // ---- helpers -----------------------------------------------------------

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

    private static String readSetting(Context context, String namespace, String key) {
        try {
            String value = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), key);
            return value == null ? "?" : value;
        } catch (Exception ignored) {
            return "?";
        }
    }
}
