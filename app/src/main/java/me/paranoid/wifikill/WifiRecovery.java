package me.paranoid.wifikill;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.provider.Settings;

/**
 * Brings Wi-Fi back after monitor mode.
 *
 * <p>Creating a monitor interface on the same PHY takes the Wi-Fi station
 * interface down; restoring the link state alone does not re-associate. The
 * station is toggled through the system Wi-Fi service, which is the only
 * supported way to make the framework reconnect.</p>
 */
final class WifiRecovery {

    private WifiRecovery() {
    }

    /** Whether the system currently reports an established Wi-Fi connection. */
    static boolean isConnected(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled()
                && wifi.getConnectionInfo() != null
                && wifi.getConnectionInfo().getNetworkId() >= 0;
    }

    /**
     * Re-enables Wi-Fi when it was switched off and asks the framework to
     * reconnect. Returns true when the system accepted the request.
     *
     * <p>Only the side effect matters: some ROMs return false from
     * {@code reconnect()} while still scheduling the association, so the
     * result is not used to decide whether the user sees a hint.</p>
     */
    @SuppressWarnings("deprecation")
    static boolean requestReconnect(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return false;
        try {
            if (!wifi.isWifiEnabled()) {
                // Throws on Android 10+ for third-party apps; that must not
                // prevent the reconnect attempt below.
                try {
                    wifi.setWifiEnabled(true);
                } catch (Exception ignored) {
                    return false;
                }
            }
            wifi.reconnect();
            return true;
        } catch (SecurityException denied) {
            // Android 10+ hides these calls from apps; the settings screen is
            // the remaining supported route and the caller falls back to it.
            return false;
        }
    }

    /**
     * Re-enables Wi-Fi after monitor mode stopped.
     *
     * <p>The start switches Wi-Fi off so the framework releases the station
     * vdev: while it holds one, peach_v2 refuses both a monitor vdev and the
     * channel tune. Removing the monitor interface does not switch the radio
     * back on, and {@code reconnect()} alone leaves the device offline. The
     * radio is therefore re-enabled from a root shell and the result is only
     * reported once a connection exists again.</p>
     */
    static boolean reenableAfterConMode(Context context) {
        if (isConnected(context)) return true;
        Process process = null;
        try {
            // "svc wifi enable" is used instead of "cmd wifi set-wifi-enabled
            // enabled": the latter honours the framework's user preference and
            // silently does nothing when the radio was switched off by the
            // system, which is exactly the state after a con_mode switch.
            process = new ProcessBuilder("su", "-c",
                    "svc wifi enable >/dev/null 2>&1 || cmd wifi set-wifi-enabled enabled")
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            long deadline = System.currentTimeMillis() + REENABLE_TIMEOUT_MS;
            int code;
            while (true) {
                try {
                    code = process.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    if (System.currentTimeMillis() >= deadline) {
                        process.destroy();
                        return false;
                    }
                    RootShell.sleep(50L);
                }
            }
            // svc reports success as soon as the request is queued (and may
            // not exist on very old ROMs), so only the re-association matters.
            if (code != 0 && !isWifiEnabled(context)) return false;
            // The framework re-associates within a few seconds; report success
            // only once a connection really exists again.
            long connectedBy = System.currentTimeMillis() + RECONNECT_WAIT_MS;
            while (System.currentTimeMillis() < connectedBy) {
                if (isConnected(context)) return true;
                RootShell.sleep(500L);
            }
        } catch (Exception ignored) {
            // No root shell available; the caller opens the settings screen.
        } finally {
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException stillRunning) {
                    process.destroy();
                }
            }
        }
        return false;
    }

    private static boolean isWifiEnabled(Context context) {
        @SuppressWarnings("deprecation")
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    private static final long REENABLE_TIMEOUT_MS = 8000L;
    private static final long RECONNECT_WAIT_MS = 15000L;

    /** Opens the system Wi-Fi panel so the user can reconnect manually. */
    static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
            // Nothing else can be done; the log already explains the state.
        }
    }
}
