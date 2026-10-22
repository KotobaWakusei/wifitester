package me.paranoid.wifikill;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Read-only Wi-Fi security reconnaissance using Android's public scan API.
 * This screen does not inject frames, disconnect clients, or change router state.
 */
public final class WiFiAuditActivity extends BaseActivity {
    private static final int REQUEST_SCAN_PERMISSIONS = 70;

    private WifiManager wifiManager;
    private TextView statusView;
    private TextView resultsView;
    private MaterialButton scanButton;
    private MaterialButton exportButton;
    private final List<ScanResult> latestResults = new ArrayList<>();
    private boolean receiverRegistered;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                return;
            }
            boolean updated = intent.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED, false);
            renderResults(updated
                    ? getString(R.string.audit_updated)
                    : getString(R.string.audit_refresh_failed));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        buildContentView();
        setTitle(R.string.audit_title);

        if (hasScanPermissions()) {
            renderResults(getString(R.string.audit_readonly_note));
        } else {
            requestScanPermissions();
        }
    }

    private void buildContentView() {
        int padding = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, Attrs.COLOR_SURFACE));
        root.setPadding(padding, padding, padding, padding);

        TextView heading = new TextView(this);
        heading.setText(getString(R.string.audit_heading));
        heading.setTextSize(24);
        heading.setTextColor(UiKit.color(this, Attrs.COLOR_ON_SURFACE));
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        root.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView explanation = new TextView(this);
        explanation.setText(getString(R.string.audit_explanation));
        explanation.setTextSize(14);
        explanation.setTextColor(UiKit.color(this, Attrs.COLOR_ON_SURFACE_VARIANT));
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        explanationParams.topMargin = dp(8);
        root.addView(explanation, explanationParams);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(UiKit.color(this, Attrs.COLOR_ON_SURFACE));
        statusView.setPadding(dp(12),dp(12),dp(12),dp(12));
        statusView.setBackgroundColor(UiKit.color(this, Attrs.COLOR_SURFACE_CONTAINER));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(12);
        root.addView(statusView, statusParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        scanButton = UiKit.primary(this, getString(R.string.audit_scan));
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startWifiScan();
            }
        });
        actions.addView(scanButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        exportButton = UiKit.tonal(this, getString(R.string.audit_export));
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportReport();
            }
        });
        actions.addView(exportButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        resultsView = UiKit.resultView(this);
        scroll.addView(resultsView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.topMargin = dp(8);
        root.addView(scroll, scrollParams);

        setContentView(root);
    }

    private android.graphics.drawable.GradientDrawable roundButton(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(1), 0xff3b506e);
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasScanPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        // Android requires FINE_LOCATION for getScanResults() and startScan(),
        // including on Android 13+. NEARBY_WIFI_DEVICES is not required by these APIs.
        return true;
    }

    private void requestScanPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            renderResults(getString(R.string.audit_permission_check_unsupported));
            return;
        }
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (permissions.isEmpty()) {
            renderResults(getString(R.string.audit_permission_ready));
            return;
        }
        requestPermissions(permissions.toArray(new String[0]), REQUEST_SCAN_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_SCAN_PERMISSIONS) {
            return;
        }
        if (hasScanPermissions()) {
            startWifiScan();
        } else {
            statusView.setText(getString(R.string.audit_permission_denied)
                    + getString(R.string.audit_permission_hint_export));
            resultsView.setText("");
            exportButton.setEnabled(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerScanReceiver();
        if (hasScanPermissions()) {
            renderResults(getString(R.string.audit_showing_latest));
        }
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(scanReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered by the system or another lifecycle path.
            }
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void registerScanReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void startWifiScan() {
        if (!hasScanPermissions()) {
            requestScanPermissions();
            return;
        }
        if (wifiManager == null) {
            renderResults(getString(R.string.audit_no_service));
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            renderResults(getString(R.string.audit_wifi_off));
            return;
        }
        try {
            boolean started = wifiManager.startScan();
            if (started) {
                statusView.setText(getString(R.string.audit_requesting));
            } else {
                renderResults(getString(R.string.audit_throttled));
            }
        } catch (SecurityException permissionError) {
            statusView.setText(getString(R.string.audit_denied,
                    getString(R.string.audit_permission_hint)));
        } catch (RuntimeException scanError) {
            statusView.setText(getString(R.string.audit_scan_failed,
                    safeValue(scanError.getMessage())));
        }
    }

    private void renderResults(String statusPrefix) {
        if (wifiManager == null || !hasScanPermissions()) {
            latestResults.clear();
            resultsView.setText(getString(R.string.audit_no_data));
            exportButton.setEnabled(false);
            if (statusView != null) {
                statusView.setText(statusPrefix);
            }
            return;
        }

        try {
            List<ScanResult> scanResults = wifiManager.getScanResults();
            latestResults.clear();
            if (scanResults != null) {
                latestResults.addAll(scanResults);
            }
        } catch (SecurityException permissionError) {
            latestResults.clear();
            resultsView.setText(getString(R.string.audit_results_denied));
            exportButton.setEnabled(false);
            statusView.setText(statusPrefix);
            return;
        }

        Collections.sort(latestResults, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult first, ScanResult second) {
                return Integer.compare(second.level, first.level);
            }
        });

        StringBuilder text = new StringBuilder();
        if (latestResults.isEmpty()) {
            text.append(getString(R.string.audit_no_access_points))
                    .append(getString(R.string.audit_permission_help));
        } else {
            for (int index = 0; index < latestResults.size(); index++) {
                ScanResult result = latestResults.get(index);
                String capabilities = result.capabilities == null ? "" : result.capabilities;
                String security = classifySecurity(capabilities);
                text.append(index + 1).append(". ")
                        .append(result.SSID == null || result.SSID.isEmpty()
                                ? getString(R.string.audit_hidden_ssid) : safeValue(result.SSID))
                        .append("\n");
                text.append("   BSSID：").append(safeValue(result.BSSID)).append("\n");
                text.append(getString(R.string.audit_row_security)).append(security).append("\n");
                text.append(getString(R.string.audit_row_signal)).append(result.level).append(" dBm  |  ")
                        .append(bandForFrequency(result.frequency)).append("  |  ")
                        .append(channelForFrequency(result.frequency)).append("\n");
                text.append(getString(R.string.audit_row_capabilities)).append(safeValue(capabilities)).append("\n");
                if (getString(R.string.audit_open).equals(security) || getString(R.string.audit_wep_legacy).equals(security)) {
                    text.append(getString(R.string.audit_weak_hint));
                }
                text.append("\n");
            }
        }
        resultsView.setText(text.toString());
        statusView.setText(statusPrefix + getString(R.string.audit_count, latestResults.size()));
        exportButton.setEnabled(!latestResults.isEmpty());
    }

    private String classifySecurity(String capabilities) {
        String value = WifiScanParser.classifySecurity(capabilities);
        switch (value) {
            case "Enhanced Open (OWE)": return getString(R.string.audit_owe);
            case "WPA3": return getString(R.string.security_wpa3);
            case "WPA2": return getString(R.string.security_wpa2);
            case "WPA (legacy)": return getString(R.string.audit_wpa_legacy);
            case "WEP (legacy)": return getString(R.string.audit_wep_legacy);
            case "Open": return getString(R.string.audit_open);
            default: return getString(R.string.audit_unknown);
        }
    }

    private static String bandForFrequency(int frequency) {
        return WifiScanParser.bandForFrequency(frequency);
    }

    private static String channelForFrequency(int frequency) {
        return WifiScanParser.channelForFrequency(frequency);
    }

    private void exportReport() {
        if (latestResults.isEmpty()) {
            statusView.setText(getString(R.string.audit_no_export));
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append(getString(R.string.audit_report_title));
        report.append(getString(R.string.audit_report_time)).append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        report.append(getString(R.string.audit_report_android)).append(safeValue(Build.VERSION.RELEASE))
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append(getString(R.string.audit_report_count, latestResults.size()));
        for (ScanResult result : latestResults) {
            String capabilities = result.capabilities == null ? "" : result.capabilities;
            report.append(getString(R.string.audit_report_ssid)).append(result.SSID == null || result.SSID.isEmpty()
                    ? getString(R.string.audit_hidden_ssid) : safeValue(result.SSID)).append("\n");
            report.append("BSSID：").append(safeValue(result.BSSID)).append("\n");
            report.append(getString(R.string.audit_report_security)).append(classifySecurity(capabilities)).append("\n");
            report.append(getString(R.string.audit_report_signal)).append(result.level).append(" dBm\n");
            report.append(getString(R.string.audit_report_frequency)).append(result.frequency).append(" MHz\n");
            report.append(getString(R.string.audit_report_channel)).append(channelForFrequency(result.frequency)).append("\n");
            report.append(getString(R.string.audit_report_capabilities)).append(safeValue(capabilities)).append("\n\n");
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.audit_share_subject));
        shareIntent.putExtra(Intent.EXTRA_TEXT, report.toString());
        startActivity(Intent.createChooser(shareIntent, getString(R.string.audit_share_chooser)));
    }

    private static String safeValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
