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
