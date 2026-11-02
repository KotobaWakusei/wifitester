package me.paranoid.wifikill;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Queries the GitHub releases API for the newest published release and
 * compares it against the running version.
 *
 * <p>Kept free of Android framework calls so the version comparison can be
 * unit tested; the caller supplies the network result.</p>
 */
final class UpdateChecker {

    static final String REPO = "KotobaWakusei/wifitester";
    static final String RELEASES_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** A published release with an APK asset, if one exists. */
    static final class Release {
        final String tag;
        final String notes;
        final String apkUrl;
        final String apkName;

        Release(String tag, String notes, String apkUrl, String apkName) {
            this.tag = tag;
            this.notes = notes == null ? "" : notes;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.apkName = apkName == null ? "" : apkName;
        }
    }

    private UpdateChecker() {
    }

    /**
     * Fetches the latest release. Prefers a "release" APK asset over a debug
     * one when both exist.
     */
    static Release fetchLatest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "WiFiTester-update-check");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new java.io.IOException("HTTP " + code);
            }
            String body = readAll(connection.getInputStream());
            JSONObject release = new JSONObject(body);
            String tag = release.optString("tag_name", "");
            String notes = release.optString("body", "");
            String apkUrl = "";
            String apkName = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
                    if (apkUrl.isEmpty()
                            || (name.toLowerCase(Locale.US).contains("release")
                            && !apkName.toLowerCase(Locale.US).contains("release"))) {
                        apkUrl = asset.optString("browser_download_url", "");
                        apkName = name;
                    }
                }
            }
            return new Release(tag, notes, apkUrl, apkName);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * True when {@code tag} is numerically newer than {@code current}. Both
     * accept an optional leading "v"/"V" and ignore non-numeric suffixes
     * ("v2.7.0-debug" compares as 2.7.0).
     */
    static boolean isNewer(String current, String tag) {
        long[] currentParts = parseVersion(current);
        long[] tagParts = parseVersion(tag);
        for (int i = 0; i < 3; i++) {
            if (tagParts[i] != currentParts[i]) {
                return tagParts[i] > currentParts[i];
            }
        }
        return false;
    }

    /** Parses "v2.7.1-rc2" into {2, 7, 1}. */
    static long[] parseVersion(String value) {
        long[] parts = new long[3];
        if (value == null) {
            return parts;
        }
        String cleaned = value.trim().replaceFirst("^[vV]", "");
        String[] segments = cleaned.split("[-+_.]", 2);
        String[] numbers = segments[0].split("\\.");
        for (int i = 0; i < 3 && i < numbers.length; i++) {
            try {
                parts[i] = Long.parseLong(numbers[i].trim());
            } catch (NumberFormatException ignored) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }
}
