package me.paranoid.wifikill;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Third tab: appearance, capture defaults, Root handling, safety switches and
 * app info.
 */
public final class SettingsFragment extends Fragment {

    private TextView rootState;

    /**
     * The attached host context. The framework {@code Fragment} API has no
     * {@code requireContext()}, so the context is resolved defensively.
     */
    private Context host() {
        Context context = getActivity();
        return context != null ? context : getContext();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ScrollView scroll = LightKit.page(getActivity());
        LinearLayout root = LightKit.root(getActivity(), scroll);
        LightKit.header(root, getActivity(), getString(R.string.settings_eyebrow), getString(R.string.settings_title),
                getString(R.string.settings_subtitle));

        root.addView(buildAppearanceCard(), LightKit.bottom(getActivity(), 12));
        root.addView(buildCaptureCard(), LightKit.bottom(getActivity(), 12));
        root.addView(buildRootCard(), LightKit.bottom(getActivity(), 12));
        root.addView(buildSafetyCard(), LightKit.bottom(getActivity(), 12));
        root.addView(buildToolsCard(), LightKit.bottom(getActivity(), 12));
        root.addView(buildAboutCard());

        return scroll;
    }

    // ---- appearance --------------------------------------------------------

    private LinearLayout buildAppearanceCard() {
        LinearLayout card = LightKit.card(getActivity());
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_appearance), 16, LightKit.INK, true));

        card.addView(LightKit.text(getActivity(), getString(R.string.settings_theme), 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 10));
        addOptions(card, AppConfig.THEME_MODES, themeLabels(), AppConfig::themeMode,
                (mode) -> AppConfig.setThemeMode(host(), mode),
                () -> AppConfig.applyTheme(host()));

        card.addView(LightKit.text(getActivity(), getString(R.string.settings_language), 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 14));
        addOptions(card, AppConfig.LANGUAGES, languageLabels(), AppConfig::language,
                (language) -> AppConfig.setLanguage(host(), language),
                () -> AppConfig.applyLanguage(host()));
        card.addView(LightKit.text(getActivity(),
                getString(R.string.settings_appearance_detail),
                12, LightKit.MUTED, false), LightKit.top(getActivity(), 10));
        return card;
    }

    /** The display names of the theme options. */
    private String[] themeLabels() {
        String[] labels = new String[AppConfig.THEME_MODES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(AppConfig.themeLabel(host(), AppConfig.THEME_MODES[i]));
        }
        return labels;
    }

    /** The display names of the language options. */
    private String[] languageLabels() {
        String[] labels = new String[AppConfig.LANGUAGES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(AppConfig.languageLabel(host(), AppConfig.LANGUAGES[i]));
        }
        return labels;
    }

    /**
     * Renders a segmented row of exclusive choices.
     *
     * @param parent  the card the row is added to
     * @param values  the stored values
     * @param labels  the display names of the values
     * @param current reads the currently active value
     * @param persist stores the picked value
     * @param apply   applies the new value; this may recreate the activity and
     *                therefore has to run after the row was repainted
     */
    private void addOptions(LinearLayout parent, String[] values, String[] labels,
                            CurrentProvider current, ChoiceListener persist, Runnable apply) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            TextView choice = LightKit.text(getActivity(), labels[i], 13, LightKit.INK, true);
            choice.setGravity(android.view.Gravity.CENTER);
            choice.setPadding(dp(4), dp(11), dp(4), dp(11));
            choice.setMinHeight(dp(44));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.rightMargin = dp(6);
            row.addView(choice, params);
            styleChoice(choice, current, value);
            choice.setOnClickListener(v -> {
                // Store first, repaint second and apply last: applying a new
                // theme or locale recreates the activity, after which the
                // views of this row must not be touched any more.
                persist.onPick(value);
                for (int j = 0; j < row.getChildCount(); j++) {
                    TextView sibling = (TextView) row.getChildAt(j);
                    styleChoice(sibling, current, values[j]);
                }
                apply.run();
            });
        }
        parent.addView(row, LightKit.top(getActivity(), 7));
    }

    private void styleChoice(TextView choice, CurrentProvider current, String value) {
        boolean active = value.equals(current.current(host()));
        choice.setBackground(UiKit.segment(host(), active));
        choice.setTextColor(UiKit.color(host(), active ? LightKit.ACCENT : LightKit.INK));
    }

    // ---- capture ----------------------------------------------------------

    private LinearLayout buildCaptureCard() {
        LinearLayout card = LightKit.card(getActivity());
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_capture), 16, LightKit.INK, true));
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_packet_limit), 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 10));
        addNumberChoices(card, new String[]{"5,000", "20,000", "50,000"},
                SettingsStore.PACKET_LIMIT_CHOICES,
                SettingsStore.KEY_CAPTURE_PACKET_LIMIT,
                SettingsStore.DEFAULT_PACKET_LIMIT);
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_snaplen), 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 14));
        addNumberChoices(card, new String[]{"256 B", "512 B", "1024 B"},
                SettingsStore.SNAPLEN_CHOICES,
                SettingsStore.KEY_CAPTURE_SNAPLEN,
                SettingsStore.DEFAULT_SNAPLEN);
        card.addView(LightKit.text(getActivity(),
                getString(R.string.settings_capture_hint),
                12, LightKit.MUTED, false), LightKit.top(getActivity(), 10));
        return card;
    }

    private void addNumberChoices(LinearLayout parent, String[] labels, int[] values,
                                  String key, int defaultValue) {
        String[] stored = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            stored[i] = String.valueOf(values[i]);
        }
        addOptions(parent, stored, labels,
                context -> String.valueOf(SettingsStore.prefs(context).getInt(key, defaultValue)),
                value -> SettingsStore.prefs(host()).edit().putInt(key, Integer.parseInt(value)).apply(),
                () -> {
                    // The capture defaults only change the stored value, the
                    // workbenches read them when they start.
                });
    }

    // ---- root -------------------------------------------------------------

    private LinearLayout buildRootCard() {
        LinearLayout card = LightKit.card(getActivity());
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_root), 16, LightKit.INK, true));

        MaterialSwitch autoRoot = UiKit.switchView(host(), SettingsStore.autoRootCheck(host()),
                (button, checked) -> SettingsStore.setAutoRootCheck(host(), checked));
        card.addView(switchRow(getString(R.string.settings_auto_root),
                getString(R.string.settings_auto_root_detail), autoRoot));

        rootState = LightKit.text(getActivity(), getString(R.string.settings_root_state_pending), 13, LightKit.MUTED, false);
        card.addView(rootState, LightKit.top(getActivity(), 10));

        MaterialButton check = LightKit.secondary(getActivity(), getString(R.string.settings_recheck_root));
        check.setOnClickListener(v -> checkRoot());
        card.addView(check, LightKit.top(getActivity(), 10));

        card.addView(LightKit.text(getActivity(),
                getString(R.string.settings_root_hint),
                13, LightKit.MUTED, false), LightKit.top(getActivity(), 6));
        return card;
    }

    private void checkRoot() {
        if (rootState == null) {
            return;
        }
        rootState.setText(getString(R.string.settings_root_state_checking));
        new Thread(() -> {
            boolean granted = RootShell.hasRoot(getContext());
            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (rootState == null) {
                    return;
                }
                rootState.setText(granted
                        ? getString(R.string.settings_root_state_ok)
                        : getString(R.string.settings_root_state_missing));
                rootState.setTextColor(UiKit.color(host(), granted ? LightKit.SUCCESS : LightKit.DANGER));
            });
        }, "wifitester-settings-root").start();
    }

    // ---- safety -----------------------------------------------------------

    private LinearLayout buildSafetyCard() {
        LinearLayout card = LightKit.card(getActivity());
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_safety), 16, LightKit.INK, true));

        MaterialSwitch ack = UiKit.switchView(host(), SettingsStore.attackAuthorized(host()), null);
        ack.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                SettingsStore.setAttackAuthorized(host(), false);
                return;
            }
            androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(host())
                    .setTitle(getString(R.string.settings_attack_ack_title))
                    .setMessage(getString(R.string.settings_attack_ack_message))
                    .setPositiveButton(getString(R.string.settings_attack_ack_confirm), (d, which) ->
                            SettingsStore.setAttackAuthorized(host(), true))
                    .setNegativeButton(getString(R.string.action_cancel), (d, which) ->
                            SettingsStore.setAttackAuthorized(host(), false))
                    .create();
            // Dismissing the dialog in any other way must not leave the switch
            // in a state that does not match the stored authorization.
            dialog.setOnDismissListener(d -> {
                boolean stored = SettingsStore.attackAuthorized(host());
                if (ack.isChecked() != stored) {
                    ack.setChecked(stored);
                }
            });
            dialog.show();
        });
        card.addView(switchRow(getString(R.string.settings_attack_ack),
                getString(R.string.settings_attack_ack_detail), ack));

        card.addView(LightKit.text(getActivity(), getString(R.string.settings_wordlist), 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 14));
        TextInputLayout wordlistLayout = UiKit.inputLayout(host(), getString(R.string.settings_wordlist));
        TextInputEditText wordlist = UiKit.input(host(), "/data/local/tmp/wordlist.txt", true);
        wordlist.setText(SettingsStore.wordlistPath(host()));
        wordlistLayout.addView(wordlist);
        card.addView(wordlistLayout, LightKit.top(getActivity(), 6));

        MaterialButton saveWordlist = LightKit.secondary(getActivity(), getString(R.string.settings_save_wordlist));
        saveWordlist.setOnClickListener(v -> {
            SettingsStore.setWordlistPath(host(), wordlist.getText() == null ? "" : wordlist.getText().toString());
            MainActivity.notify(getActivity(), getString(R.string.settings_wordlist_saved));
        });
        card.addView(saveWordlist, LightKit.top(getActivity(), 8));

        card.addView(LightKit.text(getActivity(), getString(R.string.settings_data_interface), 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 14));
        TextInputLayout interfaceLayout = UiKit.inputLayout(host(), getString(R.string.settings_data_interface));
        TextInputEditText dataInterface = UiKit.input(host(), "wlan0", true);
        dataInterface.setText(SettingsStore.defaultInterface(host()));
        interfaceLayout.addView(dataInterface);
        card.addView(interfaceLayout, LightKit.top(getActivity(), 6));

        MaterialButton saveInterface = LightKit.secondary(getActivity(), getString(R.string.settings_save_interface));
        saveInterface.setOnClickListener(v -> {
            String value = dataInterface.getText() == null ? "" : dataInterface.getText().toString().trim();
            if (!value.isEmpty() && !value.matches("[A-Za-z0-9_.-]{1,15}")) {
                MainActivity.notify(getActivity(), getString(R.string.settings_interface_invalid));
                return;
            }
            SettingsStore.setDefaultInterface(host(), value);
            MainActivity.notify(getActivity(), getString(R.string.settings_interface_saved));
        });
        card.addView(saveInterface, LightKit.top(getActivity(), 8));
        card.addView(LightKit.text(getActivity(),
                getString(R.string.settings_mitm_hint),
                12, LightKit.MUTED, false), LightKit.top(getActivity(), 10));

        MaterialSwitch keepAwake = UiKit.switchView(host(), SettingsStore.keepScreenOn(host()), null);
        keepAwake.setOnCheckedChangeListener((button, checked) -> {
            SettingsStore.setKeepScreenOn(host(), checked);
            if (getActivity() != null) {
                getActivity().getWindow().addFlags(
                        checked ? android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0);
            }
        });
        card.addView(switchRow(getString(R.string.settings_keep_awake),
                getString(R.string.settings_keep_awake_detail), keepAwake));

        return card;
    }

    /** A titled switch row with an explanatory sub line. */
    private LinearLayout switchRow(String title, String detail, View toggle) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(getActivity());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(LightKit.text(getActivity(), title, 14, LightKit.INK, true),
                LightKit.top(getActivity(), 8));
        labels.addView(LightKit.text(getActivity(), detail, 12, LightKit.MUTED, false),
                LightKit.top(getActivity(), 3));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(toggle);
        return row;
    }

    // ---- tools ------------------------------------------------------------

    private LinearLayout buildToolsCard() {
        LinearLayout card = LightKit.card(getActivity());
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_tools_title), 16, LightKit.INK, true));

        ToolPackageManager toolManager = new ToolPackageManager(getActivity());
        LinearLayout container = new LinearLayout(getActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        
        // 添加设备信息
        TextView deviceInfo = LightKit.text(getActivity(), 
                "设备信息: " + toolManager.getDeviceMonitorDescription(), 12, LightKit.MUTED, false);
        card.addView(deviceInfo, LightKit.top(getActivity(), 8));
        
        long totalSize = 0;
        for (ToolPackageManager.ToolPackage pkg : toolManager.getAllPackages()) {
            totalSize += pkg.getSizeBytes(getActivity());
            
            LinearLayout item = new LinearLayout(getActivity());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setPadding(dp(16), dp(12), dp(16), dp(12));
            
            LinearLayout info = new LinearLayout(getActivity());
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(0, 0, dp(16), 0);
            
            TextView name = LightKit.text(getActivity(), pkg.name, 14, LightKit.INK, true);
            info.addView(name);
            
            TextView size = LightKit.text(getActivity(), pkg.getSizeFormatted(getActivity()), 12, LightKit.MUTED, false);
            info.addView(size);
            
            // 添加工具列表
            TextView tools = LightKit.text(getActivity(), 
                    "工具: " + String.join(", ", pkg.tools), 11, LightKit.MUTED, false);
            info.addView(tools);
            
            item.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            
            if (pkg.preInstalled) {
                TextView status = LightKit.text(getActivity(), "内置", 12, LightKit.SUCCESS, false);
                item.addView(status);
            } else {
                MaterialButton action = new MaterialButton(getActivity());
                action.setText(toolManager.isPackageInstalled(pkg) ? "卸载" : "下载");
                action.setTextSize(12);
                action.setMinimumWidth(dp(80));
                action.setOnClickListener(v -> {
                    if (toolManager.isPackageInstalled(pkg)) {
                        showUninstallDialog(pkg);
                    } else {
                        startDownload(pkg);
                    }
                });
                item.addView(action);
            }
            
            container.addView(item);
            
            // 添加分隔线
            if (pkg != toolManager.getAllPackages().get(toolManager.getAllPackages().size() - 1)) {
                View divider = new View(getActivity());
                divider.setBackgroundColor(UiKit.color(getActivity(), LightKit.MUTED));
                divider.setMinimumHeight(1);
                container.addView(divider);
            }
        }
        
        card.addView(container, LightKit.top(getActivity(), 10));
        
        TextView totalInfo = LightKit.text(getActivity(), 
                "总占用空间: " + formatFileSize(totalSize), 12, LightKit.MUTED, false);
        card.addView(totalInfo, LightKit.top(getActivity(), 8));
        
        return card;
    }
    
    private void showUninstallDialog(ToolPackageManager.ToolPackage pkg) {
        new MaterialAlertDialogBuilder(host())
                .setTitle(getString(R.string.tools_uninstall_title))
                .setMessage(getString(R.string.tools_uninstall_confirm, pkg.name))
                .setPositiveButton(getString(R.string.action_yes), (dialog, which) -> {
                    ToolPackageManager toolManager = new ToolPackageManager(getActivity());
                    toolManager.uninstallPackage(pkg);
                    MainActivity.notify(getActivity(), pkg.name + " 已卸载");
                    getActivity().recreate();
                })
                .setNegativeButton(getString(R.string.action_no), null)
                .show();
    }
    
    private void startDownload(ToolPackageManager.ToolPackage pkg) {
        ToolInstallerService.start(getActivity(), pkg);
        MainActivity.notify(getActivity(), "开始下载 " + pkg.name);
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ---- about ------------------------------------------------------------

    private LinearLayout buildAboutCard() {
        LinearLayout card = LightKit.card(getActivity());
        card.addView(LightKit.text(getActivity(), getString(R.string.settings_about), 16, LightKit.INK, true));
        card.addView(LightKit.text(getActivity(),
                getString(R.string.settings_about_version, "2.5.1"), 13, LightKit.MUTED, false),
                LightKit.top(getActivity(), 6));
        card.addView(LightKit.text(getActivity(),
                getString(R.string.settings_about_note),
                12, LightKit.MUTED, false), LightKit.top(getActivity(), 8));

        MaterialButton repository = LightKit.secondary(getActivity(), getString(R.string.settings_repository));
        repository.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/KotobaWakusei/wifitester")));
            } catch (Exception failure) {
                MainActivity.notify(getActivity(), getString(R.string.settings_open_browser_failed));
            }
        });
        card.addView(repository, LightKit.top(getActivity(), 10));
        return card;
    }

    // ---- small contracts --------------------------------------------------

    /** Reads the currently active value. */
    private interface CurrentProvider {
        String current(Context context);
    }

    /** Invoked when the user picks another value. */
    private interface ChoiceListener {
        void onPick(String value);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
