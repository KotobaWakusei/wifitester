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

    private final StringBuilder log = new StringBuilder();
    private final List<AttackCatalog.Module> modules = AttackCatalog.modules();
    private RootProcess managed;
    private boolean rootGranted;
    private boolean monitorPresent;
    private ToolPackageManager.DeviceMonitorCapability monitorCapability = ToolPackageManager.DeviceMonitorCapability.UNKNOWN;
    private final java.util.Map<String, String> toolPaths = new java.util.LinkedHashMap<>();

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
    }

    // ---- layout -----------------------------------------------------------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
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
        stopButton.setEnabled(false);
        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> confirmStart());
        stopButton.setOnClickListener(v -> stopModule());

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
        statusState.setText(getString(R.string.attack_detect_root));
        toolState.setText(getString(R.string.attack_detect_tools));
        new Thread(() -> {
            boolean granted = RootShell.hasRoot(this);
            RootShell.Result probe = granted
                    ? RootShell.run(this, ToolCatalog.probeCommand(), RootShell.TIMEOUT_PROBE)
                    : new RootShell.Result(-1, "");
            String detectedGateway = "";
            String detectedRouteInterface = "";
            if (granted) {
                RootShell.Result route = RootShell.run(this, "ip -4 route show default 2>/dev/null", RootShell.TIMEOUT_SHORT);
                java.util.regex.Matcher routeMatcher = java.util.regex.Pattern
                        .compile("(?m)^default\\s+via\\s+((?:[0-9]{1,3}\\.){3}[0-9]{1,3}).*?\\bdev\\s+([A-Za-z0-9_.-]+)")
                        .matcher(route.output == null ? "" : route.output);
                if (routeMatcher.find()) {
                    detectedGateway = routeMatcher.group(1);
                    detectedRouteInterface = routeMatcher.group(2);
                }
            }
            final String routeGateway = detectedGateway;
            final String routeInterface = detectedRouteInterface;
            toolPaths.clear();
            for (ToolCatalog.Tool tool : ToolCatalog.parse(probe.output)) {
                toolPaths.put(tool.name, tool.path);
            }
            // The monitor interface is a device state, not a tool: it has to be
            // queried separately with "iw dev".
            String detectedMonitorInterface = "";
            if (granted) {
                RootShell.Result devices = RootShell.run(this, "iw dev 2>&1", RootShell.TIMEOUT_SHORT);
                detectedMonitorInterface = findMonitorInterface(devices.output);
            }
            final String currentMonitorInterface = detectedMonitorInterface;
            final boolean hasMonitor = !detectedMonitorInterface.isEmpty();
            
            // 检查设备监听模式支持情况
            ToolPackageManager toolManager = new ToolPackageManager(this);
            ToolPackageManager.DeviceMonitorCapability monitorCapability = toolManager.getDeviceMonitorCapability();
            
            final boolean hasRoot = granted;
            final boolean monitor = hasMonitor;
            runOnUiThread(() -> {
                rootGranted = hasRoot;
                monitorPresent = monitor;
                monitorInterface = currentMonitorInterface.isEmpty() ? MONITOR_INTERFACE : currentMonitorInterface;
                monitorCapability = toolManager.getDeviceMonitorCapability();
                statusState.setText(hasRoot ? getString(R.string.attack_root_granted) : getString(R.string.attack_root_missing));
                statusState.setTextColor(hasRoot ? 0xff79e0b0 : DANGER_TEXT);
                toolState.setText(describeTools());
                // Fill only fields that the user has not edited; never invent a victim IP.
                if (gatewayInput != null && gatewayInput.getText().toString().trim().isEmpty()
                        && !routeGateway.isEmpty()) gatewayInput.setText(routeGateway);
                if (dataInterfaceInput != null
                        && SettingsStore.defaultInterface(this).trim().isEmpty()
                        && !routeInterface.isEmpty()) dataInterfaceInput.setText(routeInterface);

                // 显示设备监听模式支持情况
                append("设备信息: " + toolManager.getDeviceMonitorDescription());
                
                boolean authorized = SettingsStore.attackAuthorized(this);
                startButton.setEnabled(hasRoot && authorized);
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
                if (monitorCapability == ToolPackageManager.DeviceMonitorCapability.MONITOR_ONLY) {
                    append("注意: 当前设备仅支持监听模式，不支持帧注入功能");
                } else if (monitorCapability == ToolPackageManager.DeviceMonitorCapability.NO_MONITOR) {
                    append("警告: 当前设备不支持Wi-Fi监听模式，建议使用OTG外接网卡");
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

    private String describeTools() {
        StringBuilder text = new StringBuilder(getString(R.string.attack_tools_header));
        ToolPackageManager toolManager = new ToolPackageManager(this);
        String[] required = {"aireplay-ng", "airodump-ng", "aircrack-ng", "hostapd", "arpspoof", "iw", "ip"};
        for (String name : required) {
            String path = toolPaths.get(name);
            boolean present = path != null && path.startsWith("/");
            boolean installed = toolManager.isToolAvailable(name);
            
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

    // ---- execution --------------------------------------------------------

    private void confirmStart() {
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
            toast("当前驱动只有监听接收能力，不支持无线帧注入；已阻止此发送型模块。可使用被动抓包或支持注入的外接网卡。");
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
            
            if (!toolManager.isToolAvailable(toolName)) {
                // 工具未安装，提示用户下载
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.attack_needs_tool_title))
                        .setMessage(getString(R.string.attack_needs_tool_download, toolName))
                        .setPositiveButton(getString(R.string.action_download), (dialog, which) -> {
                            // 尝试下载对应的工具包
                            for (ToolPackageManager.ToolPackage pkg : toolManager.getAllPackages()) {
                                if (pkg.tools.contains(toolName)) {
                                    ToolInstallerService.start(this, pkg);
                                    toast(getString(R.string.tool_download_started, pkg.name));
                                    break;
                                }
                            }
                        })
                        .setNeutralButton(getString(R.string.action_open_toolkit), (dialog, which) ->
                                startActivity(new Intent(this, AircrackNgActivity.class)))
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show();
                return;
            } else {
                // 工具已安装但未找到，可能是路径问题
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.attack_tool_missing_title))
                        .setMessage(getString(R.string.attack_tool_missing, toolName))
                        .setPositiveButton(getString(R.string.action_recheck), (dialog, which) ->
                                detectEnvironment())
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show();
                return;
            }
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
                        if (file.isFile() && file.lastModified() > newest) {
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
                String content = AttackCommandBuilder.buildHostapdConfig(MONITOR_INTERFACE,
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
        append("===== " + getString(module.titleRes) + " =====");
        append(getString(R.string.attack_command_running, command));
        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        managed = RootProcess.start(this, command, new RootProcess.OutputListener() {
            @Override
            public void onLine(String line) {
                append(line);
            }

            @Override
            public void onFinished(int exitCode) {
                append(getString(R.string.attack_module_finished, exitCode));
                runOnUiThread(() -> {
                    startButton.setEnabled(rootGranted
                            && SettingsStore.attackAuthorized(AttackActivity.this));
                    stopButton.setEnabled(false);
                    managed = null;
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
        new Thread(() -> {
            process.stop();
            runOnUiThread(() -> {
                append(getString(R.string.attack_module_stopped));
                managed = null;
                stopButton.setEnabled(false);
                startButton.setEnabled(rootGranted
                        && SettingsStore.attackAuthorized(AttackActivity.this));
            });
        }, "wifitester-attack-stop").start();
    }

    // ---- helpers ----------------------------------------------------------

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
        if (channelInput != null && channelInput.getText().toString().trim().isEmpty()) {
            channelInput.setText("6");
        }
        if (dataInterfaceInput != null && dataInterfaceInput.getText().toString().trim().isEmpty()) {
            String saved = SettingsStore.defaultInterface(this);
            dataInterfaceInput.setText(saved == null || saved.trim().isEmpty() ? "wlan0" : saved.trim());
        }
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
        });
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
    protected void onDestroy() {
        RootProcess process = managed;
        if (process != null) {
            process.stop();
            managed = null;
        }
        super.onDestroy();
    }
}
