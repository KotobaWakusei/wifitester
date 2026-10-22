package me.paranoid.wifikill;

import android.net.wifi.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable snapshot of a single access point.
 *
 * <p>{@link ScanResult} cannot be passed between activities or persisted, so the
 * list screen converts each result into this value object once and hands the
 * fields around as plain extras.</p>
 *
 * <p>The class classifies the network instead of returning a translated label,
 * so it stays free of any Android dependency and can be unit tested. The user
 * interface resolves the label through {@link #securityLabelRes()},
 * {@link #signalLabelRes()} and {@link #bandLabelRes()}.</p>
 */
final class NetworkEntry {

    /** Security classification of an access point. */
    enum Security {
        OPEN(R.string.security_open),
        WEP(R.string.security_wep),
        WPA(R.string.security_wpa),
        WPA2(R.string.security_wpa2),
        WPA3(R.string.security_wpa3),
        OWE(R.string.security_owe),
        ENTERPRISE(R.string.security_enterprise),
        UNKNOWN(R.string.security_unknown);

        final int labelRes;

        Security(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** Qualitative signal strength. */
    enum Signal {
        EXCELLENT(R.string.signal_excellent),
        STRONG(R.string.signal_strong),
        FAIR(R.string.signal_fair),
        WEAK(R.string.signal_weak),
        VERY_WEAK(R.string.signal_very_weak);

        final int labelRes;

        Signal(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** Frequency band of the access point. */
    enum Band {
        GHZ2(R.string.band_24),
        GHZ5(R.string.band_5),
        GHZ6(R.string.band_6),
        UNKNOWN(R.string.band_unknown);

        final int labelRes;

        Band(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    final String ssid;
    final String bssid;
    final String capabilities;
    final int level;
    final int frequency;
    final long timestamp;

    NetworkEntry(String ssid, String bssid, String capabilities, int level, int frequency, long timestamp) {
        this.ssid = ssid == null ? "" : ssid;
        this.bssid = bssid == null ? "" : bssid;
        this.capabilities = capabilities == null ? "" : capabilities;
        this.level = level;
        this.frequency = frequency;
        this.timestamp = timestamp;
    }

    static NetworkEntry from(ScanResult result) {
        if (result == null) {
            return null;
        }
        return new NetworkEntry(
                result.SSID,
                result.BSSID,
                result.capabilities,
                result.level,
                result.frequency,
                result.timestamp);
    }

    /** The raw network name; empty when the network hides its SSID. */
    String displaySsid() {
        return ssid.trim();
    }

    boolean isHidden() {
        return ssid.trim().isEmpty();
    }

    /** Classifies the link layer protection of the access point. */
    Security securityKind() {
        String value = capabilities.toUpperCase(Locale.US);
        // Enterprise authentication is the more specific fact and is checked
        // first: "[WPA2-EAP]" would otherwise be reported as plain "WPA2".
        if (value.contains("EAP")) {
            return Security.ENTERPRISE;
        }
        if (value.contains("WPA3") || value.contains("SAE")) {
            return Security.WPA3;
        }
        if (value.contains("OWE")) {
            return Security.OWE;
        }
        if (value.contains("WPA2")) {
            return Security.WPA2;
        }
        if (value.contains("WPA")) {
            return Security.WPA;
        }
        if (value.contains("WEP")) {
            return Security.WEP;
        }
        if (value.contains("ESS")) {
            return Security.OPEN;
        }
        return Security.UNKNOWN;
    }

    /** The resource that carries the security label. */
    int securityLabelRes() {
        return securityKind().labelRes;
    }

    /** True when the link layer offers no or only legacy encryption. */
    boolean isWeaklyProtected() {
        Security kind = securityKind();
        return kind == Security.OPEN || kind == Security.WEP || kind == Security.UNKNOWN;
    }

    /** Classifies the frequency band of the access point. */
    Band bandKind() {
        if (frequency >= 5925 && frequency <= 7125) {
            return Band.GHZ6;
        }
        if (frequency >= 4900 && frequency < 5925) {
            return Band.GHZ5;
        }
        if ((frequency >= 2412 && frequency <= 2472) || frequency == 2484) {
            return Band.GHZ2;
        }
        return Band.UNKNOWN;
    }

    /** The resource that carries the band label. */
    int bandLabelRes() {
        return bandKind().labelRes;
    }

    int channel() {
        if (frequency == 2484) {
            return 14;
        }
        if (frequency >= 2412 && frequency <= 2472) {
            return (frequency - 2407) / 5;
        }
        if (frequency >= 4900 && frequency < 5000) {
            return (frequency - 4000) / 5;
        }
        if (frequency >= 5000 && frequency < 5925) {
            return (frequency - 5000) / 5;
        }
        if (frequency == 5935) {
            return 2;
        }
        if (frequency >= 5955 && frequency <= 7115) {
            return (frequency - 5950) / 5;
        }
        return -1;
    }

    /** Classifies the signal strength. */
    Signal signalKind() {
        if (level >= -50) {
            return Signal.EXCELLENT;
        }
        if (level >= -60) {
            return Signal.STRONG;
        }
        if (level >= -70) {
            return Signal.FAIR;
        }
        if (level >= -80) {
            return Signal.WEAK;
        }
        return Signal.VERY_WEAK;
    }

    /** The resource that carries the signal label. */
    int signalLabelRes() {
        return signalKind().labelRes;
    }

    /** Whether the access point advertises WPA/WPA2 enterprise (802.1X). */
    boolean isEnterprise() {
        return capabilities.toUpperCase(Locale.US).contains("EAP");
    }

    static List<NetworkEntry> fromAll(List<ScanResult> results) {
        ArrayList<NetworkEntry> entries = new ArrayList<>();
        if (results == null) {
            return entries;
        }
        for (ScanResult result : results) {
            NetworkEntry entry = from(result);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }
}
