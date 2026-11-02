package me.paranoid.wifikill;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates the visible access points per channel and recommends the least
 * congested one per band.
 *
 * <p>2.4 GHz channels overlap: an access point on channel 6 also occupies the
 * neighbouring 20 MHz channels (6±4), so the score of a channel sums the
 * signal-weighted presence of every access point that bleeds into it. 5/6 GHz
 * channels are treated as non-overlapping. The weighting makes a strong
 * neighbour count more than a distant one.</p>
 */
public final class ChannelAnalysisActivity extends BaseActivity {

    private static final int REQUEST_SCAN_PERMISSION = 830;

    private LinearLayout results;
    private TextView summary;
    private TextView scanHint;
    private List<ScanResult> latest = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = LightKit.page(this);
        LinearLayout root = LightKit.root(this, scroll);
        LightKit.header(root, this, getString(R.string.channel_eyebrow),
                getString(R.string.channel_title), getString(R.string.channel_subtitle));

        LinearLayout actions = LightKit.card(this);
        MaterialButton scan = LightKit.primary(this, getString(R.string.channel_analyze));
        scan.setOnClickListener(v -> refresh());
        actions.addView(scan);
        scanHint = LightKit.text(this, "", 12, LightKit.MUTED, false);
        actions.addView(scanHint, LightKit.top(this, 8));
        root.addView(actions, LightKit.bottom(this, 12));

        summary = LightKit.text(this, getString(R.string.channel_waiting), 14, LightKit.INK, true);
        LinearLayout summaryCard = LightKit.card(this);
        summaryCard.addView(summary);
        root.addView(summaryCard, LightKit.bottom(this, 12));

        LinearLayout card = LightKit.card(this);
        card.addView(LightKit.text(this, getString(R.string.channel_table_title), 16, LightKit.INK, true));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        card.addView(results, LightKit.top(this, 8));
        root.addView(card, LightKit.bottom(this, 12));

        LinearLayout shareCard = LightKit.card(this);
        MaterialButton share = LightKit.secondary(this, getString(R.string.channel_share));
        share.setOnClickListener(v -> shareReport());
        shareCard.addView(share);
        root.addView(shareCard);

        setContentView(scroll);
    }

    private void refresh() {
        if (!hasScanPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_SCAN_PERMISSION);
            return;
        }
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        if (wifi == null) {
            scanHint.setText(getString(R.string.channel_wifi_missing));
            return;
        }
        if (!wifi.isWifiEnabled()) {
            scanHint.setText(getString(R.string.channel_wifi_off));
            return;
        }
        try {
            wifi.startScan();
        } catch (SecurityException denied) {
            scanHint.setText(getString(R.string.channel_scan_denied));
            return;
        }
        scanHint.setText(getString(R.string.channel_analyzing));
        new Thread(() -> {
            try {
                Thread.sleep(3500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            List<ScanResult> snapshot;
            try {
                snapshot = new ArrayList<>(wifi.getScanResults());
            } catch (SecurityException denied) {
                snapshot = new ArrayList<>();
            }
            final List<ScanResult> data = snapshot;
            runOnUiThread(() -> {
                latest = data;
                analyze(data);
            });
        }, "wifitester-channel-analysis").start();
    }

    // ---- analysis ----------------------------------------------------------

    /** Per-channel aggregation shared by the view and the shared report. */
    private static final class ChannelStat {
        final int channel;
        final String band;
        int aps;
        int strong;
        double score;

        ChannelStat(int channel, String band) {
            this.channel = channel;
            this.band = band;
        }
    }

    private List<ChannelStat> analyze(List<ScanResult> scanResults) {
        Map<String, Map<Integer, ChannelStat>> bands = new LinkedHashMap<>();
        for (ScanResult result : scanResults) {
            int channel = channelOf(result.frequency);
            if (channel <= 0) continue;
            String band = bandOf(result.frequency);
            Map<Integer, ChannelStat> perBand = bands.get(band);
            if (perBand == null) {
                perBand = new LinkedHashMap<>();
                bands.put(band, perBand);
            }
            // An AP loads its own channel and (2.4 GHz only) the overlapping
            // neighbours; the weight grows with signal strength.
            int overlap = "2.4".equals(band) ? 4 : 0;
            double weight = 100.0 + result.level;
            for (int c = channel - overlap; c <= channel + overlap; c++) {
                if (c <= 0) continue;
                ChannelStat stat = perBand.get(c);
                if (stat == null) {
                    stat = new ChannelStat(c, band);
                    perBand.put(c, stat);
                }
                stat.aps++;
                if (result.level >= -67) stat.strong++;
                stat.score += weight;
            }
        }

        List<ChannelStat> rows = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        int row = 0;
        for (Map.Entry<String, Map<Integer, ChannelStat>> bandEntry : bands.entrySet()) {
            List<ChannelStat> stats = new ArrayList<>(bandEntry.getValue().values());
            java.util.Collections.sort(stats, (a, b) -> Integer.compare(a.channel, b.channel));
            ChannelStat best = null;
            for (ChannelStat stat : stats) {
                if (best == null || stat.score < best.score) {
                    best = stat;
                }
            }
            if (best != null) {
                report.append(getString(R.string.channel_best_line, bandEntry.getKey(),
                        best.channel, (int) best.score)).append('\n');
            }
            for (ChannelStat stat : stats) {
                report.append(String.format(Locale.US,
                        "  ch%-4d %sGHz  AP:%d  strong:%d  load:%d\n",
                        stat.channel, stat.band, stat.aps, stat.strong, (int) stat.score));
                rows.add(stat);
            }
            row++;
        }
        if (row == 0) {
            summary.setText(getString(R.string.channel_no_data));
            renderRows(rows, false);
            return rows;
        }
        summary.setText(getString(R.string.channel_summary) + "\n" + report.toString().trim());
        renderRows(rows, true);
        return rows;
    }

    private void renderRows(List<ChannelStat> rows, boolean showHeader) {
        results.removeAllViews();
        if (rows.isEmpty()) {
            results.addView(LightKit.text(this,
                    getString(R.string.channel_no_data), 13, LightKit.MUTED, false));
            return;
        }
        if (showHeader) {
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.addView(columnText(getString(R.string.channel_col_channel), 1, true));
            header.addView(columnText(getString(R.string.channel_col_aps), 1, true));
            header.addView(columnText(getString(R.string.channel_col_strong), 1, true));
            header.addView(columnText(getString(R.string.channel_col_load), 1, true));
            results.addView(header);
            results.addView(LightKit.divider(this), LightKit.top(this, 4));
        }
        double maxScore = 1;
        for (ChannelStat stat : rows) {
            maxScore = Math.max(maxScore, stat.score);
        }
        boolean first = true;
        for (ChannelStat stat : rows) {
            if (!first) {
                results.addView(LightKit.divider(this));
            }
            first = false;
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int bars = (int) Math.round(stat.score / maxScore * 5.0);
            rowView.addView(columnText("ch " + stat.channel, 1, false));
            rowView.addView(columnText(String.valueOf(stat.aps), 1, false));
            rowView.addView(columnText(String.valueOf(stat.strong), 1, false));
            rowView.addView(columnText(barString(bars), 1, stat.strong > 0));
            results.addView(rowView, LightKit.top(this, 6));
        }
    }

    private String barString(int filled) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            out.append(i < filled ? '▮' : '▯');
        }
        return out.toString();
    }

    private TextView columnText(String text, float weight, boolean emphasize) {
        TextView view = LightKit.text(this, text, 13,
                emphasize ? LightKit.INK : LightKit.MUTED, emphasize);
        view.setLayoutParams(new LinearLayout.LayoutParams(0, -2, weight));
        return view;
    }

    private static String bandOf(int frequency) {
        if (frequency >= 5935) return "6";
        if (frequency >= 4900) return "5";
        return "2.4";
    }

    private static int channelOf(int frequency) {
        if (frequency == 2484) return 14;
        if (frequency >= 2412 && frequency < 2484) return (frequency - 2407) / 5;
        if (frequency >= 5955) return (frequency - 5950) / 5;
        if (frequency >= 5000 && frequency < 5955) return (frequency - 5000) / 5;
        if (frequency >= 4900) return (frequency - 4000) / 5;
        return 0;
    }

    private void shareReport() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.channel_share_subject));
        share.putExtra(Intent.EXTRA_TEXT, summary.getText());
        try {
            startActivity(Intent.createChooser(share, getString(R.string.channel_share)));
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.channel_share_failed));
        }
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_SCAN_PERMISSION) {
            refresh();
        }
    }
}
