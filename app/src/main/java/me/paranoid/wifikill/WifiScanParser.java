package me.paranoid.wifikill;

import java.util.Locale;

/** Pure Wi-Fi scan result parsers, kept independent of Android APIs for unit testing. */
final class WifiScanParser {
    private WifiScanParser() {
    }

    static String classifySecurity(String capabilities) {
        if (capabilities == null || capabilities.trim().isEmpty()) {
            return "Unknown";
        }
        String value = capabilities.toUpperCase(Locale.US);
        if (value.contains("OWE")) {
            return "Enhanced Open (OWE)";
        }
        if (value.contains("SAE") || value.contains("WPA3")) {
            return "WPA3";
        }
        if (value.contains("WPA2")) {
            return "WPA2";
        }
        if (value.contains("WPA")) {
            return "WPA (legacy)";
        }
        if (value.contains("WEP")) {
            return "WEP (legacy)";
        }
        if (value.contains("ESS")) {
            return "Open";
        }
        return "Unknown";
    }

    static String bandForFrequency(int frequency) {
        if (frequency >= 5925 && frequency <= 7125) {
            return "6 GHz";
        }
        if (frequency >= 5000 && frequency < 5925) {
            return "5 GHz";
        }
        if (frequency >= 4900 && frequency < 5000) {
            return "4.9 GHz";
        }
        if ((frequency >= 2412 && frequency <= 2472) || frequency == 2484) {
            return "2.4 GHz";
        }
        return "Unknown";
    }

    static String channelForFrequency(int frequency) {
        if (frequency == 2484) {
            return "14";
        }
        if (frequency >= 2412 && frequency <= 2472
                && (frequency - 2412) % 5 == 0) {
            return String.valueOf((frequency - 2407) / 5);
        }
        if (frequency >= 4900 && frequency < 5000
                && (frequency - 4000) % 5 == 0) {
            return String.valueOf((frequency - 4000) / 5);
        }
        if (frequency >= 5000 && frequency < 5925
                && (frequency - 5000) % 5 == 0) {
            return String.valueOf((frequency - 5000) / 5);
        }
        // 5935 MHz is the special 6 GHz channel 2 (802.11ax).
        if (frequency == 5935) {
            return "2";
        }
        if (frequency >= 5955 && frequency <= 7115
                && (frequency - 5950) % 5 == 0) {
            return String.valueOf((frequency - 5950) / 5);
        }
        return "?";
    }
}
