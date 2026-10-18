package me.paranoid.wifikill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses driver-advertised Wi-Fi frequencies from the output of `iw list`. */
final class WifiChannelParser {
    private static final String LIST_MARKER = "__IW_LIST__";
    private static final String DEV_MARKER = "__IW_DEV__";
    private static final Pattern CHANNEL = Pattern.compile("(\\d{4,5})\\s+MHz\\s+\\[(\\d{1,3})\\]");

    private WifiChannelParser() {
    }

    static ArrayList<Channel> parse(String output) {
        ArrayList<Channel> result = new ArrayList<>();
        if (output == null) {
            return result;
        }
        int marker = output.indexOf(LIST_MARKER);
        if (marker < 0) {
            return result;
        }
        int contentStart = marker + LIST_MARKER.length();
        int end = output.indexOf(DEV_MARKER, contentStart);
        String section = output.substring(contentStart, end > contentStart ? end : output.length());

        Map<Integer, Channel> byFrequency = new LinkedHashMap<>();
        Matcher matcher = CHANNEL.matcher(section);
        while (matcher.find()) {
            try {
                int frequency = Integer.parseInt(matcher.group(1));
                int number = Integer.parseInt(matcher.group(2));
                if (!matchesChannelFrequency(frequency, number)) {
                    continue;
                }
                int lineStart = section.lastIndexOf('\n', matcher.start());
                int lineEnd = section.indexOf('\n', matcher.end());
                String sourceLine = section.substring(
                        lineStart < 0 ? 0 : lineStart + 1,
                        lineEnd < 0 ? section.length() : lineEnd);
                // iw commonly prints "(disabled)", not "[disabled]".
                if (sourceLine.toLowerCase(Locale.US).contains("disabled")) {
                    continue;
                }
                String band;
                if (frequency < 3000) {
                    band = "2.4 GHz";
                } else if (frequency < 5000) {
                    band = "4.9 GHz";
                } else if (frequency < 5925) {
                    band = "5 GHz";
                } else {
                    band = "6 GHz";
                }
                String label = "Ch " + number + "  ·  " + frequency + " MHz  ·  " + band;
                if (!byFrequency.containsKey(frequency)) {
                    byFrequency.put(frequency, new Channel(number, frequency, label));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed driver output instead of blocking discovery.
            }
        }
        result.addAll(byFrequency.values());
        return result;
    }

    /**
     * Rejects malformed driver rows whose channel number does not correspond to
     * the frequency. This also filters frequencies that fall between valid
     * channel centers instead of exposing them as selectable channels.
     */
    private static boolean matchesChannelFrequency(int frequency, int number) {
        if (frequency == 2484) {
            return number == 14;
        }
        if (frequency >= 2412 && frequency <= 2472
                && (frequency - 2412) % 5 == 0) {
            return number == (frequency - 2407) / 5;
        }
        if (frequency >= 4900 && frequency < 5000
                && (frequency - 4000) % 5 == 0) {
            return number == (frequency - 4000) / 5;
        }
        if (frequency >= 5000 && frequency < 5925
                && (frequency - 5000) % 5 == 0) {
            return number == (frequency - 5000) / 5;
        }
        if (frequency == 5935) {
            return number == 2;
        }
        if (frequency >= 5955 && frequency <= 7115
                && (frequency - 5950) % 5 == 0) {
            return number == (frequency - 5950) / 5;
        }
        return false;
    }

    static final class Channel {
        final int number;
        final int frequency;
        final String label;

        Channel(int number, int frequency, String label) {
            this.number = number;
            this.frequency = frequency;
            this.label = label;
        }
    }
}
