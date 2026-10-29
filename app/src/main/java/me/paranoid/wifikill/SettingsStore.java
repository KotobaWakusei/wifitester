package me.paranoid.wifikill;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Central access point for persisted application settings.
 *
 * <p>Keeping the keys, defaults and validation in one place stops the UI and
 * the background workers from drifting apart, which previously allowed a
 * hand-edited preference to disable a safety limit.</p>
 */
final class SettingsStore {

    static final String PREFS = "wifitester_settings";

    static final String KEY_AUTO_ROOT = "auto_root_check";
    static final String KEY_CAPTURE_PACKET_LIMIT = "capture_packet_limit";
    static final String KEY_CAPTURE_SNAPLEN = "capture_snaplen";
    static final String KEY_ATTACK_ACK = "attack_authorization_ack";
    static final String KEY_WORDLIST_PATH = "attack_wordlist_path";
    static final String KEY_DEFAULT_INTERFACE = "default_interface";
    static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    static final String KEY_PINNED_NETWORKS = "pinned_networks";
    static final String KEY_HIDE_WEAK_NETWORKS = "hide_weak_networks";
    static final String KEY_WEAK_NETWORK_THRESHOLD = "weak_network_threshold";
    static final String KEY_THEME_MODE = "theme_mode";
    static final String KEY_LANGUAGE = "app_language";
    /** Fill the attack workbench target fields from the device instead of guessing. */
    static final String KEY_ATTACK_AUTOFILL = "attack_autofill_target";
    static final String KEY_CAPTURE_SESSION_ACTIVE = "capture_session_active";
    static final String KEY_CAPTURE_SESSION_INTERFACE = "capture_session_interface";
    static final String KEY_CAPTURE_SESSION_MONITOR = "capture_session_monitor";
    static final String KEY_CAPTURE_SESSION_STATE = "capture_session_state";
    static final String KEY_CAPTURE_SESSION_CREATED = "capture_session_created";
    static final String KEY_CAPTURE_SESSION_PATH = "capture_session_path";

    /** Accepted packet-count limits. Anything else falls back to the default. */
    static final int[] PACKET_LIMIT_CHOICES = {5000, 20000, 50000};
    static final int DEFAULT_PACKET_LIMIT = 50000;
    /** Accepted snapshot lengths. Anything else falls back to the default. */
    static final int[] SNAPLEN_CHOICES = {256, 512, 1024};
    static final int DEFAULT_SNAPLEN = 512;

    private SettingsStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean autoRootCheck(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_ROOT, true);
    }

    static void setAutoRootCheck(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_ROOT, enabled).apply();
    }

    /** Frame budget for a single capture session. */
    static int capturePacketLimit(Context context) {
        int stored = prefs(context).getInt(KEY_CAPTURE_PACKET_LIMIT, DEFAULT_PACKET_LIMIT);
        for (int allowed : PACKET_LIMIT_CHOICES) {
            if (allowed == stored) {
                return allowed;
            }
        }
        return DEFAULT_PACKET_LIMIT;
    }

    static void setCapturePacketLimit(Context context, int value) {
        prefs(context).edit().putInt(KEY_CAPTURE_PACKET_LIMIT, value).apply();
    }

    /** Per-frame snapshot length in bytes. */
    static int captureSnaplen(Context context) {
        int stored = prefs(context).getInt(KEY_CAPTURE_SNAPLEN, DEFAULT_SNAPLEN);
        for (int allowed : SNAPLEN_CHOICES) {
            if (allowed == stored) {
                return allowed;
            }
        }
        return DEFAULT_SNAPLEN;
    }

    static void setCaptureSnaplen(Context context, int value) {
        prefs(context).edit().putInt(KEY_CAPTURE_SNAPLEN, value).apply();
    }

    /**
     * Whether the user has acknowledged the authorization and legal notice
     * required before the active attack modules may run.
     */
    static boolean attackAuthorized(Context context) {
        return prefs(context).getBoolean(KEY_ATTACK_ACK, false);
    }

    static void setAttackAuthorized(Context context, boolean authorized) {
        prefs(context).edit().putBoolean(KEY_ATTACK_ACK, authorized).apply();
    }

    /**
     * Whether the attack workbench may fill its target fields (gateway, local
     * interface and address, neighbour list, scanned access point) from the
     * device. Only empty fields are filled, so a value the user typed is
     * never replaced.
     */
    static boolean attackAutofill(Context context) {
        return prefs(context).getBoolean(KEY_ATTACK_AUTOFILL, true);
    }

    static void setAttackAutofill(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ATTACK_AUTOFILL, enabled).apply();
    }

    static String wordlistPath(Context context) {
        String stored = prefs(context).getString(KEY_WORDLIST_PATH, "");
        return stored == null ? "" : stored.trim();
    }

    static void setWordlistPath(Context context, String path) {
        prefs(context).edit().putString(KEY_WORDLIST_PATH, path == null ? "" : path.trim()).apply();
    }

    static String defaultInterface(Context context) {
        String stored = prefs(context).getString(KEY_DEFAULT_INTERFACE, "");
        return stored == null ? "" : stored.trim();
    }

    static void setDefaultInterface(Context context, String name) {
        prefs(context).edit().putString(KEY_DEFAULT_INTERFACE, name == null ? "" : name.trim()).apply();
    }

    /**
     * Records a running monitor session so a later launch can finish the
     * restore when the capture was stopped from the notification (or the
     * process died) while the workbench was not in front.
     */
    static void setCaptureSession(Context context, String iface, String monitor,
                                  String originalState, boolean created, String path) {
        prefs(context).edit()
                .putBoolean(KEY_CAPTURE_SESSION_ACTIVE, true)
                .putString(KEY_CAPTURE_SESSION_INTERFACE, iface == null ? "" : iface.trim())
                .putString(KEY_CAPTURE_SESSION_MONITOR, monitor == null ? "" : monitor.trim())
                .putString(KEY_CAPTURE_SESSION_STATE, originalState == null ? "" : originalState.trim())
                .putBoolean(KEY_CAPTURE_SESSION_CREATED, created)
                .putString(KEY_CAPTURE_SESSION_PATH, path == null ? "" : path)
                .apply();
    }

    /** Whether a session is still registered (capture running or awaiting restore). */
    static boolean captureSessionActive(Context context) {
        return prefs(context).getBoolean(KEY_CAPTURE_SESSION_ACTIVE, false);
    }

    static String captureSessionInterface(Context context) {
        String stored = prefs(context).getString(KEY_CAPTURE_SESSION_INTERFACE, "");
        return stored == null ? "" : stored.trim();
    }

    static String captureSessionMonitor(Context context) {
        String stored = prefs(context).getString(KEY_CAPTURE_SESSION_MONITOR, "");
        return stored == null ? "" : stored.trim();
    }

    static String captureSessionOriginalState(Context context) {
        String stored = prefs(context).getString(KEY_CAPTURE_SESSION_STATE, "");
        return stored == null ? "" : stored.trim();
    }

    static boolean captureSessionCreated(Context context) {
        return prefs(context).getBoolean(KEY_CAPTURE_SESSION_CREATED, false);
    }

    static String captureSessionPath(Context context) {
        String stored = prefs(context).getString(KEY_CAPTURE_SESSION_PATH, "");
        return stored == null ? "" : stored.trim();
    }

    static void clearCaptureSession(Context context) {
        prefs(context).edit()
                .remove(KEY_CAPTURE_SESSION_ACTIVE)
                .remove(KEY_CAPTURE_SESSION_INTERFACE)
                .remove(KEY_CAPTURE_SESSION_MONITOR)
                .remove(KEY_CAPTURE_SESSION_STATE)
                .remove(KEY_CAPTURE_SESSION_CREATED)
                .remove(KEY_CAPTURE_SESSION_PATH)
                .apply();
    }

    static boolean keepScreenOn(Context context) {
        return prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    static void setKeepScreenOn(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply();
    }

    static boolean hideWeakNetworks(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_WEAK_NETWORKS, false);
    }

    static void setHideWeakNetworks(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HIDE_WEAK_NETWORKS, enabled).apply();
    }

    /** RSSI threshold (dBm) below which a network counts as "weak". */
    static int weakNetworkThreshold(Context context) {
        int stored = prefs(context).getInt(KEY_WEAK_NETWORK_THRESHOLD, -75);
        if (stored < -100 || stored > 0) {
            return -75;
        }
        return stored;
    }

    static void setWeakNetworkThreshold(Context context, int rssi) {
        prefs(context).edit().putInt(KEY_WEAK_NETWORK_THRESHOLD, rssi).apply();
    }

    /** BSSIDs the user pinned to the top of the network list. */
    static List<String> pinnedNetworks(Context context) {
        String raw = prefs(context).getString(KEY_PINNED_NETWORKS, "");
        ArrayList<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim().toLowerCase(java.util.Locale.US);
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    static boolean isPinned(Context context, String bssid) {
        return bssid != null && pinnedNetworks(context).contains(bssid.toLowerCase(java.util.Locale.US));
    }

    static void togglePinned(Context context, String bssid) {
        if (bssid == null || bssid.trim().isEmpty()) {
            return;
        }
        List<String> pinned = pinnedNetworks(context);
        String key = bssid.trim().toLowerCase(java.util.Locale.US);
        if (pinned.remove(key)) {
            // Removed.
        } else {
            pinned.add(key);
        }
        prefs(context).edit().putString(KEY_PINNED_NETWORKS, String.join(",", pinned)).apply();
    }
}
