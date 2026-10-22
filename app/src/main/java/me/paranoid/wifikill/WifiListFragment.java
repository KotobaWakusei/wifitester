package me.paranoid.wifikill;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * First tab: the visible access-point list plus the current link status.
 *
 * <p>Tapping a row opens {@link NetworkDetailActivity}, the secondary screen
 * that shows the full radio parameters of one network and offers the common
 * operations (connect, copy, share, hand over to the monitor or attack
 * workbench).</p>
 */
public final class WifiListFragment extends Fragment {

    private static final int REQUEST_WIFI_PERMISSION = 810;

    /** Sort modes offered in the list header. */
    private static final int SORT_BY_SIGNAL = 0;
    private static final int SORT_BY_CHANNEL = 1;
    private static final int SORT_BY_SECURITY = 2;

    private WifiManager wifi;
    private LinearLayout networkList;
    private TextView wifiState;
    private TextView connectedName;
    private TextView connectedDetails;
    private TextView rootWarning;
    private TextView scanSummary;

    private final List<NetworkEntry> entries = new ArrayList<>();
    private int sortMode = SORT_BY_SIGNAL;
    private boolean hideWeak;
    private boolean receiverRegistered;
    private Boolean rootAvailable;

    /**
     * The attached host context. The framework {@code Fragment} API has no
     * {@code requireContext()}, so the context is resolved defensively.
     */
    private Context host() {
        Context context = getActivity();
        return context != null ? context : getContext();
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                return;
            }
            boolean updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            scanSummary.setText(updated ? getString(R.string.wifi_scan_updated) : getString(R.string.wifi_scan_cached));
            loadScanResults();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the scan results across configuration changes; rebuilding the
        // list from the system cache is slow and rate limited.
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        wifi = (WifiManager) host().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        hideWeak = SettingsStore.hideWeakNetworks(host());

        ScrollView scroll = LightKit.page(getActivity());
        LinearLayout root = LightKit.root(getActivity(), scroll);
        LightKit.header(root, getActivity(), getString(R.string.wifi_eyebrow), getString(R.string.wifi_title),
                getString(R.string.wifi_subtitle));

        rootWarning = LightKit.text(getActivity(), "", 13, LightKit.WARNING_TEXT, false);
        rootWarning.setPadding(LightKit.dp(getActivity(), 13), LightKit.dp(getActivity(), 11),
                LightKit.dp(getActivity(), 13), LightKit.dp(getActivity(), 11));
        rootWarning.setBackground(LightKit.round(LightKit.WARNING_BG, LightKit.dp(getActivity(), 13)));
        rootWarning.setVisibility(View.GONE);
        rootWarning.setText(getString(R.string.wifi_root_missing));
        root.addView(rootWarning, LightKit.bottom(getActivity(), 12));

        root.addView(buildConnectionCard(), LightKit.bottom(getActivity(), 12));
        root.addView(buildNearbyCard(), LightKit.bottom(getActivity(), 12));
        root.addView(buildQuickActions());

        refreshConnectionInfo();
        renderNetworkList();
        return scroll;
    }

    // ---- connection card -------------------------------------------------

    private LinearLayout buildConnectionCard() {
        LinearLayout card = LightKit.card(getActivity());
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView symbol = LightKit.text(getActivity(), "◉", 27, LightKit.ACCENT, true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(LightKit.round(LightKit.ACCENT_SOFT, LightKit.dp(getActivity(), 16)));
        symbol.setPadding(LightKit.dp(getActivity(), 12), LightKit.dp(getActivity(), 9),
                LightKit.dp(getActivity(), 12), LightKit.dp(getActivity(), 9));
        row.addView(symbol);

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        wifiState = LightKit.text(getActivity(), getString(R.string.wifi_state_reading), 17, LightKit.INK, true);
        labels.addView(wifiState);
        labels.addView(LightKit.text(getActivity(), getString(R.string.wifi_state_managed), 12,
                LightKit.MUTED, false), LightKit.top(getActivity(), 4));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = LightKit.dp(getActivity(), 12);
        row.addView(labels, labelParams);
        card.addView(row);

        connectedName = LightKit.text(getActivity(), getString(R.string.wifi_connected_reading), 19, LightKit.INK, true);
        card.addView(connectedName, LightKit.top(getActivity(), 12));
        connectedDetails = LightKit.text(getActivity(), "", 13, LightKit.MUTED, false);
        connectedDetails.setTextIsSelectable(true);
        card.addView(connectedDetails, LightKit.top(getActivity(), 6));

        LinearLayout actions = new LinearLayout(getActivity());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton panel = LightKit.primary(getActivity(), getString(R.string.wifi_panel_button));
        panel.setOnClickListener(v -> openWifiPanel());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.rightMargin = LightKit.dp(getActivity(), 6);
        actions.addView(panel, half);
        MaterialButton refresh = LightKit.secondary(getActivity(), getString(R.string.wifi_refresh_button));
        refresh.setOnClickListener(v -> {
            refreshConnectionInfo();
            startScan();
        });
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(actions, LightKit.top(getActivity(), 12));
        return card;
    }

    // ---- nearby networks card -------------------------------------------

    private LinearLayout buildNearbyCard() {
        LinearLayout card = LightKit.card(getActivity());

        LinearLayout heading = new LinearLayout(getActivity());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(LightKit.text(getActivity(), getString(R.string.wifi_nearby), 16, LightKit.INK, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton scan = LightKit.secondary(getActivity(), getString(R.string.wifi_scan));
        scan.setOnClickListener(v -> startScan());
        heading.addView(scan);
        card.addView(heading);

        scanSummary = LightKit.text(getActivity(), getString(R.string.wifi_scan_waiting),
                12, LightKit.MUTED, false);
        card.addView(scanSummary, LightKit.top(getActivity(), 6));

        LinearLayout options = new LinearLayout(getActivity());
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(Gravity.CENTER_VERTICAL);
        TextView sortLabel = LightKit.text(getActivity(), getString(R.string.wifi_sort_label), 12, LightKit.MUTED, false);
        options.addView(sortLabel);
        MaterialButton sort = LightKit.neutral(getActivity(), getString(R.string.wifi_sort_signal));
        sort.setOnClickListener(v -> showSortOptions());
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(-2, -2);
        sortParams.leftMargin = LightKit.dp(getActivity(), 8);
        options.addView(sort, sortParams);
        MaterialButton weak = LightKit.neutral(getActivity(), getString(R.string.wifi_hide_weak));
        weak.setOnClickListener(v -> {
            hideWeak = !hideWeak;
            SettingsStore.setHideWeakNetworks(host(), hideWeak);
            weak.setText(hideWeak ? getString(R.string.wifi_show_weak)
                    : getString(R.string.wifi_hide_weak));
            renderNetworkList();
        });
        LinearLayout.LayoutParams weakParams = new LinearLayout.LayoutParams(-2, -2);
        weakParams.leftMargin = LightKit.dp(getActivity(), 6);
        options.addView(weak, weakParams);
        card.addView(options, LightKit.top(getActivity(), 8));

        networkList = new LinearLayout(getActivity());
        networkList.setOrientation(LinearLayout.VERTICAL);
        card.addView(networkList, LightKit.top(getActivity(), 6));
        return card;
    }

    private LinearLayout buildQuickActions() {
        LinearLayout card = LightKit.card(getActivity());
        card.addView(LightKit.text(getActivity(), getString(R.string.wifi_quick_actions), 16, LightKit.INK, true));
        card.addView(LightKit.actionRow(getActivity(), getString(R.string.wifi_quick_settings),
                getString(R.string.wifi_quick_settings_detail), this::openWifiSettings));
        card.addView(LightKit.divider(getActivity()));
        card.addView(LightKit.actionRow(getActivity(), getString(R.string.wifi_quick_tools),
                getString(R.string.wifi_quick_tools_detail), () -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).selectTab(1, true);
            }
        }));
        card.addView(LightKit.divider(getActivity()));
        card.addView(LightKit.actionRow(getActivity(), getString(R.string.wifi_quick_root),
                getString(R.string.wifi_quick_root_detail), this::checkRootWarning));
        return card;
    }

    private void showSortOptions() {
        final String[] labels = {getString(R.string.wifi_sort_signal), getString(R.string.wifi_sort_channel),
                getString(R.string.wifi_sort_security)};
        new AlertDialog.Builder(getActivity())
                .setTitle(getString(R.string.wifi_sort_title))
                .setSingleChoiceItems(labels, sortMode, (dialog, which) -> {
                    sortMode = which;
                    renderNetworkList();
                    dialog.dismiss();
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    // ---- data ------------------------------------------------------------

    private void refreshConnectionInfo() {
        if (wifi == null) {
            wifiState.setText(getString(R.string.wifi_state_unavailable));
            connectedName.setText(getString(R.string.wifi_unavailable));
            connectedDetails.setText("");
            return;
        }
        boolean enabled = wifi.isWifiEnabled();
        wifiState.setText(getString(enabled ? R.string.wifi_state_enabled
                : R.string.wifi_state_disabled));
        wifiState.setTextColor(UiKit.color(host(), enabled ? LightKit.SUCCESS : LightKit.DANGER));

        WifiInfo info = null;
        try {
            info = wifi.getConnectionInfo();
        } catch (Exception ignored) {
            // Some vendor builds throw when the Wi-Fi service is still starting.
        }
        if (!enabled || info == null || info.getNetworkId() == -1 || isBlank(info.getSSID())) {
            connectedName.setText(getString(R.string.wifi_connected_none));
            connectedDetails.setText(getString(R.string.wifi_connected_hint));
            return;
        }
        connectedName.setText(cleanSsid(info.getSSID()));
        String frequency = info.getFrequency() > 0
                ? info.getFrequency() + " MHz (" + bandOf(info.getFrequency()) + ")"
                : getString(R.string.detail_unknown);
        connectedDetails.setText(getString(R.string.wifi_connected_details,
                safe(info.getBSSID()),
                info.getRssi(),
                info.getLinkSpeed(),
                frequency,
                ipToString(info.getIpAddress()),
                safe(info.getMacAddress())));
    }

    private void startScan() {
        if (wifi == null || networkList == null) {
            return;
        }
        if (!hasScanPermission()) {
            requestScanPermission();
            return;
        }
        if (!wifi.isWifiEnabled()) {
            scanSummary.setText(getString(R.string.wifi_disabled_hint));
            renderNetworkList();
            return;
        }
        scanSummary.setText(getString(R.string.wifi_scan_requesting));
        try {
            wifi.startScan();
            loadScanResults();
        } catch (SecurityException denied) {
            scanSummary.setText(getString(R.string.wifi_scan_denied));
        } catch (Exception failure) {
            scanSummary.setText(getString(R.string.wifi_scan_failed, failure.getClass().getSimpleName()));
        }
    }

    private void loadScanResults() {
        if (wifi == null) {
            return;
        }
        try {
            List<ScanResult> results = wifi.getScanResults();
            entries.clear();
            entries.addAll(NetworkEntry.fromAll(results));
            scanSummary.setText(getString(R.string.wifi_scan_found, entries.size()));
        } catch (SecurityException denied) {
            entries.clear();
            scanSummary.setText(getString(R.string.wifi_scan_denied_text));
        } catch (Exception failure) {
            entries.clear();
            scanSummary.setText(getString(R.string.wifi_scan_read_failed, failure.getClass().getSimpleName()));
        }
        renderNetworkList();
    }

    private void renderNetworkList() {
        if (networkList == null) {
            return;
        }
        networkList.removeAllViews();

        List<NetworkEntry> visible = new ArrayList<>();
        for (NetworkEntry entry : entries) {
            if (hideWeak && entry.level < SettingsStore.weakNetworkThreshold(host())) {
                continue;
            }
            visible.add(entry);
        }
        sortEntries(visible);

        if (visible.isEmpty()) {
            TextView empty = LightKit.text(getActivity(),
                    entries.isEmpty()
                            ? getString(R.string.wifi_empty)
                            : getString(R.string.wifi_empty_filtered),
                    13, LightKit.MUTED, false);
            empty.setPadding(0, LightKit.dp(getActivity(), 12), 0, LightKit.dp(getActivity(), 12));
            networkList.addView(empty);
            return;
        }

        final List<String> pinned = SettingsStore.pinnedNetworks(host());
        for (NetworkEntry entry : visible) {
            boolean isPinned = pinned.contains(entry.bssid.toLowerCase(Locale.US));
            networkList.addView(buildNetworkRow(entry, isPinned));
        }
    }

    private void sortEntries(List<NetworkEntry> visible) {
        final List<String> pinned = SettingsStore.pinnedNetworks(host());
        Collections.sort(visible, new Comparator<NetworkEntry>() {
            @Override
            public int compare(NetworkEntry first, NetworkEntry second) {
                boolean firstPinned = pinned.contains(first.bssid.toLowerCase(Locale.US));
                boolean secondPinned = pinned.contains(second.bssid.toLowerCase(Locale.US));
                if (firstPinned != secondPinned) {
                    return firstPinned ? -1 : 1;
                }
                switch (sortMode) {
                    case SORT_BY_CHANNEL:
                        return Integer.compare(first.channel(), second.channel());
                    case SORT_BY_SECURITY:
                        return first.securityKind().compareTo(second.securityKind());
                    case SORT_BY_SIGNAL:
                    default:
                        return Integer.compare(second.level, first.level);
                }
            }
        });
    }

    private View buildNetworkRow(NetworkEntry entry, boolean isPinned) {
        LinearLayout item = new LinearLayout(getActivity());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(LightKit.dp(getActivity(), 10), LightKit.dp(getActivity(), 12),
                LightKit.dp(getActivity(), 10), LightKit.dp(getActivity(), 12));
        item.setBackground(androidx.core.content.ContextCompat.getDrawable(
                getActivity(), R.drawable.list_item_selector));

        // Leading avatar: the first character of the SSID on a tinted circle.
        TextView avatar = LightKit.text(getActivity(), avatarLabel(entry), 16, LightKit.ACCENT, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(LightKit.round(getActivity(), LightKit.ACCENT_SOFT, LightKit.dp(getActivity(), 14)));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                LightKit.dp(getActivity(), 42), LightKit.dp(getActivity(), 42));
        avatarParams.rightMargin = LightKit.dp(getActivity(), 12);
        item.addView(avatar, avatarParams);

        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nameRow = new LinearLayout(getActivity());
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ssid = LightKit.text(getActivity(), ssidLabel(entry), 15, LightKit.INK, true);
        ssid.setMaxLines(1);
        ssid.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameRow.addView(ssid, new LinearLayout.LayoutParams(0, -2, 1));
        if (isPinned) {
            TextView pin = LightKit.badge(getActivity(), getString(R.string.wifi_pinned),
                    LightKit.ACCENT_SOFT, LightKit.ACCENT);
            LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(-2, -2);
            pinParams.leftMargin = LightKit.dp(getActivity(), 6);
            nameRow.addView(pin, pinParams);
        }
        labels.addView(nameRow);

        String channel = entry.channel() > 0 ? getString(R.string.wifi_channel, entry.channel()) : getString(R.string.wifi_channel_unknown);
        labels.addView(LightKit.text(getActivity(),
                safe(entry.bssid) + "  ·  " + entry.level + " dBm  ·  " + channel,
                11, LightKit.MUTED, false), LightKit.top(getActivity(), 4));
        labels.addView(LightKit.text(getActivity(),
                getString(entry.securityLabelRes()) + "  ·  " + getString(entry.bandLabelRes())
                        + "  ·  " + getString(entry.signalLabelRes()),
                11, LightKit.MUTED, false), LightKit.top(getActivity(), 3));

        item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout trailing = new LinearLayout(getActivity());
        trailing.setOrientation(LinearLayout.VERTICAL);
        trailing.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        trailing.addView(LightKit.signalMeter(getActivity(), entry.level, -50));
        trailing.addView(LightKit.text(getActivity(), getString(entry.signalLabelRes()), 10, LightKit.MUTED, false),
                LightKit.top(getActivity(), 4));
        LinearLayout.LayoutParams trailingParams = new LinearLayout.LayoutParams(-2, -2);
        trailingParams.leftMargin = LightKit.dp(getActivity(), 10);
        item.addView(trailing, trailingParams);

        item.setOnClickListener(v -> openDetail(entry));
        item.setAlpha(0f);
        item.setTranslationY(LightKit.dp(getActivity(), 6));
        item.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(networkList.getChildCount() * 24, 180)).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        LinearLayout container = new LinearLayout(getActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(item);
        container.addView(LightKit.divider(getActivity()));
        return container;
    }

    /** The network name, or the hidden marker when the SSID is not broadcast. */
    private String ssidLabel(NetworkEntry entry) {
        return entry.isHidden() ? getString(R.string.wifi_hidden_ssid) : entry.displaySsid();
    }

    /** The leading character shown in the avatar of a network row. */
    private String avatarLabel(NetworkEntry entry) {
        String ssid = ssidLabel(entry);
        if (ssid == null || ssid.trim().isEmpty()) {
            return "?";
        }
        return ssid.trim().substring(0, 1).toUpperCase(Locale.getDefault());
    }

    private void openDetail(NetworkEntry entry) {
        Intent intent = new Intent(getActivity(), NetworkDetailActivity.class);
        intent.putExtra(NetworkDetailActivity.EXTRA_SSID, entry.ssid);
        intent.putExtra(NetworkDetailActivity.EXTRA_BSSID, entry.bssid);
        intent.putExtra(NetworkDetailActivity.EXTRA_CAPABILITIES, entry.capabilities);
        intent.putExtra(NetworkDetailActivity.EXTRA_LEVEL, entry.level);
        intent.putExtra(NetworkDetailActivity.EXTRA_FREQUENCY, entry.frequency);
        intent.putExtra(NetworkDetailActivity.EXTRA_TIMESTAMP, entry.timestamp);
        startActivity(intent);
    }

    // ---- permissions and lifecycle ---------------------------------------

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        Context context = host();
        return context != null
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            scanSummary.setText(getString(R.string.wifi_permission_check_unsupported));
            return;
        }
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_WIFI_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_WIFI_PERMISSION) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else if (scanSummary != null) {
            scanSummary.setText(getString(R.string.wifi_scan_permission_missing));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        registerScanReceiver();
        refreshConnectionInfo();
        if (hasScanPermission()) {
            loadScanResults();
        }
        // Respect the user's preference: the automatic probe is skipped when
        // it was turned off in the settings.
        if (SettingsStore.autoRootCheck(host())) {
            checkRootWarning();
        } else if (rootWarning != null) {
            rootWarning.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPause() {
        if (receiverRegistered) {
            try {
                Context context = host();
                if (context != null) {
                    context.unregisterReceiver(scanReceiver);
                }
            } catch (IllegalArgumentException ignored) {
                // Already unregistered by the system.
            }
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void registerScanReceiver() {
        if (receiverRegistered) {
            return;
        }
        Context context = host();
        if (context == null) {
            return;
        }
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(scanReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception failure) {
            receiverRegistered = false;
        }
    }

    private void checkRootWarning() {
        new Thread(() -> {
            boolean granted = RootShell.hasRoot(getContext());
            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (rootWarning == null) {
                    return;
                }
                rootAvailable = granted;
                rootWarning.setVisibility(granted ? View.GONE : View.VISIBLE);
            });
        }, "wifitester-root-warning").start();
    }

    // ---- small helpers ----------------------------------------------------

    private void openWifiPanel() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            } else {
                openWifiSettings();
            }
        } catch (Exception failure) {
            openWifiSettings();
        }
    }

    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception failure) {
            MainActivity.notify(getActivity(), getString(R.string.wifi_settings_failed));
        }
    }

    private String cleanSsid(String value) {
        return value == null ? getString(R.string.detail_unknown) : value.replace("\"", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty()
                || "<unknown ssid>".equalsIgnoreCase(value);
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? getString(R.string.detail_unknown) : value;
    }

    private String bandOf(int frequency) {
        if (frequency >= 5925) {
            return getString(R.string.band_6);
        }
        if (frequency >= 4900) {
            return getString(R.string.band_5);
        }
        return frequency > 0 ? getString(R.string.band_24) : getString(R.string.band_unknown);
    }

    private String ipToString(int ip) {
        if (ip == 0) {
            return getString(R.string.detail_unknown);
        }
        return (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
    }
}
