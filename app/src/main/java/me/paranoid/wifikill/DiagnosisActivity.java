package me.paranoid.wifikill;

import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only network diagnosis wizard: walks through the common causes of a
 * slow or flaky connection (signal, gateway, DNS, internet) and finishes with
 * a plain-language verdict plus suggestions. No root, no dangerous
 * operations — this screen is safe for any user.
 */
public final class DiagnosisActivity extends BaseActivity {

    private LinearLayout results;
    private MaterialButton runButton;
    private ScrollView scrollPane;
    private int WARN;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));
        WARN = UiKit.color(this, R.attr.colorOnWarningContainer);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = LightKit.page(this);
        scrollPane = scroll;
        LinearLayout root = LightKit.root(this, scroll);
        LightKit.header(root, this, getString(R.string.diag_eyebrow),
                getString(R.string.diag_title), getString(R.string.diag_subtitle));

        LinearLayout actionsCard = LightKit.card(this);
        runButton = LightKit.primary(this, getString(R.string.diag_run));
        runButton.setOnClickListener(v -> startDiagnosis());
        actionsCard.addView(runButton);
        TextView note = LightKit.text(this, getString(R.string.diag_note), 12, LightKit.MUTED, false);
        actionsCard.addView(note, LightKit.top(this, 8));
        root.addView(actionsCard, LightKit.bottom(this, 12));

        LinearLayout resultsCard = LightKit.card(this);
        resultsCard.addView(LightKit.text(this, getString(R.string.diag_results_title), 16, LightKit.INK, true));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        resultsCard.addView(results, LightKit.top(this, 8));
        root.addView(resultsCard, LightKit.bottom(this, 12));

        LinearLayout shareCard = LightKit.card(this);
        MaterialButton share = LightKit.secondary(this, getString(R.string.diag_share));
        share.setOnClickListener(v -> shareReport());
        shareCard.addView(share);
        root.addView(shareCard);

        setContentView(scroll);
    }

    // ---- model -------------------------------------------------------------

    private final List<String[]> reportRows = new ArrayList<>();
    private final StringBuilder verdict = new StringBuilder();

    private void startDiagnosis() {
        if (running) {
            return;
        }
        running = true;
        runButton.setEnabled(false);
        reportRows.clear();
        verdict.setLength(0);
        results.removeAllViews();
        appendResult(getString(R.string.diag_running), "", LightKit.MUTED);
        new Thread(() -> {
            int rssi = checkConnection();
            checkGateway();
            checkDns();
            checkInternet();
            verdict(rssi);
            runOnUiThread(() -> {
                running = false;
                runButton.setEnabled(true);
                appendResult(getString(R.string.diag_done), verdict.toString(), LightKit.SUCCESS);
            });
        }, "wifitester-diagnosis").start();
    }

    private void row(String title, String detail, int color) {
        runOnUiThread(() -> appendResult(title, detail, color));
        reportRows.add(new String[]{title, detail});
    }

    private void appendResult(String title, String detail, int color) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = LightKit.text(this, title, 14, color, true);
        block.addView(titleView);
        if (detail != null && !detail.isEmpty()) {
            TextView detailView = LightKit.text(this, detail, 12, LightKit.MUTED, false);
            detailView.setTextIsSelectable(true);
            block.addView(detailView, LightKit.top(this, 2));
        }
        results.addView(block, LightKit.top(this, 10));
        if (scrollPane != null) {
            scrollPane.post(() -> scrollPane.fullScroll(View.FOCUS_DOWN));
        }
    }

    // ---- checks ------------------------------------------------------------

    private int checkConnection() {
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        if (wifi == null) {
            row(getString(R.string.diag_conn), getString(R.string.diag_unavailable), LightKit.DANGER);
            return -100;
        }
        WifiInfo info = wifi.getConnectionInfo();
        int rssi = info == null ? -100 : info.getRssi();
        if (rssi <= -100 || info == null || info.getNetworkId() == -1) {
            row(getString(R.string.diag_conn), getString(R.string.diag_disconnected), LightKit.DANGER);
            return -100;
        }
        String detail = getString(R.string.diag_conn_detail,
                safeSsid(info.getSSID()), rssi, info.getLinkSpeed(), info.getFrequency());
        row(getString(R.string.diag_conn), detail,
                rssi >= -60 ? LightKit.SUCCESS : (rssi >= -70 ? WARN : LightKit.DANGER));
        return rssi;
    }

    private void checkGateway() {
        int gateway = gatewayIp();
        if (gateway == 0) {
            lastGatewayPing = null;
            row(getString(R.string.diag_gateway), getString(R.string.diag_gateway_unknown), WARN);
            return;
        }
        String ip = dotted(gateway);
        PingResult ping = ping(ip, 4);
        lastGatewayPing = ping;
        int color = ping.lossPercent == 0 ? LightKit.SUCCESS
                : (ping.lossPercent < 50 ? WARN : LightKit.DANGER);
        row(getString(R.string.diag_gateway, ip),
                getString(R.string.diag_ping_result, ping.lossPercent,
                        ping.avgMs < 0 ? getString(R.string.diag_no_reply)
                                : String.format(Locale.US, "%.1f ms", ping.avgMs)),
                color);
    }

    private void checkDns() {
        long best = Long.MAX_VALUE;
        int failures = 0;
        String[] hosts = {"www.baidu.com", "www.qq.com", "www.gstatic.com"};
        for (String host : hosts) {
            long elapsed = resolve(host);
            if (elapsed < 0) {
                failures++;
            } else if (elapsed < best) {
                best = elapsed;
            }
        }
        if (failures == hosts.length) {
            dnsFailedAll = true;
            row(getString(R.string.diag_dns), getString(R.string.diag_dns_failed), LightKit.DANGER);
            return;
        }
        dnsFailedAll = false;
        long shown = best == Long.MAX_VALUE ? -1 : best;
        int color = shown <= 100 ? LightKit.SUCCESS : (shown <= 500 ? WARN : LightKit.DANGER);
        row(getString(R.string.diag_dns),
                getString(R.string.diag_dns_result, shown, failures),
                color);
    }

    private void checkInternet() {
        String[] probes = {
                "http://connect.rom.miui.com/generate_204",
                "http://www.gstatic.com/generate_204",
                "https://www.baidu.com"};
        long best = Long.MAX_VALUE;
        boolean reachable = false;
        for (String probe : probes) {
            long elapsed = httpProbe(probe);
            if (elapsed >= 0) {
                reachable = true;
                if (elapsed < best) {
                    best = elapsed;
                }
            }
        }
        if (!reachable) {
            internetReachable = false;
            row(getString(R.string.diag_internet), getString(R.string.diag_internet_failed), LightKit.DANGER);
            return;
        }
        internetReachable = true;
        int color = best <= 300 ? LightKit.SUCCESS : (best <= 1200 ? WARN : LightKit.DANGER);
        row(getString(R.string.diag_internet),
                getString(R.string.diag_internet_result, best), color);
    }

    private void verdict(int rssi) {
        List<String> problems = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (rssi == -100) {
            problems.add(getString(R.string.diag_v_no_conn));
            suggestions.add(getString(R.string.diag_s_connect));
        } else if (rssi < -70) {
            problems.add(getString(R.string.diag_v_weak, rssi));
            suggestions.add(getString(R.string.diag_s_weak));
        }
        PingResult gateway = lastGatewayPing;
        if (gateway != null && gateway.lossPercent > 0) {
            problems.add(getString(R.string.diag_v_gateway_loss, gateway.lossPercent));
            suggestions.add(getString(R.string.diag_s_gateway));
        }
        if (dnsFailedAll) {
            problems.add(getString(R.string.diag_v_dns));
            suggestions.add(getString(R.string.diag_s_dns));
        }
        if (!internetReachable) {
            problems.add(getString(R.string.diag_v_internet));
            suggestions.add(getString(R.string.diag_s_internet));
        }
        if (problems.isEmpty()) {
            verdict.append(getString(R.string.diag_v_healthy));
        } else {
            verdict.append(getString(R.string.diag_v_summary));
            for (String problem : problems) {
                verdict.append("\n· ").append(problem);
            }
            verdict.append('\n');
            for (String suggestion : suggestions) {
                verdict.append("→ ").append(suggestion).append('\n');
            }
        }
    }

    // ---- primitives --------------------------------------------------------

    private PingResult lastGatewayPing;
    private boolean dnsFailedAll;
    private boolean internetReachable;

    private static final class PingResult {
        int lossPercent = 100;
        double avgMs = -1;
    }

    private int gatewayIp() {
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        if (wifi == null) {
            return 0;
        }
        android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
        return dhcp == null ? 0 : dhcp.gateway;
    }

    private static String dotted(int address) {
        return (address & 0xff) + "." + ((address >> 8) & 0xff) + "."
                + ((address >> 16) & 0xff) + "." + ((address >> 24) & 0xff);
    }

    private PingResult ping(String host, int count) {
        PingResult result = new PingResult();
        try {
            Process process = new ProcessBuilder("/system/bin/ping",
                    "-c", String.valueOf(count), "-W", "2", host)
                    .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher loss = java.util.regex.Pattern
                        .compile("(\\d+)% packet loss").matcher(line);
                if (loss.find()) {
                    result.lossPercent = Integer.parseInt(loss.group(1));
                }
                java.util.regex.Matcher avg = java.util.regex.Pattern
                        .compile("=\\s*[\\d.]+/[\\d.]+/([\\d.]+)").matcher(line);
                if (avg.find()) {
                    result.avgMs = Double.parseDouble(avg.group(1));
                }
            }
            reader.close();
            process.waitFor();
        } catch (Exception ignored) {
            // The failure is reported as 100% loss.
        }
        return result;
    }

    private long resolve(String host) {
        long start = SystemClock.elapsedRealtime();
        try {
            InetAddress address = InetAddress.getByName(host);
            long elapsed = SystemClock.elapsedRealtime() - start;
            return address == null ? -1 : elapsed;
        } catch (Exception failure) {
            return -1;
        }
    }

    private long httpProbe(String target) {
        long start = SystemClock.elapsedRealtime();
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "WiFiTester-diagnosis");
            connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode();
            connection.disconnect();
            long elapsed = SystemClock.elapsedRealtime() - start;
            return (code >= 200 && code < 400) ? elapsed : -1;
        } catch (Exception failure) {
            return -1;
        }
    }

    private String safeSsid(String ssid) {
        String value = ssid == null ? "" : ssid.replace("\"", "");
        return value.isEmpty() ? getString(R.string.detail_unknown) : value;
    }

    private void shareReport() {
        StringBuilder report = new StringBuilder();
        report.append(getString(R.string.diag_report_title));
        report.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new java.util.Date())).append('\n');
        for (String[] row : reportRows) {
            report.append(row[0]).append('\n');
            if (row[1] != null && !row[1].isEmpty()) {
                report.append("   ").append(row[1].replace("\n", "\n   ")).append('\n');
            }
        }
        report.append(verdict);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_share_subject));
        share.putExtra(Intent.EXTRA_TEXT, report.toString());
        try {
            startActivity(Intent.createChooser(share, getString(R.string.diag_share)));
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.diag_share_failed));
        }
    }
}
