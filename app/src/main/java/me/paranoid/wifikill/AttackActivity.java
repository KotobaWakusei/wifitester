package me.paranoid.wifikill;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Workbench for the active test modules.
 *
 * <p>Every module that puts frames on the air is gated behind an explicit
 * authorization acknowledgement and a per-run confirmation dialog that spells
 * out the target, the tool and the command that will be executed. The offline
 * module reads local files only and therefore carries no radio risk.</p>
 */
public final class AttackActivity extends BaseActivity {

    public static final String EXTRA_TARGET_BSSID = "target_bssid";
    public static final String EXTRA_TARGET_SSID = "target_ssid";
    public static final String EXTRA_TARGET_CHANNEL = "target_channel";

    private int BG;
    private int PANEL;
    private int INK;
    private int ACCENT;
    private int MUTED;
    private int WARN_BG;
    private int WARN_TEXT;
    private int DANGER_BG;
    private int DANGER_TEXT;

    private static final String CAPTURE_DIRECTORY = "attack-captures";
    private static final String MONITOR_INTERFACE = "wfkmon0";
    private String monitorInterface = MONITOR_INTERFACE;

    private TextView statusState;
    private TextView toolState;
    private TextView logView;
    private TextView logCounter;
    private TextView attackStatusIndicator;
    private MaterialButton refreshEnvironment;
    private MaterialButton wordlistPickButton;
    private ScrollView pageScroll;
    private Spinner moduleSpinner;
    private EditText bssidInput;
    private EditText ssidInput;
    private EditText clientInput;
    private EditText channelInput;
    private EditText countInput;
    private EditText gatewayInput;
    private EditText victimInput;
    private EditText dataInterfaceInput;
    private EditText passInput;
    private EditText wordlistInput;
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton autofillButton;
    private TextView autofillState;

    private final StringBuilder log = new StringBuilder();
    private final List<AttackCatalog.Module> modules = AttackCatalog.modules();
    private RootProcess managed;
    private boolean detecting;
    private String statusMessage;
    private int statusColor;
    private long attackElapsedSince;
    private android.os.Handler elapsedTicker;
    private boolean rootGranted;
    private boolean monitorPresent;
    private ToolPackageManager.DeviceMonitorCapability monitorCapability = ToolPackageManager.DeviceMonitorCapability.UNKNOWN;
    private final java.util.Map<String, String> toolPaths = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BG = UiKit.color(this, Attrs.COLOR_SURFACE);
        PANEL = UiKit.color(this, Attrs.COLOR_SURFACE_CONTAINER_LOWEST);
        INK = UiKit.color(this, Attrs.COLOR_ON_SURFACE);
        ACCENT = UiKit.color(this, Attrs.COLOR_PRIMARY);
        MUTED = UiKit.color(this, Attrs.COLOR_ON_SURFACE_VARIANT);
        WARN_BG = UiKit.color(this, R.attr.colorWarningContainer);
        WARN_TEXT = UiKit.color(this, R.attr.colorOnWarningContainer);
        DANGER_BG = UiKit.color(this, Attrs.COLOR_ERROR_CONTAINER);
        DANGER_TEXT = UiKit.color(this, Attrs.COLOR_ON_ERROR_CONTAINER);
        buildUi();
        applyTarget(getIntent());
        detectEnvironment();
        installBackGuard();
    }

    /**
     * Leaving the screen while a module runs would stop the process silently
     * (see {@link #onDestroy()}), so ask first instead of killing a test by
     * accident.
     */
    private void installBackGuard() {
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (managed == null) {
                            setEnabled(false);
                            getOnBackPressedDispatcher().onBackPressed();
                            return;
                        }
                        new MaterialAlertDialogBuilder(AttackActivity.this)
                                .setTitle(getString(R.string.attack_leave_title))
                                .setMessage(getString(R.string.attack_leave_detail))
                                .setPositiveButton(getString(R.string.attack_leave_confirm), (dialog, which) -> {
                                    setEnabled(false);
                                    getOnBackPressedDispatcher().onBackPressed();
                                })
                                .setNegativeButton(getString(R.string.action_cancel), null)
                                .show();
                    }
                });
    }

    // ---- layout -----------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(26));
        scroll.addView(root);

        root.addView(text(getString(R.string.attack_eyebrow), 11, ACCENT, true));
        root.addView(text(getString(R.string.attack_title), 30, INK, true), top(7));
        root.addView(text(getString(R.string.attack_subtitle),
                14, MUTED, false), top(5));

        root.addView(buildAuthorizationCard(), top(16));
        root.addView(buildStatusCard(), top(12));
        root.addView(buildTargetCard(), top(12));
        root.addView(buildModuleCard(), top(12));
        root.addView(buildParameterCard(), top(12));
        root.addView(buildActionCard(), top(12));

        root.addView(buildLogCard(), top(18));

        setContentView(scroll);
    }

    /**
     * The execution log.
     *
     * <p>The log is rendered as a terminal style card: a monospaced surface
     * with a status chip, a running line counter and a row of actions.</p>
     */
    private LinearLayout buildLogCard() {
        logView = UiKit.logView(this);
        logView.setText(getString(R.string.attack_log_waiting));
        LinearLayout card = UiKit.logCard(this, logView, getString(R.string.attack_log_title));
        logCounter = card.findViewWithTag(UiKit.LOG_COUNTER_TAG);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton export = tonalButton(getString(R.string.attack_export_log));
        export.setOnClickListener(v -> exportLog());
        actions.addView(export, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton clear = UiKit.textButton(this, getString(R.string.attack_clear_log));
        clear.setOnClickListener(v -> clearLog());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(-2, -2);
        clearParams.leftMargin = dp(6);
        actions.addView(clear, clearParams);
        card.addView(actions, top(8));

        return card;
    }

    /** Clears the visible log buffer. */
    private void clearLog() {
        synchronized (log) {
            log.setLength(0);
        }
        if (logView != null) {
            logView.setText("");
        }
        updateLogCounter();
    }

    /** Refreshes the line counter shown in the log header. */
    private void updateLogCounter() {
        int lines = 0;
        synchronized (log) {
            lines = UiKit.countLines(log);
        }
        if (logCounter != null) {
            logCounter.setText(getString(R.string.attack_log_lines, lines));
        }
    }

    private LinearLayout buildAuthorizationCard() {
        LinearLayout card = card();
        TextView title = text(getString(R.string.attack_authorization), 15, INK, true);
        card.addView(title);
        TextView body = text(getString(R.string.attack_authorization_detail),
                12, MUTED, false);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.setBackground(round(WARN_BG, dp(10)));
        card.addView(body, top(8));
        MaterialButton open = tonalButton(getString(R.string.attack_open_settings));
        open.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_TAB, 2);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });
        card.addView(open, top(8));
        return card;
    }

    private LinearLayout buildStatusCard() {
        LinearLayout card = card();
        card.addView(text(getString(R.string.attack_environment), 16, INK, true));
        statusState = text(getString(R.string.attack_root_checking), 13, MUTED, false);
        card.addView(statusState, top(8));
        toolState = text(getString(R.string.attack_tool_checking), 12, MUTED, false);
        card.addView(toolState, top(5));
        MaterialButton refresh = tonalButton(getString(R.string.attack_recheck));
        refreshEnvironment = refresh;
        refresh.setOnClickListener(v -> detectEnvironment());
        card.addView(refresh, top(10));
        return card;
    }

    private LinearLayout buildTargetCard() {
        LinearLayout card = card();
        card.addView(text(getString(R.string.attack_target), 16, INK, true));
        card.addView(text(getString(R.string.attack_target_detail),
                12, MUTED, false), top(4));

        bssidInput = input("aa:bb:cc:dd:ee:ff", getString(R.string.attack_label_bssid));
        card.addView(bssidInput, top(10));
        ssidInput = input(getString(R.string.attack_hint_target_ssid), getString(R.string.attack_label_ssid));
        card.addView(ssidInput, top(8));
        channelInput = input("6", getString(R.string.attack_label_channel));
        channelInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.addView(channelInput, top(8));
        clientInput = input(getString(R.string.attack_hint_all_clients), getString(R.string.attack_label_client));
        card.addView(clientInput, top(8));
        gatewayInput = input("192.168.1.1", getString(R.string.attack_label_gateway));
        card.addView(gatewayInput, top(8));
        victimInput = input("192.168.1.100", getString(R.string.attack_label_victim));
        card.addView(victimInput, top(8));
        dataInterfaceInput = input("wlan0", getString(R.string.attack_label_data_interface));
        String savedInterface = SettingsStore.defaultInterface(this);
        if (!savedInterface.isEmpty()) {
            dataInterfaceInput.setText(savedInterface);
        }
        card.addView(dataInterfaceInput, top(8));
        autofillButton = tonalButton(getString(R.string.attack_autofill));
        autofillButton.setOnClickListener(v -> runAutofill(true));
        card.addView(autofillButton, top(12));
        autofillState = text(getString(R.string.attack_autofill_hint), 12, MUTED, false);
        card.addView(autofillState, top(6));
        return card;
    }

    private LinearLayout buildModuleCard() {
        LinearLayout card = card();
        card.addView(text(getString(R.string.attack_module), 16, INK, true));
        List<String> labels = new ArrayList<>();
        for (AttackCatalog.Module module : modules) {
            labels.add(getString(module.titleRes) + "  ·  " + getString(module.risk.labelRes));
        }
        moduleSpinner = new Spinner(this);
        moduleSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
        card.addView(moduleSpinner, top(4));

        TextView description = text("", 12, MUTED, false);
        card.addView(description, top(8));
        moduleSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AttackCatalog.Module module = modules.get(position);
                description.setText(getString(module.descriptionRes)
                        + "\n\n" + getString(R.string.attack_needs_tool_label) + module.tool
                        + getString(R.string.attack_risk_level_label) + getString(module.risk.labelRes)
                        + (module.requiresMonitor ? getString(R.string.attack_requires_monitor) : ""));
                applyModuleDefaults(module);
                updateFieldVisibility(module);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                // The first module is shown by default.
            }
        });
        return card;
    }

    private LinearLayout buildParameterCard() {
        LinearLayout card = card();
        card.addView(text(getString(R.string.attack_parameters), 16, INK, true));
        countInput = input(getString(R.string.attack_hint_count), getString(R.string.attack_frame_count));
        countInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        // An empty field was parsed as zero, which some aireplay-ng modes interpret
        // as an unlimited run. Use a bounded lab-test default instead.
        countInput.setText("10");
        card.addView(countInput, top(8));
        passInput = input(getString(R.string.attack_hint_passphrase), getString(R.string.attack_passphrase));
        card.addView(passInput, top(8));
        wordlistInput = input("/data/local/tmp/wordlist.txt", getString(R.string.attack_wordlist));
        String savedWordlist = SettingsStore.wordlistPath(this);
        if (savedWordlist != null && !savedWordlist.trim().isEmpty()) {
            wordlistInput.setText(savedWordlist.trim());
        }
        card.addView(wordlistInput, top(8));
        MaterialButton pick = tonalButton(getString(R.string.attack_use_saved_wordlist));
        wordlistPickButton = pick;
        pick.setOnClickListener(v -> wordlistInput.setText(SettingsStore.wordlistPath(this)));
        card.addView(pick, top(8));
        return card;
    }

    private LinearLayout buildActionCard() {
        LinearLayout card = card();
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = primaryButton(getString(R.string.attack_start));
        stopButton = tonalButton(getString(R.string.attack_stop));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1);
        half.rightMargin = dp(5);
        actions.addView(startButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        half2.leftMargin = dp(5);
        actions.addView(stopButton, half2);
        card.addView(actions);
        
        // 初始状态：停止按钮禁用，启动按钮根据权限状态
        stopButton.setEnabled(false);
        startButton.setEnabled(false);
        
        startButton.setOnClickListener(v -> confirmStart());
        stopButton.setOnClickListener(v -> stopModule());

        // 添加攻击状态指示器
        attackStatusIndicator = text("", 12, MUTED, false);
        attackStatusIndicator.setVisibility(View.GONE);
        card.addView(attackStatusIndicator, top(8));

        MaterialButton analyze = tonalButton(getString(R.string.attack_analyze_frames));
        analyze.setOnClickListener(v -> startActivity(new Intent(this, FrameAnalysisActivity.class)));
        card.addView(analyze, top(8));
        MaterialButton inspect = tonalButton(getString(R.string.attack_open_capture_analysis));
        inspect.setOnClickListener(v -> startActivity(new Intent(this, AircrackToolkitActivity.class)));
        card.addView(inspect, top(8));
        return card;
    }

    // ---- environment ------------------------------------------------------

    private void detectEnvironment() {
        // Re-checking while a probe is in flight used to start a second su
        // session; the two answers could then overwrite each other.
        if (detecting) return;
        detecting = true;
        if (refreshEnvironment != null) refreshEnvironment.setEnabled(false);
        statusState.setText(getString(R.string.attack_detect_root));
        toolState.setText(getString(R.string.attack_detect_tools));
        startButton.setEnabled(false);
        new Thread(() -> {
            // Extract the bundled toolchain before probing. On a fresh install
            // the first screen visited may be this workbench, and without the
            // extraction every bundled tool would look missing.
            new BundledToolchain(this).install();
            boolean granted = RootShell.hasRoot(this);
            RootShell.Result probe = granted
                    ? RootShell.run(this, ToolCatalog.probeCommand(), RootShell.TIMEOUT_PROBE)
                    : new RootShell.Result(-1, "");
            toolPaths.clear();
            for (ToolCatalog.Tool tool : ToolCatalog.parse(probe.output)) {
                // ConcurrentHashMap rejects null values; a missing tool is
                // represented by get() returning null, which is exactly what
                // every reader (describeTools/isToolAvailable) checks for.
                if (tool.path != null) {
                    toolPaths.put(tool.name, tool.path);
                }
            }
            // The monitor interface is a device state, not a tool: it has to be
            // queried separately with "iw dev".
            String detectedMonitorInterface = "";
            if (granted) {
                RootShell.Result devices = RootShell.run(this, "iw dev 2>&1", RootShell.TIMEOUT_SHORT);
                detectedMonitorInterface = findMonitorInterface(devices.output);
                if (detectedMonitorInterface.isEmpty()) {
                    RootShell.Result conMode = RootShell.run(this,
                            "cat /sys/module/qca_cld3_peach_v2/parameters/con_mode 2>/dev/null",
                            RootShell.TIMEOUT_SHORT);
                    if ("4".equals(conMode.output == null ? "" : conMode.output.trim())) {
                        String saved = SettingsStore.defaultInterface(this);
                        detectedMonitorInterface = saved.isEmpty() ? "wlan0" : saved;
                    }
                }
            }
            final String currentMonitorInterface = detectedMonitorInterface;
            final boolean hasMonitor = !detectedMonitorInterface.isEmpty();
            
            // 检查设备监听模式支持情况
            ToolPackageManager toolManager = new ToolPackageManager(this);
            // Captured by the UI lambda below: must be final, and must not
            // shadow the field of the same name.
            final ToolPackageManager.DeviceMonitorCapability deviceCapability =
                    toolManager.getDeviceMonitorCapability();

            final boolean hasRoot = granted;
            final boolean monitor = hasMonitor;
            runOnUiThread(() -> {
                detecting = false;
                if (refreshEnvironment != null) refreshEnvironment.setEnabled(true);
                rootGranted = hasRoot;
                monitorPresent = monitor;
                monitorInterface = currentMonitorInterface.isEmpty() ? MONITOR_INTERFACE : currentMonitorInterface;
                monitorCapability = deviceCapability;
                statusState.setText(hasRoot ? getString(R.string.attack_root_granted) : getString(R.string.attack_root_missing));
                statusState.setTextColor(hasRoot ? 0xff79e0b0 : DANGER_TEXT);
                toolState.setText(describeTools());

                // Fill only fields that the user has not edited; never invent a target.
                if (SettingsStore.attackAutofill(this)) {
                    runAutofill(hasRoot);
                } else if (autofillButton != null) {
                    autofillButton.setEnabled(false);
                    autofillState.setText(getString(R.string.attack_autofill_disabled));
                }

                // 显示设备监听模式支持情况
                append(getString(R.string.attack_device_info, describeCapability(deviceCapability)));
                
                boolean authorized = SettingsStore.attackAuthorized(this);
                // Never re-enable the start button while a module is still
                // running: overwriting "managed" would leak the old process.
                startButton.setEnabled(hasRoot && authorized && managed == null);
                if (!authorized) {
                    append(getString(R.string.attack_not_authorized));
                }
                if (!hasRoot) {
                    append(getString(R.string.attack_no_root));
                }
                append(monitor
                        ? getString(R.string.attack_monitor_detected, currentMonitorInterface)
                        : getString(R.string.attack_no_monitor, MONITOR_INTERFACE));
                
                // 根据设备能力提供功能建议
                if (deviceCapability == ToolPackageManager.DeviceMonitorCapability.MONITOR_ONLY) {
                    append(getString(R.string.attack_capability_monitor_only));
                } else if (deviceCapability == ToolPackageManager.DeviceMonitorCapability.NO_MONITOR) {
                    append(getString(R.string.attack_capability_no_monitor));
                }
            });
        }, "wifitester-attack-detect").start();
    }

    /** Returns the interface whose current iw dev block says type monitor. */
    private static String findMonitorInterface(String output) {
        if (output == null || output.trim().isEmpty()) return "";
        String[] blocks = output.split("(?m)(?=^\\s*Interface\\s+)");
        for (String block : blocks) {
            java.util.regex.Matcher name = java.util.regex.Pattern
                    .compile("(?m)^\\s*Interface\\s+([A-Za-z0-9_.-]+)\\s*$")
                    .matcher(block);
            if (name.find() && java.util.regex.Pattern
                    .compile("(?m)^\\s*type\\s+monitor\\s*$").matcher(block).find()) {
                return name.group(1);
            }
        }
        // Older iw/driver combinations may omit the type line for the known app interface.
        return output.contains("Interface " + MONITOR_INTERFACE) ? MONITOR_INTERFACE : "";
    }

    /** Localised description of the capability the driver is expected to offer. */
    private String describeCapability(ToolPackageManager.DeviceMonitorCapability capability) {
        switch (capability) {
            case FULL_MONITOR_INJECTION:
                return getString(R.string.attack_capability_full);
            case MONITOR_ONLY:
                return getString(R.string.attack_capability_monitor_only);
            case NO_MONITOR:
                return getString(R.string.attack_capability_no_monitor);
            case REQUIRES_OTG:
                return getString(R.string.attack_capability_requires_otg);
            case UNKNOWN:
            default:
                return getString(R.string.attack_capability_unknown);
        }
    }

    private String describeTools() {
        StringBuilder text = new StringBuilder(getString(R.string.attack_tools_header));
        ToolPackageManager toolManager = new ToolPackageManager(this);
        String[] required = {"aireplay-ng", "airodump-ng", "aircrack-ng", "hostapd", "arpspoof", "iw", "ip"};
        for (String name : required) {
            String path = toolPaths.get(name);
            boolean present = path != null && path.startsWith("/");
            // iw/ip and hostapd are not part of a downloadable package; for
            // those only the actual detection result matters. Requiring a
            // package marked "installed" made system tools show as missing.
            boolean packaged = isPackagedTool(toolManager, name);
            boolean installed = !packaged || toolManager.isToolAvailable(name);

            if (text.length() > 5) {
                text.append("  ");
            }
            text.append(name).append(" ");
            if (present && installed) {
                text.append("✓");
            } else if (installed) {
                text.append("⚠"); // 已安装但未找到
            } else {
                text.append("×"); // 未安装
            }
        }
        text.append(getString(R.string.attack_missing_command_hint));
        return text.toString();
    }

    /** Whether any downloadable package claims to provide this tool. */
    private static boolean isPackagedTool(ToolPackageManager toolManager, String tool) {
        for (ToolPackageManager.ToolPackage pkg : toolManager.getAllPackages()) {
            if (pkg.tools.contains(tool)) return true;
        }
        return false;
    }

    // ---- execution --------------------------------------------------------

    private void confirmStart() {
        if (managed != null) {
            // A module is already running; starting another would leak it.
            toast(getString(R.string.attack_module_running));
            return;
        }
        if (!rootGranted) {
            toast(getString(R.string.attack_needs_root));
            return;
        }
        if (!SettingsStore.attackAuthorized(this)) {
            toast(getString(R.string.attack_needs_authorization));
            return;
        }
        final AttackCatalog.Module module = selectedModule();
        if (module == null) {
            return;
        }
        if (module.requiresMonitor
                && monitorCapability == ToolPackageManager.DeviceMonitorCapability.MONITOR_ONLY
                && module.risk != AttackCatalog.Risk.PASSIVE) {
            toast(getString(R.string.attack_monitor_only_blocked));
            return;
        }
        if (module.requiresMonitor && !monitorPresent) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.attack_needs_monitor_title))
                    .setMessage(getString(R.string.attack_needs_monitor, MONITOR_INTERFACE))
                    .setPositiveButton(getString(R.string.action_open_monitor), (dialog, which) ->
                            startActivity(new Intent(this, MonitorModeActivity.class)))
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show();
            return;
        }
        if (!isToolAvailable(module.tool)) {
            ToolPackageManager toolManager = new ToolPackageManager(this);
            String toolName = module.tool;
            boolean packaged = isPackagedTool(toolManager, toolName);
            boolean packageInstalled = packaged && toolManager.isToolAvailable(toolName);

            if (!packaged) {
                // iw/ip/hostapd are not shipped in a package; pretending a
                // download exists left the user tapping a button that did
                // nothing. Say plainly that the command has to be provided.
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.attack_needs_tool_title))
                        .setMessage(getString(R.string.attack_needs_tool, toolName))
                        .setPositiveButton(getString(R.string.action_open_toolkit), (dialog, which) ->
                                startActivity(new Intent(this, AircrackNgActivity.class)))
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show();
                return;
            }
            if (!packageInstalled) {
                // The package exists but is not complete. Only offer a download
                // when the manifest actually declares one; the bundled packs
                // are extracted from the APK and have no download URL.
                ToolPackageManager.ToolPackage targetPackage = null;
                for (ToolPackageManager.ToolPackage pkg : toolManager.getAllPackages()) {
                    if (pkg.tools.contains(toolName)) {
                        targetPackage = pkg;
                        break;
                    }
                }
                final ToolPackageManager.ToolPackage downloadPackage =
                        targetPackage != null && toolManager.canDownload(targetPackage)
                                ? targetPackage : null;
                if (downloadPackage != null) {
                    final ToolPackageManager.ToolPackage pkg = downloadPackage;
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.attack_needs_tool_title))
                            .setMessage(getString(R.string.attack_needs_tool_download, toolName))
                            .setPositiveButton(getString(R.string.action_download), (dialog, which) -> {
                                ToolInstallerService.start(this, pkg);
                                toast(getString(R.string.tool_download_started, pkg.name));
                            })
                            .setNeutralButton(getString(R.string.action_open_toolkit), (dialog, which) ->
                                    startActivity(new Intent(this, AircrackNgActivity.class)))
                            .setNegativeButton(getString(R.string.action_cancel), null)
                            .show();
                } else {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.attack_tool_missing_title))
                            .setMessage(getString(R.string.attack_tool_missing, toolName))
                            .setPositiveButton(getString(R.string.action_recheck), (dialog, which) ->
                                    detectEnvironment())
                            .setNeutralButton(getString(R.string.action_open_toolkit), (dialog, which) ->
                                    startActivity(new Intent(this, AircrackNgActivity.class)))
                            .setNegativeButton(getString(R.string.action_cancel), null)
                            .show();
                }
                return;
            }
            // The package is installed but the probe did not find the command.
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.attack_tool_missing_title))
                    .setMessage(getString(R.string.attack_tool_missing, toolName))
                    .setPositiveButton(getString(R.string.action_recheck), (dialog, which) ->
                            detectEnvironment())
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show();
            return;
        }

        final String command;
        final String summary;
        try {
            command = buildCommand(module);
            summary = describeCommand(module, command);
        } catch (AttackCommandBuilder.ValidationException invalid) {
            toast(invalid.resolve(this));
            return;
        } catch (IllegalArgumentException invalid) {
            toast(invalid.getMessage());
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.attack_confirm_title))
                .setMessage(summary)
                .setPositiveButton(getString(R.string.action_execute), (dialog, which) -> launch(module, command))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private AttackCatalog.Module selectedModule() {
        int position = moduleSpinner.getSelectedItemPosition();
        if (position < 0 || position >= modules.size()) {
            return null;
        }
        return modules.get(position);
    }

    /** Whether the tool a module depends on was found on the device. */
    private boolean isToolAvailable(String tool) {
        // 首先检查工具是否已安装
        ToolPackageManager toolManager = new ToolPackageManager(this);
        if (!toolManager.isToolAvailable(tool)) {
            return false;
        }
        // 然后检查工具是否实际存在
        String path = toolPaths.get(tool);
        return path != null && path.startsWith("/");
    }

    /** Builds the command for the selected module; shared with the unit tests. */
    String buildCommand(AttackCatalog.Module module) {
        String bssid = bssidInput.getText().toString().trim();
        String client = clientInput.getText().toString().trim();
        String ssid = ssidInput.getText().toString().trim();
        int channel = parseInt(channelInput.getText().toString(), 0);
        int count = parseInt(countInput.getText().toString(), 0);
        String gateway = gatewayInput.getText().toString().trim();
        String passphrase = passInput.getText().toString();
        String wordlist = wordlistInput.getText().toString().trim();

        switch (module.id) {
            case AttackCatalog.ID_DEAUTH:
                return AttackCommandBuilder.deauth("aireplay-ng", monitorInterface,
                        bssid, client, count);
            case AttackCatalog.ID_BEACON:
                return AttackCommandBuilder.beaconFlood("aireplay-ng", monitorInterface,
                        bssid, count);
            case AttackCatalog.ID_AUTH:
                return AttackCommandBuilder.authFlood("aireplay-ng", monitorInterface,
                        bssid, count);
            case AttackCatalog.ID_HANDSHAKE: {
                File directory = new File(getFilesDir(), CAPTURE_DIRECTORY);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalArgumentException(getString(R.string.attack_capture_dir_failed));
                }
                String prefix = new File(directory,
                        "handshake-" + System.currentTimeMillis()).getAbsolutePath();
                return AttackCommandBuilder.captureHandshake("airodump-ng", monitorInterface,
                        bssid, channel, prefix);
            }
            case AttackCatalog.ID_CRACK: {
                File directory = new File(getFilesDir(), CAPTURE_DIRECTORY);
                File[] files = directory.listFiles();
                String capture = null;
                if (files != null) {
                    long newest = Long.MIN_VALUE;
                    for (File file : files) {
                        // airodump-ng writes .cap/.csv/.kismet.csv/.log.csv with
                        // the same prefix and nearly identical timestamps; only
                        // the .cap file is a valid aircrack-ng input.
                        if (file.isFile() && file.getName().endsWith(".cap")
                                && file.lastModified() > newest) {
                            newest = file.lastModified();
                            capture = file.getAbsolutePath();
                        }
                    }
                }
                if (capture == null) {
                    throw new IllegalArgumentException(getString(R.string.attack_no_handshake));
                }
                return AttackCommandBuilder.crackHandshake("aircrack-ng", capture, wordlist);
            }
            case AttackCatalog.ID_EVIL_TWIN: {
                File config = new File(getFilesDir(), "hostapd.conf");
                // Use the detected monitor interface: a hardcoded name points
                // hostapd at an interface that may not exist on this device.
                String content = AttackCommandBuilder.buildHostapdConfig(monitorInterface,
                        "nl80211", ssid.isEmpty() ? bssid : ssid, channel, false,
                        passphrase, false);
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(config),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    writer.write(content);
                } catch (Exception failure) {
                    throw new IllegalArgumentException(getString(R.string.attack_hostapd_config_failed,
                            failure.getMessage()));
                }
                return AttackCommandBuilder.evilTwin("hostapd", config.getAbsolutePath());
            }
            case AttackCatalog.ID_ARP_SPOOF:
                return AttackCommandBuilder.arpSpoof("arpspoof",
                        dataInterfaceInput.getText().toString().trim(),
                        victimInput.getText().toString().trim(),
                        gatewayInput.getText().toString().trim());
            default:
                throw new IllegalArgumentException(getString(R.string.attack_unknown_module));
        }
    }

    private String describeCommand(AttackCatalog.Module module, String command) {
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.attack_confirm_module, getString(module.titleRes))).append('\n');
        text.append(getString(R.string.attack_confirm_risk, getString(module.risk.labelRes))).append('\n');
        if (AttackCatalog.ID_DEAUTH.equals(module.id)
                || AttackCatalog.ID_AUTH.equals(module.id)
                || AttackCatalog.ID_BEACON.equals(module.id)) {
            text.append(getString(R.string.attack_to_target)).append(bssidInput.getText().toString().trim())
                    .append(getString(R.string.attack_risk_disruptive));
        } else if (AttackCatalog.ID_EVIL_TWIN.equals(module.id)) {
            text.append(getString(R.string.attack_risk_evil_twin));
        } else if (AttackCatalog.ID_ARP_SPOOF.equals(module.id)) {
            text.append(getString(R.string.attack_to_victim)).append(victimInput.getText().toString().trim())
                    .append(getString(R.string.attack_risk_arp));
        } else if (AttackCatalog.ID_HANDSHAKE.equals(module.id)) {
            text.append(getString(R.string.attack_risk_handshake));
        } else {
            text.append(getString(R.string.attack_risk_offline_note));
        }
        text.append("\n").append(getString(R.string.attack_confirm_command, command));
        return text.toString();
    }

    private void launch(AttackCatalog.Module module, String command) {
        if (managed != null) return;
        append("===== " + getString(module.titleRes) + " =====");
        append(getString(R.string.attack_command_running, command));
        
        // 更新UI状态
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        startButton.setText(getString(R.string.attack_start_running));
        stopButton.setText(getString(R.string.attack_stop));
        moduleSpinner.setEnabled(false);
        startElapsed();
        updateAttackStatus(getString(R.string.attack_status_running), ACCENT);
        toast(getString(R.string.attack_started_toast));
        // A sleeping CPU would stall the injected frames; keep the screen on
        // for exactly as long as the module runs.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Bring the live output into view: the first lines are the evidence
        // that the module actually started.
        if (pageScroll != null) pageScroll.post(() -> pageScroll.fullScroll(View.FOCUS_DOWN));
        
        // 添加启动提示
        append(getString(R.string.attack_module_started, getString(module.titleRes)));

        managed = RootProcess.start(this, command, new RootProcess.OutputListener() {
            @Override
            public void onLine(String line) {
                append(line);
                // 实时更新攻击状态。onLine runs on the RootProcess output
                // thread; views may only be touched on the UI thread.
                if (line.contains("sent") || line.contains("packets")) {
                    runOnUiThread(() -> updateAttackStatus(
                            getString(R.string.attack_status_sending), ACCENT));
                }
            }

            @Override
            public void onFinished(int exitCode) {
                append(getString(R.string.attack_module_finished, exitCode));
                String statusMessage;
                int statusColor;
                
                if (exitCode == 0) {
                    statusMessage = getString(R.string.attack_status_success);
                    statusColor = 0xff79e0b0; // 绿色
                } else if (exitCode == 130 || exitCode == 143) {
                    statusMessage = getString(R.string.attack_status_stopped);
                    statusColor = WARN_TEXT; // 黄色
                } else {
                    statusMessage = getString(R.string.attack_status_failed, exitCode);
                    statusColor = DANGER_TEXT; // 红色
                }
                
                runOnUiThread(() -> {
                    stopElapsed();
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    startButton.setEnabled(rootGranted
                            && SettingsStore.attackAuthorized(AttackActivity.this));
                    startButton.setText(getString(R.string.attack_start));
                    stopButton.setText(getString(R.string.attack_stop));
                    stopButton.setEnabled(false);
                    moduleSpinner.setEnabled(true);
                    managed = null;
                    updateAttackStatus(statusMessage, statusColor);
                });
            }
        });
        
        if (managed != null) {
            append(getString(R.string.attack_process_started, managed.innerPid()));
        }
    }

    private void stopModule() {
        RootProcess process = managed;
        if (process == null) {
            toast(getString(R.string.attack_no_running_module));
            return;
        }
        
        append(getString(R.string.attack_stopping));
        updateAttackStatus(getString(R.string.attack_status_stopping), WARN_TEXT);
        stopButton.setEnabled(false);
        stopButton.setText(getString(R.string.attack_stop_stopping));
        
        new Thread(() -> {
            process.stop();
            runOnUiThread(() -> {
                append(getString(R.string.attack_module_stopped));
                managed = null;
                stopElapsed();
                stopButton.setText(getString(R.string.attack_stop));
                stopButton.setEnabled(false);
                startButton.setText(getString(R.string.attack_start));
                startButton.setEnabled(rootGranted
                        && SettingsStore.attackAuthorized(AttackActivity.this));
                moduleSpinner.setEnabled(true);
                updateAttackStatus(null, MUTED);
            });
        }, "wifitester-attack-stop").start();
    }

    // ---- target autofill --------------------------------------------------

    /**
     * Fills the target fields from what the device actually reports.
     *
     * <p>Inputs come from <em>both</em> worlds on purpose: the route table, the
     * address and the ARP cache need root, while the scan list is available to
     * any app with the location permission. Only empty fields are written, so
     * a value the user typed (or a target passed from the network list) is
     * never replaced. Nothing is guessed: a field without evidence stays
     * empty and the reason is shown under the button.</p>
     */
    private void runAutofill(boolean includeLocal) {
        if (autofillButton == null) return;
        final String preferredBssid = bssidInput.getText().toString().trim();
        final String preferredSsid = ssidInput.getText().toString().trim();
        // SpannableStringBuilder is not thread safe: snapshot editable fields
        // here on the UI thread instead of reading them from the worker.
        final String dataInterface = dataInterfaceInput.getText().toString().trim();
        if (includeLocal) {
            autofillButton.setEnabled(false);
            autofillState.setText(getString(R.string.attack_autofill_running));
        }
        new Thread(() -> {
            AttackTargetResolver.LocalNetwork network = new AttackTargetResolver.LocalNetwork();
            String neighbourOutput = "";
            if (includeLocal) {
                RootShell.Result route = RootShell.run(this,
                        "ip -4 route show table all 2>/dev/null", RootShell.TIMEOUT_SHORT);
                RootShell.Result address = RootShell.run(this,
                        "ip -4 addr show 2>/dev/null", RootShell.TIMEOUT_SHORT);
                network = AttackTargetResolver.parseLocalNetwork(route.output, address.output);
                String iface = network.routeInterface.isEmpty()
                    ? dataInterface : network.routeInterface;
                if (iface.matches("[A-Za-z0-9_.-]{1,15}")) {
                    RootShell.Result neighbours = RootShell.run(this,
                            "ip -4 neigh show dev " + iface + " 2>/dev/null", RootShell.TIMEOUT_SHORT);
                    neighbourOutput = neighbours.output;
                }
            }
            List<AttackTargetResolver.AccessPoint> scanned = scanAccessPoints(dataInterface);
            final AttackTargetResolver.LocalNetwork local = network;
            final List<AttackTargetResolver.AccessPoint> accessPoints = scanned;
            final String neighbours = neighbourOutput;
            runOnUiThread(() -> applyAutofill(local, neighbours, accessPoints,
                    preferredBssid, preferredSsid, includeLocal));
        }, "wifitester-attack-autofill").start();
    }

    /**
     * Returns the visible access points. The framework scan needs no root but
     * is rate limited; "iw scan" is more complete and works from the monitor
     * mode toolchain. Both are best effort and may return an empty list.
     */
    private List<AttackTargetResolver.AccessPoint> scanAccessPoints(String dataInterface) {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wifi != null) {
                List<android.net.wifi.ScanResult> results = wifi.getScanResults();
                if (results != null && !results.isEmpty()) {
                    List<AttackTargetResolver.AccessPoint> converted = new ArrayList<>();
                    for (android.net.wifi.ScanResult result : results) {
                        String name = result.SSID == null ? "" : result.SSID.trim();
                        if (name.isEmpty() || result.BSSID == null) continue;
                        converted.add(new AttackTargetResolver.AccessPoint(
                                result.BSSID.toLowerCase(Locale.US), name,
                                frequencyToChannel(result.frequency), result.level));
                    }
                    if (!converted.isEmpty()) return converted;
                }
            }
        } catch (SecurityException denied) {
            // Location/Nearby permission was not granted; fall through to iw.
        } catch (Exception ignored) {
            // A vendor scan service can fail; iw remains as fallback.
        }
        String iface = dataInterface == null ? "" : dataInterface.trim();
        if (!iface.matches("[A-Za-z0-9_.-]{1,15}")) return new ArrayList<>();
        RootShell.Result scan = RootShell.run(this,
                "iw dev " + iface + " scan 2>/dev/null", RootShell.TIMEOUT_LONG);
        return AttackTargetResolver.parseScan(scan.output);
    }

    private static int frequencyToChannel(int frequency) {
        if (frequency == 2484) return 14;
        if (frequency >= 2412 && frequency < 2484) return (frequency - 2407) / 5;
        if (frequency >= 5955) return (frequency - 5950) / 5;
        if (frequency >= 5000 && frequency < 5955) return (frequency - 5000) / 5;
        return 0;
    }

    private void applyAutofill(AttackTargetResolver.LocalNetwork local, String neighbourOutput,
                               List<AttackTargetResolver.AccessPoint> accessPoints,
                               String preferredBssid, String preferredSsid, boolean includeLocal) {
        if (autofillButton != null) autofillButton.setEnabled(true);
        int filled = 0;
        StringBuilder notes = new StringBuilder();

        if (includeLocal && local.hasGateway()) {
            filled += fillIfEmpty(gatewayInput, local.gateway);
            if (SettingsStore.defaultInterface(this).trim().isEmpty()) {
                filled += fillIfEmpty(dataInterfaceInput, local.routeInterface);
            }
            List<String> neighbours = AttackTargetResolver.parseNeighbours(neighbourOutput, local.localAddress);
            String victim = AttackTargetResolver.chooseVictim(local, neighbours);
            if (!victim.isEmpty()) filled += fillIfEmpty(victimInput, victim);
            if (neighbours.isEmpty() && victim.isEmpty()) {
                notes.append(getString(R.string.attack_autofill_no_neighbours)).append('\n');
            }
        } else if (includeLocal) {
            notes.append(getString(R.string.attack_autofill_no_gateway)).append('\n');
        }

        AttackTargetResolver.AccessPoint target =
                AttackTargetResolver.chooseTarget(accessPoints, preferredBssid, preferredSsid);
        if (target != null) {
            filled += fillIfEmpty(bssidInput, target.bssid);
            filled += fillIfEmpty(ssidInput, target.ssid);
            if (target.channel > 0) {
                String current = channelInput.getText().toString().trim();
                // "6" is the factory default, not a user choice.
                if (current.isEmpty() || current.equals("6")) {
                    if (!current.equals(String.valueOf(target.channel))) {
                        channelInput.setText(String.valueOf(target.channel));
                        filled++;
                    }
                }
            }
        } else {
            notes.append(getString(R.string.attack_autofill_no_scan)).append('\n');
        }

        if (filled == 0 && notes.length() == 0) {
            notes.append(getString(R.string.attack_autofill_nothing)).append('\n');
        }
        autofillState.setText(getString(R.string.attack_autofill_result, filled)
                + (notes.length() == 0 ? "" : "\n" + notes.toString().trim()));
        append(getString(R.string.attack_autofill_log, filled));
    }

    /** Writes the value only when the field is empty; returns 1 when it did. */
    private int fillIfEmpty(EditText field, String value) {
        if (field == null || value == null || value.trim().isEmpty()) return 0;
        if (!field.getText().toString().trim().isEmpty()) return 0;
        field.setText(value.trim());
        return 1;
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Shows only the target and parameter fields the selected module actually
     * reads.
     *
     * <p>The workbench used to display eight inputs at once, so most of them
     * looked mandatory even though a deauthentication run only needs a BSSID
     * and a count. Values are kept in the views, so switching modules back and
     * forth never loses what was typed.</p>
     */
    private void updateFieldVisibility(AttackCatalog.Module module) {
        if (module == null) return;
        String id = module.id;
        boolean deauth = AttackCatalog.ID_DEAUTH.equals(id);
        boolean beacon = AttackCatalog.ID_BEACON.equals(id);
        boolean auth = AttackCatalog.ID_AUTH.equals(id);
        boolean handshake = AttackCatalog.ID_HANDSHAKE.equals(id);
        boolean crack = AttackCatalog.ID_CRACK.equals(id);
        boolean evilTwin = AttackCatalog.ID_EVIL_TWIN.equals(id);
        boolean arp = AttackCatalog.ID_ARP_SPOOF.equals(id);

        boolean usesTarget = !crack && !arp;
        show(bssidInput, usesTarget);
        show(ssidInput, evilTwin);
        show(channelInput, handshake || evilTwin);
        show(clientInput, deauth);
        show(countInput, deauth || beacon || auth);
        show(gatewayInput, arp);
        show(victimInput, arp);
        show(dataInterfaceInput, arp);
        show(passInput, evilTwin);
        show(wordlistInput, crack);
        show(wordlistPickButton, crack);
        show(autofillButton, usesTarget || arp);
        show(autofillState, usesTarget || arp);
    }

    private static void show(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Completes shared, non-secret fields for every module without overwriting
     * anything the user has already typed. Target MAC/SSID and victim IP are
     * never fabricated: they must come from the selected network/device.
     */
    private void applyModuleDefaults(AttackCatalog.Module module) {
        if (module == null) return;
        if (countInput != null && countInput.getText().toString().trim().isEmpty()) {
            countInput.setText("10");
        }
        // Leave the channel empty: a value is only written when the scanned
        // access point actually reports one, so an untouched "6" can never
        // silently point the module at the wrong channel.
        if (wordlistInput != null && wordlistInput.getText().toString().trim().isEmpty()) {
            String saved = SettingsStore.wordlistPath(this);
            if (saved != null && !saved.trim().isEmpty()) wordlistInput.setText(saved.trim());
        }
    }

    private void applyTarget(Intent intent) {
        if (intent == null) {
            return;
        }
        String bssid = intent.getStringExtra(EXTRA_TARGET_BSSID);
        if (bssid != null && !bssid.trim().isEmpty()) {
            bssidInput.setText(bssid.trim());
        }
        String ssid = intent.getStringExtra(EXTRA_TARGET_SSID);
        if (ssid != null && !ssid.trim().isEmpty()) {
            ssidInput.setText(ssid.trim());
        }
        int channel = intent.getIntExtra(EXTRA_TARGET_CHANNEL, 0);
        if (channel > 0) {
            channelInput.setText(String.valueOf(channel));
        }
    }

    private EditText input(String hint, String label) {
        EditText field = UiKit.plainInput(this, hint);
        field.setContentDescription(label);
        return field;
    }

    private void exportLog() {
        String content;
        synchronized (log) {
            content = log.toString();
        }
        if (content.trim().isEmpty()) {
            toast(getString(R.string.attack_no_log));
            return;
        }
        StringBuilder report = new StringBuilder();
        report.append(getString(R.string.attack_report_title));
        report.append(getString(R.string.attack_report_time))
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append("\n\n");
        report.append(content);
        File directory = new File(getFilesDir(), "reports");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new java.io.IOException(getString(R.string.attack_report_dir_failed));
            }
            File target = new File(directory, "attack-log-" + System.currentTimeMillis() + ".txt");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(target),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(report.toString());
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.attack_share_subject));
            share.putExtra(Intent.EXTRA_TEXT, report.toString());
            startActivity(Intent.createChooser(share, getString(R.string.attack_share_chooser)));
        } catch (Exception failure) {
            toast(getString(R.string.attack_export_failed, failure.getMessage()));
        }
    }

    private void append(String line) {
        if (line == null) {
            return;
        }
        String snapshot;
        synchronized (log) {
            log.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                    .append("  ").append(line).append('\n');
            if (log.length() > 40000) {
                log.delete(0, log.length() - 40000);
            }
            snapshot = log.length() > 12000
                    ? log.substring(log.length() - 12000) : log.toString();
        }
        final String text = snapshot;
        runOnUiThread(() -> {
            if (logView != null) {
                logView.setText(text);
            }
            updateLogCounter();
            followLogTail();
        });
    }

    /**
     * Keeps the newest output visible, but only when the user is already
     * at the bottom: someone reading earlier lines is not yanked away.
     */
    private void followLogTail() {
        if (pageScroll == null || logView == null) return;
        View content = pageScroll.getChildAt(0);
        if (content == null) return;
        int viewportBottom = pageScroll.getScrollY() + pageScroll.getHeight();
        int distanceToBottom = content.getBottom() - viewportBottom;
        if (distanceToBottom <= dp(220)) {
            pageScroll.post(() -> pageScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 更新攻击状态指示器 */
    private void updateAttackStatus(String message, int color) {
        statusMessage = message;
        statusColor = color;
        renderAttackStatus();
    }

    /** 把当前状态和已运行时长写到状态行上。 */
    private void renderAttackStatus() {
        if (attackStatusIndicator == null) return;
        if (statusMessage == null || statusMessage.trim().isEmpty()) {
            attackStatusIndicator.setVisibility(View.GONE);
            return;
        }
        attackStatusIndicator.setVisibility(View.VISIBLE);
        attackStatusIndicator.setText(attackElapsedSince > 0L
                ? getString(R.string.attack_status_elapsed, statusMessage, elapsedLabel())
                : statusMessage);
        attackStatusIndicator.setTextColor(statusColor);
    }

    private String elapsedLabel() {
        long seconds = Math.max(0L, (System.currentTimeMillis() - attackElapsedSince) / 1000L);
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    /** Starts the "running for mm:ss" ticker shown next to the state. */
    private void startElapsed() {
        stopElapsed();
        attackElapsedSince = System.currentTimeMillis();
        renderAttackStatus();
        elapsedTicker = new android.os.Handler(getMainLooper());
        elapsedTicker.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (attackElapsedSince <= 0L) return;
                renderAttackStatus();
                elapsedTicker.postDelayed(this, 1000L);
            }
        }, 1000L);
    }

    /** Stops the ticker and hides the elapsed time. */
    private void stopElapsed() {
        attackElapsedSince = 0L;
        if (elapsedTicker != null) {
            elapsedTicker.removeCallbacksAndMessages(null);
            elapsedTicker = null;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = round(PANEL, dp(20));
        background.setStroke(dp(1), UiKit.color(this, Attrs.COLOR_OUTLINE_VARIANT));
        layout.setBackground(background);
        layout.setElevation(0f);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    /** The primary, filled action button. */
    private MaterialButton primaryButton(String label) {
        return UiKit.primary(this, label);
    }

    /** A tonal button for secondary actions. */
    private MaterialButton tonalButton(String label) {
        return UiKit.tonal(this, label);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams top(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(value);
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A running module must keep the CPU awake even if the global
        // preference is off; applyKeepScreenOn() would have cleared it.
        if (managed != null) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onDestroy() {
        stopElapsed();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RootProcess process = managed;
        managed = null;
        if (process != null) {
            // stop() blocks for up to several seconds (TERM, grace period,
            // KILL); it must not run on the main thread or leaving the screen
            // can ANR while the tool ignores SIGTERM.
            new Thread(() -> process.stop(), "wifitester-attack-destroy").start();
        }
        super.onDestroy();
    }
}
