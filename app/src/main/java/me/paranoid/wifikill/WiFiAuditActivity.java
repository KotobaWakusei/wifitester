package me.paranoid.wifikill;

import android.Manifest;
import android.app.Activity;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
public final class WiFiAuditActivity extends Activity {
    private static final int REQUEST_SCAN_PERMISSIONS = 70;

    private WifiManager wifiManager;
    private TextView statusView;
    private TextView resultsView;
    private Button scanButton;
    private Button exportButton;
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
                    ? "扫描结果已更新。"
                    : "无法刷新扫描结果，正在显示缓存数据。");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        buildContentView();
        setTitle("Wi-Fi 网络信息");

        if (hasScanPermissions()) {
            renderResults("只读扫描 Wi-Fi 网络，不会修改网络配置。");
        } else {
            requestScanPermissions();
        }
    }

    private void buildContentView() {
        int padding = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0b1020);
        root.setPadding(padding, padding, padding, padding);

        TextView heading = new TextView(this);
        heading.setText("附近的 Wi-Fi 网络");
        heading.setTextSize(24);
        heading.setTextColor(0xffffffff);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        root.addView(heading, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView explanation = new TextView(this);
        explanation.setText("以下信息由 Android 提供。扫描结果可能受定位权限、系统设置"
                + "和系统扫描频率限制影响。");
        explanation.setTextSize(14);
        explanation.setTextColor(0xffa8b5ca);
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        explanationParams.topMargin = dp(8);
        root.addView(explanation, explanationParams);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xffc3d1e5);
        statusView.setPadding(dp(12),dp(12),dp(12),dp(12));
        statusView.setBackgroundColor(0xff151f33);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(12);
        root.addView(statusView, statusParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        scanButton = new Button(this);
        scanButton.setText("扫描 / 刷新");
        scanButton.setAllCaps(false);
        scanButton.setTextColor(0xffffffff);
        scanButton.setBackground(roundButton(0xff315d91));
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startWifiScan();
            }
        });
        actions.addView(scanButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        exportButton = new Button(this);
        exportButton.setText("导出报告");
        exportButton.setAllCaps(false);
        exportButton.setTextColor(0xffffffff);
        exportButton.setBackground(roundButton(0xff263a56));
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
        resultsView = new TextView(this);
        resultsView.setTextSize(14);
        resultsView.setTextColor(0xffdce6f5);
        resultsView.setTextIsSelectable(true);
        resultsView.setPadding(dp(12), dp(12), dp(12), dp(12));
        resultsView.setBackgroundColor(0xff101827);
        scroll.addView(resultsView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.topMargin = dp(8);
        root.addView(scroll, scrollParams);

        setContentView(root);
    }

    private android.graphics.drawable.GradientDrawable roundButton(int color) {\n        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();\n        d.setColor(color);\n        d.setCornerRadius(dp(14));\n        d.setStroke(dp(1), 0xff3b506e);\n        return d;\n    }\n\n    private int dp(int value) {
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
            renderResults("当前 Android 版本无法检查 Wi-Fi 扫描权限。");
            return;
        }
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (permissions.isEmpty()) {
            renderResults("所需权限已具备。");
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
            statusView.setText("Wi-Fi 扫描权限被拒绝。你仍可使用主界面的"
                    + "设备信息导出功能。");
            resultsView.setText("");
            exportButton.setEnabled(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerScanReceiver();
        if (hasScanPermissions()) {
            renderResults("正在显示 Android 提供的最新扫描结果。");
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
            renderResults("此设备未提供 Wi-Fi 系统服务。");
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            renderResults("Wi-Fi 已关闭。请在 Android 设置中启用后重试。");
            return;
        }
        try {
            boolean started = wifiManager.startScan();
            if (started) {
                statusView.setText("已请求扫描，正在等待 Android 返回结果…");
            } else {
                renderResults("Android 限制了本次扫描频率，正在显示缓存结果。");
            }
        } catch (SecurityException permissionError) {
            statusView.setText("Android 拒绝了扫描访问。请检查定位和附近设备"
                    + "权限，以及系统定位开关。");
        } catch (RuntimeException scanError) {
            statusView.setText("无法发起扫描："
                    + safeValue(scanError.getMessage()));
        }
    }

    private void renderResults(String statusPrefix) {
        if (wifiManager == null || !hasScanPermissions()) {
            latestResults.clear();
            resultsView.setText("授予 Wi-Fi 扫描所需权限前，无法读取扫描数据。");
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
            resultsView.setText("Android 拒绝访问扫描结果。");
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
            text.append("当前扫描缓存中没有可用的接入点。\n")
                    .append("点击“扫描 / 刷新”，并检查 Android 定位权限和系统定位开关。");
        } else {
            for (int index = 0; index < latestResults.size(); index++) {
                ScanResult result = latestResults.get(index);
                String capabilities = result.capabilities == null ? "" : result.capabilities;
                String security = classifySecurity(capabilities);
                text.append(index + 1).append(". ")
                        .append(result.SSID == null || result.SSID.isEmpty()
                                ? "<隐藏的 SSID>" : safeValue(result.SSID))
                        .append("\n");
                text.append("   BSSID：").append(safeValue(result.BSSID)).append("\n");
                text.append("   安全类型：").append(security).append("\n");
                text.append("   信号强度：").append(result.level).append(" dBm  |  ")
                        .append(bandForFrequency(result.frequency)).append("  |  频道 ")
                        .append(channelForFrequency(result.frequency)).append("\n");
                text.append("   能力信息：").append(safeValue(capabilities)).append("\n");
                if ("开放网络".equals(security) || "WEP（旧式加密）".equals(security)) {
                    text.append("   提示：链路层保护较弱或未加密，请确认"
                            + "该网络是否为公开网络。\n");
                }
                text.append("\n");
            }
        }
        resultsView.setText(text.toString());
        statusView.setText(statusPrefix + "\n发现的网络数量：" + latestResults.size());
        exportButton.setEnabled(!latestResults.isEmpty());
    }

    private static String classifySecurity(String capabilities) {
        String value = WifiScanParser.classifySecurity(capabilities);
        switch (value) {
            case "Enhanced Open (OWE)": return "增强型开放网络（OWE）";
            case "WPA3": return "WPA3";
            case "WPA2": return "WPA2";
            case "WPA (legacy)": return "WPA（旧式加密）";
            case "WEP (legacy)": return "WEP（旧式加密）";
            case "Open": return "开放网络";
            default: return "未知";
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
            statusView.setText("没有可导出的扫描结果。");
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append("Wi-Fi 网络安全信息\n");
        report.append("生成时间：").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        report.append("Android 版本：").append(safeValue(Build.VERSION.RELEASE))
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        report.append("可见网络数量：").append(latestResults.size()).append("\n\n");
        for (ScanResult result : latestResults) {
            String capabilities = result.capabilities == null ? "" : result.capabilities;
            report.append("网络名称（SSID）：").append(result.SSID == null || result.SSID.isEmpty()
                    ? "<隐藏的 SSID>" : safeValue(result.SSID)).append("\n");
            report.append("B网络名称（SSID）：").append(safeValue(result.BSSID)).append("\n");
            report.append("安全类型：").append(classifySecurity(capabilities)).append("\n");
            report.append("信号强度：").append(result.level).append(" dBm\n");
            report.append("频率：").append(result.frequency).append(" MHz\n");
            report.append("频道：").append(channelForFrequency(result.frequency)).append("\n");
            report.append("能力信息：").append(safeValue(capabilities)).append("\n\n");
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Wi-Fi 网络安全信息");
        shareIntent.putExtra(Intent.EXTRA_TEXT, report.toString());
        startActivity(Intent.createChooser(shareIntent, "导出 Wi-Fi 报告"));
    }

    private static String safeValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
