package me.paranoid.wifikill;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Secondary screen for a single access point.
 *
 * <p>Shows the complete radio description reported by Android and offers the
 * common operations: connect, copy identifiers, share, pin to the top of the
 * list, forget the network, and hand the selection over to the monitor or
 * attack workbench.</p>
 */
public final class NetworkDetailActivity extends BaseActivity {

    public static final String EXTRA_SSID = "ssid";
    public static final String EXTRA_BSSID = "bssid";
    public static final String EXTRA_CAPABILITIES = "capabilities";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_FREQUENCY = "frequency";
    public static final String EXTRA_TIMESTAMP = "timestamp";

    private NetworkEntry entry;
    private TextView pinButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UiKit.color(this, R.attr.colorSurface));
        getWindow().setNavigationBarColor(UiKit.color(this, R.attr.colorSurface));

        Intent intent = getIntent();
        entry = new NetworkEntry(
                intent == null ? "" : intent.getStringExtra(EXTRA_SSID),
                intent == null ? "" : intent.getStringExtra(EXTRA_BSSID),
                intent == null ? "" : intent.getStringExtra(EXTRA_CAPABILITIES),
                intent == null ? -100 : intent.getIntExtra(EXTRA_LEVEL, -100),
                intent == null ? 0 : intent.getIntExtra(EXTRA_FREQUENCY, 0),
                intent == null ? 0L : intent.getLongExtra(EXTRA_TIMESTAMP, 0L));

        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = LightKit.page(this);
        LinearLayout root = LightKit.root(this, scroll);

        // ---- title block ---------------------------------------------------
        LinearLayout titleCard = LightKit.card(this);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView symbol = LightKit.text(this, "◉", 27, LightKit.ACCENT, true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(LightKit.round(this, LightKit.ACCENT_SOFT, dp(16)));
        symbol.setPadding(dp(12), dp(9), dp(12), dp(9));
        titleRow.addView(symbol);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(LightKit.text(this, ssidLabel(), 20, LightKit.INK, true));
        labels.addView(LightKit.text(this,
                getString(entry.securityLabelRes()) + "  ·  " + getString(entry.bandLabelRes())
                        + "  ·  " + getString(entry.signalLabelRes()),
                12, LightKit.MUTED, false), LightKit.top(this, 4));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = dp(12);
        titleRow.addView(labels, labelParams);
        titleCard.addView(titleRow);

        LinearLayout meterRow = new LinearLayout(this);
        meterRow.setOrientation(LinearLayout.HORIZONTAL);
        meterRow.setGravity(Gravity.CENTER_VERTICAL);
        meterRow.addView(LightKit.signalMeter(this, entry.level, -50));
        meterRow.addView(LightKit.text(this, entry.level + " dBm", 12, LightKit.MUTED, false));
        titleCard.addView(meterRow, LightKit.top(this, 12));
        root.addView(titleCard, LightKit.bottom(this, 12));

        if (entry.isWeaklyProtected()) {
            TextView warning = LightKit.text(this,
                    getString(R.string.detail_weak_warning),
                    13, LightKit.WARNING_TEXT, false);
            warning.setPadding(dp(13), dp(11), dp(13), dp(11));
            warning.setBackground(LightKit.round(this, LightKit.WARNING_BG, dp(13)));
            root.addView(warning, LightKit.bottom(this, 12));
        }

        // ---- radio parameters ---------------------------------------------
        LinearLayout details = LightKit.card(this);
        details.addView(LightKit.text(this, getString(R.string.detail_parameters), 16, LightKit.INK, true));
        addDetail(details, getString(R.string.detail_ssid), entry.isHidden() ? getString(R.string.detail_hidden) : entry.ssid);
        addDetail(details, getString(R.string.detail_bssid), orDash(entry.bssid));
        addDetail(details, getString(R.string.detail_rssi), entry.level + " dBm（" + getString(entry.signalLabelRes()) + "）");
        addDetail(details, getString(R.string.detail_frequency), entry.frequency > 0 ? entry.frequency + " MHz" : getString(R.string.detail_unknown));
        addDetail(details, getString(R.string.detail_channel), entry.channel() > 0 ? String.valueOf(entry.channel()) : getString(R.string.detail_unknown));
        addDetail(details, getString(R.string.detail_band), getString(entry.bandLabelRes()));
        addDetail(details, getString(R.string.detail_security), getString(entry.securityLabelRes()));
        addDetail(details, getString(R.string.detail_enterprise), entry.isEnterprise() ? getString(R.string.detail_enterprise_yes) : getString(R.string.detail_enterprise_no));
        addDetail(details, getString(R.string.detail_capabilities), orDash(entry.capabilities));
        if (entry.timestamp > 0L) {
            addDetail(details, getString(R.string.detail_last_seen),
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(entry.timestamp)));
        }
        root.addView(details, LightKit.bottom(this, 12));

        // ---- common operations --------------------------------------------
        LinearLayout operations = LightKit.card(this);
        operations.addView(LightKit.text(this, getString(R.string.detail_common_actions), 16, LightKit.INK, true));

        operations.addView(LightKit.actionRow(this, getString(R.string.detail_connect),
                getString(R.string.detail_connect_detail), this::openWifiPanel));
        operations.addView(LightKit.divider(this));

        operations.addView(LightKit.actionRow(this, getString(R.string.detail_copy_ssid),
                entry.isHidden() ? getString(R.string.detail_copy_ssid_detail) : entry.ssid,
                () -> copyToClipboard(getString(R.string.detail_ssid), entry.ssid)));
        operations.addView(LightKit.divider(this));

        operations.addView(LightKit.actionRow(this, getString(R.string.detail_copy_bssid),
                orDash(entry.bssid), () -> copyToClipboard(getString(R.string.detail_bssid), entry.bssid)));
        operations.addView(LightKit.divider(this));

        operations.addView(LightKit.actionRow(this, getString(R.string.detail_share),
                getString(R.string.detail_share_detail), this::shareDetails));
        operations.addView(LightKit.divider(this));

        boolean pinned = SettingsStore.isPinned(this, entry.bssid);
        pinButton = LightKit.text(this, pinned ? getString(R.string.detail_unpin) : getString(R.string.detail_pin), 14, LightKit.INK, true);
        operations.addView(LightKit.actionRow(this, pinned ? getString(R.string.detail_unpin) : getString(R.string.detail_pin),
                pinned ? getString(R.string.detail_unpin_detail) : getString(R.string.detail_pin_detail),
                () -> togglePinned()));
        operations.addView(LightKit.divider(this));

        operations.addView(LightKit.actionRow(this, getString(R.string.detail_open_monitor),
                getString(R.string.detail_open_monitor_detail), () -> openMonitor(false)));
        operations.addView(LightKit.divider(this));

        operations.addView(LightKit.actionRow(this, getString(R.string.detail_open_attack),
                getString(R.string.detail_open_attack_detail), () -> openMonitor(true)));
        operations.addView(LightKit.divider(this));

        operations.addView(LightKit.actionRow(this, getString(R.string.detail_forget),
                getString(R.string.detail_forget_detail), this::forgetNetwork));
        root.addView(operations, LightKit.bottom(this, 12));

        // ---- safety note ----------------------------------------------------
        LinearLayout note = LightKit.card(this);
        note.addView(LightKit.text(this, getString(R.string.detail_boundary_title), 15, LightKit.INK, true));
        note.addView(LightKit.text(this,
                getString(R.string.detail_boundary_text),
                12, LightKit.MUTED, false), LightKit.top(this, 6));
        root.addView(note);

        setContentView(scroll);
    }

    /** The network name, or the hidden marker when the SSID is not broadcast. */
    private String ssidLabel() {
        return entry.isHidden() ? getString(R.string.wifi_hidden_ssid) : entry.displaySsid();
    }

    private void addDetail(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        TextView key = LightKit.text(this, label, 12, LightKit.MUTED, false);
        key.setMinWidth(dp(78));
        row.addView(key);
        TextView content = LightKit.text(this, value, 13, LightKit.INK, false);
        content.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(8);
        row.addView(content, params);
        parent.addView(row, LightKit.top(this, 8));
    }

    // ---- actions ----------------------------------------------------------

    private void openWifiPanel() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivity(new Intent(android.provider.Settings.Panel.ACTION_WIFI));
            } else {
                startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.detail_open_panel_failed));
        }
    }

    private void copyToClipboard(String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            MainActivity.notify(this, getString(R.string.detail_copy_empty, label));
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                MainActivity.notify(this, getString(R.string.detail_clipboard_unavailable));
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            MainActivity.notify(this, getString(R.string.detail_copied, label));
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.detail_copy_failed, failure.getMessage()));
        }
    }

    private void shareDetails() {
        StringBuilder body = new StringBuilder();
        body.append(getString(R.string.detail_share_title));
        body.append("SSID： ").append(entry.isHidden() ? getString(R.string.detail_hidden) : entry.ssid).append('\n');
        body.append("BSSID： ").append(orDash(entry.bssid)).append('\n');
        body.append(getString(R.string.detail_share_signal)).append(entry.level).append(" dBm（").append(getString(entry.signalLabelRes())).append("）\n");
        body.append(getString(R.string.detail_share_frequency)).append(entry.frequency).append(" MHz\n");
        body.append(getString(R.string.detail_share_channel)).append(entry.channel() > 0 ? String.valueOf(entry.channel()) : getString(R.string.detail_unknown)).append('\n');
        body.append(getString(R.string.detail_share_band)).append(getString(entry.bandLabelRes())).append('\n');
        body.append(getString(R.string.detail_share_security)).append(getString(entry.securityLabelRes())).append('\n');
        body.append(getString(R.string.detail_share_capabilities)).append(orDash(entry.capabilities)).append('\n');
        body.append(getString(R.string.detail_share_footer));

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.detail_share_title).trim() + " · " + ssidLabel());
        share.putExtra(Intent.EXTRA_TEXT, body.toString());
        try {
            startActivity(Intent.createChooser(share, getString(R.string.detail_share)));
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.detail_share_no_app));
        }
    }

    private void togglePinned() {
        SettingsStore.togglePinned(this, entry.bssid);
        boolean pinned = SettingsStore.isPinned(this, entry.bssid);
        if (pinButton != null) {
            pinButton.setText(pinned ? getString(R.string.detail_unpin) : getString(R.string.detail_pin));
        }
        MainActivity.notify(this, pinned ? getString(R.string.detail_pinned_to_top) : getString(R.string.detail_unpinned));
    }

    private void openMonitor(boolean attack) {
        if (attack) {
            Intent intent = new Intent(this, AttackActivity.class);
            intent.putExtra(AttackActivity.EXTRA_TARGET_BSSID, entry.bssid);
            intent.putExtra(AttackActivity.EXTRA_TARGET_SSID, entry.ssid);
            intent.putExtra(AttackActivity.EXTRA_TARGET_CHANNEL, entry.channel());
            startActivity(intent);
            return;
        }
        Intent intent = new Intent(this, MonitorModeActivity.class);
        intent.putExtra(MonitorModeActivity.EXTRA_PRESET_BSSID, entry.bssid);
        intent.putExtra(MonitorModeActivity.EXTRA_PRESET_CHANNEL, entry.channel());
        startActivity(intent);
    }

    private void forgetNetwork() {
        WifiManager manager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (manager == null) {
            MainActivity.notify(this, getString(R.string.wifi_state_unavailable));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.detail_forget_unsupported_title))
                    .setMessage(getString(R.string.detail_forget_unsupported))
                    .setPositiveButton(getString(R.string.action_open_settings), (dialog, which) -> openWifiPanel())
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show();
            return;
        }
        int networkId = findNetworkId(manager);
        if (networkId < 0) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.detail_forget_unavailable_title))
                    .setMessage(getString(R.string.detail_forget_unavailable))
                    .setPositiveButton(getString(R.string.action_open_settings), (dialog, which) -> openWifiPanel())
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.detail_forget))
                .setMessage(getString(R.string.detail_forget_dialog, ssidLabel()))
                .setPositiveButton(getString(R.string.action_cancel), (dialog, which) -> removeNetwork(manager, networkId))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void removeNetwork(WifiManager manager, int networkId) {
        try {
            boolean removed = manager.removeNetwork(networkId);
            MainActivity.notify(this, removed ? getString(R.string.detail_forget_done) : getString(R.string.detail_forget_refused));
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.detail_forget_failed, failure.getMessage()));
        }
    }

    /**
     * Resolves the id of the saved network to remove.
     *
     * <p>The public SDK exposes no way to enumerate the saved networks, and
     * {@code WifiManager.findNetworkId()} was removed in API 34. The only id
     * that can be obtained from public API is the one of the current
     * connection, so the removal is limited to that network.</p>
     */
    private int findNetworkId(WifiManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return -1;
        }
        android.net.wifi.WifiInfo info = manager.getConnectionInfo();
        if (info == null || info.getNetworkId() < 0) {
            return -1;
        }
        String currentBssid = info.getBSSID();
        if (currentBssid != null && !entry.bssid.isEmpty()
                && !currentBssid.equalsIgnoreCase(entry.bssid)) {
            return -1;
        }
        return info.getNetworkId();
    }

    private static String orDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
