package me.paranoid.wifikill;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generic passive monitor workflow with capability-based interface and channel discovery. */
public final class MonitorModeActivity extends BaseActivity {
    /** Preselects the channel derived from a network opened from the Wi-Fi list. */
    public static final String EXTRA_PRESET_CHANNEL = "preset_channel";
    /** Preselects the BSSID derived from a network opened from the Wi-Fi list. */
    public static final String EXTRA_PRESET_BSSID = "preset_bssid";
    private int BG, PANEL, INK, ACCENT, MUTED;
    private static final int CREATE_REPORT=7021, EXPORT_CAPTURE=7022;
    private TextView rootState, capabilityState, log;
    private LinearLayout logCard;
    private Spinner ifaceSpinner, channelSpinner;
    private MaterialButton startButton, stopButton, exportButton, captureExportButton, refreshButton, handshakeButton;
    private MaterialButton channelHopToggle;
    /**
     * The probe side is vendor independent; the start/restore commands use the
     * con_mode-aware overloads of the iw backend directly.
     */
    private final IwMonitorModeBackend monitorBackend=new IwMonitorModeBackend(this);
    private final ArrayList<String> interfaces=new ArrayList<>();
    private final ArrayList<WifiChannelParser.Channel> channels=new ArrayList<>();
    private final StringBuilder diagnostics=new StringBuilder();
    private volatile File latestCaptureFile;
    private File pendingCaptureExport;
    private volatile boolean running=false, createdMonitor=false, rootGranted=false;
    private volatile boolean probing=false;
    private volatile boolean channelHopping=false;
    private String activeInterface="", selectedMonitor="wfkmon0";
    private String previousInterfaceState="unknown";
    /** QCACLD global con_mode switch discovered by the probe, or null. */
    private volatile String conModePath;
    /**
     * Session details remembered across activity recreation. The capture itself
     * lives in {@link MonitorCaptureService}, so leaving this screen no longer
     * ends the capture.
     */
    private String restoreInterface="", restoreMonitor="", restoreState="";
    private boolean restoreCreated=false;

    private final android.content.BroadcastReceiver captureStateReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (intent == null || !MonitorCaptureService.ACTION_STATE.equals(intent.getAction())) return;
            boolean active = intent.getBooleanExtra(MonitorCaptureService.EXTRA_RUNNING, false);
            long pid = intent.getLongExtra(MonitorCaptureService.EXTRA_PID, -1L);
            String line = intent.getStringExtra(MonitorCaptureService.EXTRA_LINE);
            boolean hasExit = intent.hasExtra(MonitorCaptureService.EXTRA_EXIT);
            int exit = intent.getIntExtra(MonitorCaptureService.EXTRA_EXIT, 0);
            String target = intent.getStringExtra(MonitorCaptureService.EXTRA_TARGET);
            if (target != null && !target.isEmpty()) latestCaptureFile = new File(target);
            boolean hoppingNow = intent.getBooleanExtra(MonitorCaptureService.EXTRA_HOPPING, false);
            if (hoppingNow != channelHopping) {
                channelHopping = hoppingNow;
                updateHopToggle();
            }
            if (!active && running) {
                // The capture ended on its own (frame limit reached, or the
                // notification was used). Restore the interface exactly once.
                onCaptureEnded(hasExit ? exit : -1);
                return;
            }
            if (pid > 1L) append(getString(R.string.monitor_pid, String.valueOf(pid)));
            if (line != null && !line.isEmpty()) append(line);
            if (hasExit && !active) append(getString(R.string.monitor_capture_finished, exit,
                    latestCaptureFile == null ? 0L : latestCaptureFile.length()));
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        BG=UiKit.color(this,Attrs.COLOR_SURFACE);
        PANEL=UiKit.color(this,Attrs.COLOR_SURFACE_CONTAINER_LOWEST);
        INK=UiKit.color(this,Attrs.COLOR_ON_SURFACE);
        ACCENT=UiKit.color(this,Attrs.COLOR_PRIMARY);
        MUTED=UiKit.color(this,Attrs.COLOR_ON_SURFACE_VARIANT);
        buildUi();probe();
        requestNotificationPermissionIfNeeded();
    }

    /**
     * Android 13+ hides the capture notification until the user allows it.
     * The notification is how the capture is stopped after leaving this
     * screen, so the permission is requested on first visit.
     */
    private void requestNotificationPermissionIfNeeded(){
        if(android.os.Build.VERSION.SDK_INT<33)return;
        if(checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                ==android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFICATIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode!=REQ_NOTIFICATIONS)return;
        boolean granted=results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;
        append(granted?getString(R.string.monitor_notification_permission_granted)
                :getString(R.string.monitor_notification_permission_denied));
    }
    private static final int REQ_NOTIFICATIONS=7101;

    @Override protected void onStart(){
        super.onStart();
        android.content.IntentFilter filter=new android.content.IntentFilter(MonitorCaptureService.ACTION_STATE);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        if(android.os.Build.VERSION.SDK_INT>=33){
            registerReceiver(captureStateReceiver,filter,android.content.Context.RECEIVER_NOT_EXPORTED);
        }else{
            registerReceiver(captureStateReceiver,filter);
        }
    }

    @Override protected void onStop(){
        try{unregisterReceiver(captureStateReceiver);}catch(RuntimeException ignored){}
        super.onStop();
    }

    @Override protected void onResume(){
        super.onResume();
        syncWithService();
        scheduleCaptureWatchdog();
    }

    @Override protected void onPause(){
        cancelCaptureWatchdog();
        super.onPause();
    }

    /**
     * While this screen is open nothing else notices that the capture ended
     * (the frame budget was reached, or tcpdump failed), so the session is
     * polled and the restore runs as soon as it is gone.
     */
    private final Runnable captureWatchdog = new Runnable() {
        @Override public void run() {
            if(!running){cancelCaptureWatchdog();return;}
            if(!MonitorCaptureService.isRunning(MonitorModeActivity.this)){
                if(latestCaptureFile!=null&&latestCaptureFile.isFile()){
                    append(getString(R.string.monitor_capture_finished,0,latestCaptureFile.length()));
                }
                onCaptureEnded(-1);
                return;
            }
            captureWatchdogHandler.postDelayed(this,CAPTURE_WATCHDOG_MS);
        }
    };
    private final android.os.Handler captureWatchdogHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long CAPTURE_WATCHDOG_MS=2000L;

    private void scheduleCaptureWatchdog(){
        captureWatchdogHandler.removeCallbacks(captureWatchdog);
        captureWatchdogHandler.postDelayed(captureWatchdog,CAPTURE_WATCHDOG_MS);
    }

    private void cancelCaptureWatchdog(){
        captureWatchdogHandler.removeCallbacks(captureWatchdog);
    }

    // ---- service binding ---------------------------------------------------

    private MonitorCaptureService.Session session;
    private volatile boolean bindingInFlight;
    private volatile boolean bound;

    private final android.content.ServiceConnection captureConnection =
            new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
                    session = (MonitorCaptureService.Session) service;
                    bound = true;
                    syncWithService();
                }

                @Override public void onServiceDisconnected(android.content.ComponentName name) {
                    session = null;
                    bound = false;
                }
            };

    /** Binds the capture session (starting it if necessary); true when ready. */
    private boolean ensureBound() {
        if (session != null) return true;
        if (bindingInFlight) return false;
        bindingInFlight = true;
        try {
            return bindService(MonitorCaptureService.intent(this), captureConnection,
                    android.content.Context.BIND_AUTO_CREATE);
        } catch (RuntimeException refused) {
            append(getString(R.string.monitor_capture_service_failed));
            return false;
        } finally {
            bindingInFlight = false;
        }
    }

    private void releaseBinding() {
        if (!bound && session == null) return;
        try {
            unbindService(captureConnection);
        } catch (RuntimeException ignored) {
            // Already unbound.
        }
        bound = false;
        session = null;
    }

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(22),dp(20),dp(24));scroll.addView(root);
        root.addView(text(getString(R.string.monitor_eyebrow),11,ACCENT,true));
        root.addView(text(getString(R.string.monitor_workbench_title),30,INK,true),top(7));
        root.addView(text(getString(R.string.monitor_subtitle),14,MUTED,false),top(5));

        LinearLayout status=card();
        rootState=text(getString(R.string.monitor_root_checking),16,INK,true);status.addView(rootState);
        capabilityState=text(getString(R.string.monitor_capability_waiting),13,MUTED,false);status.addView(capabilityState,top(7));
        refreshButton=tonalButton(getString(R.string.monitor_recheck));status.addView(refreshButton,top(12));refreshButton.setOnClickListener(v->probe());
        root.addView(status,top(17));

        LinearLayout controls=card();
        controls.addView(text(getString(R.string.monitor_interfaces),14,INK,true));
        ifaceSpinner=new Spinner(this);controls.addView(ifaceSpinner,top(4));
        controls.addView(text(getString(R.string.monitor_channels),14,INK,true),top(12));
        channelSpinner=new Spinner(this);controls.addView(channelSpinner,top(4));
        // Channel hopping is a capture-session feature; the button is only
        // usable while a capture runs and more than one channel is known.
        LinearLayout hopControls=new LinearLayout(this);
        hopControls.setOrientation(LinearLayout.HORIZONTAL);
        hopControls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView hopLabel=text(getString(R.string.monitor_hop_label),12,MUTED,false);
        hopLabel.setPadding(0,0,dp(10),0);
        hopControls.addView(hopLabel);
        channelHopToggle=tonalButton(getString(R.string.monitor_hop_off));
        LinearLayout.LayoutParams hopParams=new LinearLayout.LayoutParams(-2,dp(42));
        hopControls.addView(channelHopToggle,hopParams);
        channelHopToggle.setEnabled(false);
        channelHopToggle.setOnClickListener(v->toggleChannelHopping());
        controls.addView(hopControls,top(10));
        root.addView(controls,top(12));

        TextView warning=text(getString(R.string.monitor_warning),12,UiKit.color(this,R.attr.colorOnWarningContainer),false);
        warning.setPadding(dp(2),dp(12),dp(2),dp(4));root.addView(warning);

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton=primaryButton(getString(R.string.monitor_start));stopButton=tonalButton(getString(R.string.monitor_stop_restore));
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(48),1);half.rightMargin=dp(5);actions.addView(startButton,half);
        LinearLayout.LayoutParams half2=new LinearLayout.LayoutParams(0,dp(48),1);half2.leftMargin=dp(5);actions.addView(stopButton,half2);
        root.addView(actions,top(8));stopButton.setEnabled(false);startButton.setEnabled(false);
        startButton.setOnClickListener(v->confirmStart());stopButton.setOnClickListener(v->stopMonitor());
        exportButton=tonalButton(getString(R.string.monitor_export_report));root.addView(exportButton,top(10));exportButton.setOnClickListener(v->exportReport());
        captureExportButton=tonalButton(getString(R.string.monitor_export_capture));captureExportButton.setEnabled(false);root.addView(captureExportButton,top(8));captureExportButton.setOnClickListener(v->exportCapture());
        // One-tap pure analysis of the current (or last) capture: it reports
        // which handshakes were actually captured, so the user knows whether
        // the file is worth sending to aircrack-ng — no root call involved.
        handshakeButton=tonalButton(getString(R.string.monitor_check_handshake));handshakeButton.setEnabled(false);root.addView(handshakeButton,top(8));handshakeButton.setOnClickListener(v->checkHandshake());

        log=UiKit.logView(this);log.setText(getString(R.string.monitor_log_waiting));
        logCard=UiKit.logCard(this,log,getString(R.string.monitor_log_title));
        root.addView(logCard,top(18));
        setContentView(scroll);
    }

    private void probe(){
        if(running){toast(getString(R.string.monitor_stop_first));return;}
        if(probing)return; // a detection pass is already in flight; don't race it
        probing=true;
        startButton.setEnabled(false);rootState.setText(getString(R.string.monitor_root_verifying));capabilityState.setText(getString(R.string.monitor_capability_scanning));
        new Thread(()->{
            BundledToolchain.InstallResult embedded=new BundledToolchain(this).install();
            Result r=runRoot(monitorBackend.probeCommand(),15000);
            String out=r.output==null?"":r.output;
            boolean rootOk=r.code==0 && Pattern.compile("\\buid=0(?:\\(|\\b)").matcher(out).find();
            ArrayList<String> found=discoverInterfaces(out);
            ArrayList<WifiChannelParser.Channel> foundChannels=WifiChannelParser.parse(out);
            boolean iw=toolPath(out,"__IW_PATH__"), tcpdump=toolPath(out,"__TCPDUMP_PATH__"), ip=toolPath(out,"__IP_PATH__");
            final String detectedConMode=out.contains("CON_MODE:")?IwMonitorModeBackend.parseConModePath(out):null;
            runOnUiThread(()->{
                probing=false;
                conModePath=detectedConMode;
                rootGranted=rootOk;interfaces.clear();interfaces.addAll(found);channels.clear();channels.addAll(foundChannels);
                rootState.setText(rootOk?getString(R.string.monitor_root_granted):getString(R.string.monitor_root_missing));
                rootState.setTextColor(rootOk?0xff79e0b0:0xffffa1a1);
                ArrayAdapter<String> ia=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,interfaces.isEmpty()?new ArrayList<String>() : interfaces);
                ifaceSpinner.setAdapter(ia);
                if(!interfaces.isEmpty()) ifaceSpinner.setSelection(0);
                ArrayList<String> labels=new ArrayList<>();for(WifiChannelParser.Channel c:channels)labels.add(c.label);
                if(labels.isEmpty()) labels.add(getString(R.string.monitor_no_channels));
                channelSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
                selectPresetChannel();
                boolean ok=rootOk&&iw&&tcpdump&&ip&&!interfaces.isEmpty()&&!channels.isEmpty();
                capabilityState.setText(getString(R.string.monitor_capability_summary,
                        getString(iw?R.string.monitor_tool_ok:R.string.monitor_tool_missing,"iw"),
                        getString(ip?R.string.monitor_tool_ok:R.string.monitor_tool_missing,"ip"),
                        getString(tcpdump?R.string.monitor_tool_ok:R.string.monitor_tool_missing,"tcpdump"),
                        interfaces.size(),channels.size())+
                        getString(R.string.monitor_channels_note));
                startButton.setEnabled(ok);
                if(ifaceSpinner!=null) ifaceSpinner.setEnabled(!running);
                if(channelSpinner!=null) channelSpinner.setEnabled(!running);
                if(refreshButton!=null) refreshButton.setEnabled(!running);
                updateHopToggle();
                append(detectedConMode!=null
                        ? getString(R.string.monitor_con_mode_detected,detectedConMode)
                        : getString(R.string.monitor_con_mode_absent));
                append(getString(R.string.monitor_device_probe,new BundledToolchain(this).getAbi(),embedded.message,embedded.installed,r.code,
                        String.valueOf(rootOk),String.valueOf(iw),String.valueOf(ip),String.valueOf(tcpdump),
                        interfaces,labels,trim(out,6500)));
            });
        }).start();
    }

    /**
     * Preselects the channel that was chosen in the network detail screen.
     * The channel list comes from the driver, so a request for a channel the
     * driver does not publish is simply ignored.
     */
    private void selectPresetChannel(){
        int preset=getIntent()==null?0:getIntent().getIntExtra(EXTRA_PRESET_CHANNEL,0);
        if(preset<=0||channelSpinner==null)return;
        for(int i=0;i<channels.size();i++){
            if(channels.get(i).number==preset){
                channelSpinner.setSelection(i);
                append(getString(R.string.monitor_preset_channel,preset));
                return;
            }
        }
        append(getString(R.string.monitor_channel_not_published,preset));
    }

    private ArrayList<String> discoverInterfaces(String out){
        ArrayList<String> found=new ArrayList<>();
        int marker=out.indexOf("__IW_DEV__");
        int end=out.indexOf("__LINKS__",Math.max(marker,0));
        String section=marker>=0?out.substring(marker+10,end>marker?end:out.length()):out;
        Matcher m=Pattern.compile("(?m)^\\s*Interface\\s+([A-Za-z0-9_.-]+)\\s*$").matcher(section);
        while(m.find()){
            String n=m.group(1);
            String lower=n.toLowerCase(Locale.US);
            // Wi-Fi Aware/NAN and P2P virtual interfaces are not preferred
            // as monitor-mode parents; their names can begin with "wifi".
            if(lower.contains("aware")||lower.startsWith("p2p")) continue;
            if(!n.startsWith("wfkmon")&&!n.equals("lo")&&!found.contains(n))found.add(n);
        }
        if(found.isEmpty()){
            Matcher ip=Pattern.compile("(?m)^\\d+:\\s*([A-Za-z0-9_.-]+):").matcher(out);
            while(ip.find()){
                String n=ip.group(1);
                String lower=n.toLowerCase(Locale.US);
                if(!lower.contains("aware")&&!lower.startsWith("p2p")&&(n.startsWith("wlan")||n.startsWith("wifi")||n.startsWith("wl")||n.startsWith("ath"))&&!n.startsWith("wfkmon")&&!found.contains(n))found.add(n);
            }
        }
        java.util.Collections.sort(found,(a,b)->Integer.compare(interfacePriority(a),interfacePriority(b)));
        return found;
    }

    private int interfacePriority(String name){
        String n=name.toLowerCase(Locale.US);
        if(n.startsWith("wlan")||n.startsWith("wl"))return 0;
        if(n.startsWith("ath"))return 1;
        if(n.startsWith("wifi"))return 2;
        if(n.startsWith("p2p")||n.contains("aware"))return 4;
        if(n.contains("ap"))return 3;
        return 3;
    }

    private boolean toolPath(String out,String marker){
        int p=out.indexOf(marker);if(p<0)return false;
        String tail=out.substring(p+marker.length());int nl=tail.indexOf('\n');
        if(nl<0)return false;String path=tail.substring(nl+1).trim().split("\n",2)[0].trim();
        return path.startsWith("/") && !path.contains("not found");
    }

    private void confirmStart(){
        if(running){toast(getString(R.string.monitor_stop_first));return;}
        if(!rootGranted||ifaceSpinner.getSelectedItem()==null||channelSpinner.getSelectedItemPosition()<0||channels.isEmpty())return;
        new MaterialAlertDialogBuilder(this).setTitle(getString(R.string.monitor_confirm_title))
                .setMessage(getString(R.string.monitor_confirm_use,ifaceSpinner.getSelectedItem(),channels.get(channelSpinner.getSelectedItemPosition()).label)
                        + getString(R.string.monitor_confirm_hint))
                .setNegativeButton(getString(R.string.action_cancel),null)
                .setPositiveButton(getString(R.string.monitor_confirm_start),(d,w)->startMonitor()).show();
    }

    private void startMonitor(){
        if(running||ifaceSpinner.getSelectedItem()==null)return;
        activeInterface=ifaceSpinner.getSelectedItem().toString();
        if(!activeInterface.matches("[A-Za-z0-9_.-]+")){append(getString(R.string.monitor_unsafe_interface));return;}
        // A previous Qualcomm con_mode run captures on the parent interface. Always
        // reset the requested virtual-interface name before attempting a fresh start.
        selectedMonitor = "wfkmon0";
        WifiChannelParser.Channel channel=channels.get(channelSpinner.getSelectedItemPosition());
        startButton.setEnabled(false);refreshButton.setEnabled(false);
        startButton.setText(getString(R.string.monitor_start_starting));
        append(getString(R.string.monitor_starting,activeInterface,channel.label));
        new Thread(()->{
            Result pre=runRoot("ip -o link show dev '"+activeInterface+"' 2>&1",4000);
            String old=pre.output==null?"":pre.output;
            if(pre.code!=0){
                runOnUiThread(()->{
                    append(getString(R.string.monitor_state_unreadable,trim(old,1200)));
                    capabilityState.setText(getString(R.string.monitor_state_unreadable_short));
                    applyRunningState();
                });
                return;
            }
            String state=InterfaceStateParser.parseAdministrativeState(old);
            if(state==null){
                runOnUiThread(()->{
                    append(getString(R.string.monitor_state_unparsable,trim(old,1200)));
                    capabilityState.setText(getString(R.string.monitor_state_unknown));
                    applyRunningState();
                });
                return;
            }
            String cmd=monitorBackend.buildStartCommand(activeInterface,selectedMonitor,channel,conModePath);
            Result r=runRoot(cmd,20000);
            final String oldState=state;
            runOnUiThread(()->{
                previousInterfaceState=oldState;
                append(getString(R.string.monitor_exit_code,r.code,trim(r.output,2000)));
                if(r.code!=0||r.output==null||!r.output.contains("MONITOR_READY")){
                    // Only delete an interface this invocation actually created.
                    // An existing wfkmon0 must never be removed on the preflight-error path.
                    String cleanup=monitorBackend.buildFailedStartCleanupCommand(
                            activeInterface,selectedMonitor,oldState,
                            r.output!=null&&r.output.contains("MONITOR_CREATED"),conModePath);
                    // Complete cleanup before allowing another start attempt; otherwise a
                    // fast retry can race the old cleanup and trigger another EBUSY.
                    String failure=getString(R.string.monitor_start_failed);
                    if(r.output!=null && r.output.contains("MONITOR_CREATE_FAILED")) failure=getString(R.string.monitor_create_failed);
                    else if(r.output!=null && r.output.contains("CON_MODE_SET_FAILED")) failure=getString(R.string.monitor_con_mode_failed);
                    else if(r.output!=null && r.output.contains("CON_MODE_NOT_APPLIED")) failure=getString(R.string.monitor_con_mode_not_applied);
                    else if(r.output!=null && (r.output.contains("CHANNEL_SET_FAILED") || r.output.contains("CHANNEL_TUNING_FAILED") || r.output.contains("CHANNEL_FREQ_FAILED"))) failure=getString(R.string.monitor_channel_failed);
                    else if(r.output!=null && r.output.contains("MONITOR_UP_FAILED")) failure=getString(R.string.monitor_up_failed);
                    // Show the driver's own answer next to the summary; the full
                    // output stays in the log view for the report.
                    String detail=driverHint(r.output);
                    final String failureMessage=detail.isEmpty()?failure:failure+"\n"+detail;
                    final String cleanupCommand=cleanup;
                    startButton.setEnabled(false);refreshButton.setEnabled(false);
                    new Thread(()->{
                        Result cleanupResult=runRoot(cleanupCommand,6000);
                        runOnUiThread(()->{
                            append(getString(R.string.monitor_cleanup,cleanupResult.code,trim(cleanupResult.output,1200)));
                            capabilityState.setText(failureMessage);
                            applyRunningState();
                        });
                    }).start();
                    return;
                }
                // QCACLD con_mode fallback uses the existing parent interface because
                // this driver rejects a second monitor vdev. Capture on that interface.
                boolean parentMode=r.output.contains("MONITOR_PARENT_MODE");
                if (parentMode) {
                    selectedMonitor = activeInterface;
                    createdMonitor = false;
                    append(getString(R.string.monitor_con_mode_active,activeInterface));
                } else {
                    selectedMonitor = "wfkmon0";
                    createdMonitor = true;
                }
                running=true;
                applyRunningState();
                capabilityState.setText(getString(R.string.monitor_started,channel.label));
                rememberSession(oldState);
                // Bind before starting the service: the capture state is then
                // pushed to the UI without relying on broadcasts, and a failed
                // bind aborts the session instead of leaving an orphan tcpdump.
                ensureBound();
                beginCapture(channel.label);
                scheduleCaptureWatchdog();
                // Channel hopping is now controlled by the service, not a root loop.
                if (channelHopping && session != null) {
                    session.setChannelHopping(true);
                }
            });
        }).start();
    }

    /** Stores what a later restore needs, including after an activity restart. */
    private void rememberSession(String originalState){
        restoreInterface=activeInterface;
        restoreMonitor=selectedMonitor;
        restoreState=originalState;
        restoreCreated=createdMonitor;
        SettingsStore.setCaptureSession(this,restoreInterface,restoreMonitor,restoreState,
                restoreCreated,latestCaptureFile==null?"":latestCaptureFile.getAbsolutePath());
    }

    /**
     * Hands the capture to {@link MonitorCaptureService}: a foreground service
     * keeps tcpdump running after this screen is left, and its notification
     * carries a stop button.
     */
    private void beginCapture(String channelLabel){
        new Thread(()->{
            try{
                File directory=new File(getFilesDir(),"captures");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException(getString(R.string.monitor_capture_dir_failed));
                File target=File.createTempFile("wifitester-",".pcap",directory);
                latestCaptureFile=target;
                // The session record was written with the previous capture file
                // (rememberSession runs before this thread). Update the path so
                // a later restore still points at the current PCAP.
                SettingsStore.setCaptureSession(this,restoreInterface,restoreMonitor,
                        restoreState,restoreCreated,target.getAbsolutePath());
                int packetLimit=SettingsStore.capturePacketLimit(this);
                int snaplen=SettingsStore.captureSnaplen(this);
                String monitor=selectedMonitor;
                String hopChannelsSpec = buildHopChannelsSpec();
                MonitorCaptureService.Session boundSession=session;
                boolean started=boundSession!=null
                        ? boundSession.start(monitor,channelLabel,packetLimit,snaplen,target.getAbsolutePath(),
                                hopChannelsSpec, 2000, channelHopping)
                        : MonitorCaptureService.start(this,monitor,channelLabel,packetLimit,snaplen,target.getAbsolutePath(),
                                hopChannelsSpec, 2000, channelHopping);
                runOnUiThread(()->{
                    if(!started){
                        append(getString(R.string.monitor_capture_service_failed));
                        stopMonitor();
                        return;
                    }
                    append(getString(R.string.monitor_capture_started,packetLimit,snaplen,target.getName()));
                    append(getString(R.string.monitor_notification_running));
                    rememberSession(previousInterfaceState);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    append(getString(R.string.monitor_capture_write_failed,e.getClass().getSimpleName()+": "+e.getMessage()));
                    stopMonitor();
                });
            }
        }).start();
    }

    /**
     * Re-attaches the UI to a capture that is still running, or finishes the
     * restore of one that ended while this screen was gone.
     */
    private void syncWithService(){
        if(session==null&&MonitorCaptureService.isRunning(this)) ensureBound();
        MonitorCaptureService.State state=session!=null?session.state():MonitorCaptureService.state(this);
        if(state.running){
            if(!state.targetPath.isEmpty()) latestCaptureFile=new File(state.targetPath);
            if(session!=null&&!state.interfaceName.isEmpty()) selectedMonitor=state.interfaceName;
            channelHopping=state.hopping;
            if(!running){
                running=true;
                selectedMonitor=state.interfaceName;
                createdMonitor=false;
                applyRunningState();
                capabilityState.setText(getString(R.string.monitor_running_in_background,state.interfaceName));
                append(getString(R.string.monitor_notification_running));
            }
            updateHopToggle();
            return;
        }
        // No capture: if a session is recorded as active, it ended elsewhere.
        // The record must survive until restoreInterface() consumes it: clearing
        // it here made the guard in restoreInterface() return immediately, so
        // the interface was never brought back after a notification stop or an
        // activity restart.
        if(SettingsStore.captureSessionActive(this)){
            // The capture file path was recorded when the session started;
            // without reading it back the export button stays disabled even
            // though the PCAP exists.
            recoverStoredCapturePath();
            running=false;createdMonitor=false;channelHopping=false;
            applyRunningState();
            restoreInterface(restoreFromStore());
        } else if(running){
            // The service is gone but we thought it was running (process death).
            recoverStoredCapturePath();
            running=false;createdMonitor=false;channelHopping=false;
            applyRunningState();
            restoreInterface(restoreFromStore());
        }
    }

    /** Restores the capture file pointer from the session record. */
    private void recoverStoredCapturePath(){
        if(latestCaptureFile!=null&&latestCaptureFile.isFile()) return;
        String stored=SettingsStore.captureSessionPath(this);
        if(stored.isEmpty()) return;
        File recorded=new File(stored);
        if(recorded.isFile()) latestCaptureFile=recorded;
    }

    /** Toggle channel hopping on/off */
    private void toggleChannelHopping() {
        if (!running || session == null) {
            append(getString(R.string.monitor_hop_not_running));
            return;
        }
        boolean enable = !session.isChannelHopping();
        if (!session.setChannelHopping(enable)) {
            append(getString(R.string.monitor_hop_not_supported));
            return;
        }
        channelHopping = enable;
        updateHopToggle();
        append(getString(enable ? R.string.monitor_hop_started : R.string.monitor_hop_stopped));
    }

    /** Stop the channel hopping process */
    private void stopChannelHopping() {
        channelHopping = false;
        if (session != null) {
            session.setChannelHopping(false);
        }
        updateHopToggle();
    }

    /** Encodes the discovered channels as "number,frequency" pairs for the service. */
    private String buildHopChannelsSpec() {
        StringBuilder spec = new StringBuilder();
        for (WifiChannelParser.Channel channel : channels) {
            if (spec.length() > 0) spec.append(';');
            spec.append(channel.number).append(',').append(channel.frequency);
        }
        return spec.toString();
    }

    private String[] restoreFromStore(){
        return new String[]{
                SettingsStore.captureSessionInterface(this),
                SettingsStore.captureSessionMonitor(this),
                SettingsStore.captureSessionOriginalState(this),
                String.valueOf(SettingsStore.captureSessionCreated(this)),
                SettingsStore.captureSessionPath(this)};
    }

    /**
     * Runs the backend restore once per session. The session record is cleared
     * before the work starts, so a duplicate stop request (button plus
     * notification) cannot run the restore twice.
     */
    private void restoreInterface(String[] session){
        if(session==null||session.length<3)return;
        if(!SettingsStore.captureSessionActive(this)&&!running)return;
        SettingsStore.clearCaptureSession(this);
        final String iface=session[0].isEmpty()?activeInterface:session[0];
        final String monitor=session[1].isEmpty()?selectedMonitor:session[1];
        final String state=session[2].isEmpty()?previousInterfaceState:session[2];
        final boolean created=Boolean.parseBoolean(session[3]);
        if(!iface.matches("[A-Za-z0-9_.-]{1,15}")||!monitor.matches("[A-Za-z0-9_.-]{1,15}"))return;
        append(getString(R.string.monitor_stopping));
        new Thread(()->{
            Result r=runRoot(monitorBackend.buildStopCommand(iface,monitor,state,conModePath),8000);
            boolean reconnect=WifiRecovery.requestReconnect(this);
            reconnect=WifiRecovery.reenableAfterConMode(this)||reconnect;
            if(conModePath!=null&&r.output!=null&&r.output.contains("CON_MODE_RESTORED:0")){
                append(getString(R.string.monitor_con_mode_restored,conModePath));
            }
            final boolean reconnected=reconnect;
            final String restoreState=("up".equals(state)?"up":"down");
            final boolean canExport=isUsableCapture(latestCaptureFile);
            runOnUiThread(()->{
                SettingsStore.clearCaptureSession(this);
                running=false;createdMonitor=false;
                applyRunningState();
                captureExportButton.setEnabled(canExport);
                handshakeButton.setEnabled(canExport);
                long captured=latestCaptureFile==null?0L:latestCaptureFile.length();
                String captureState=captured>24L?getString(R.string.monitor_capture_saved)
                        :getString(R.string.monitor_capture_no_data);
                capabilityState.setText(getString(R.string.monitor_stopped,restoreState,captureState)
                        +getString(reconnected?R.string.monitor_reconnect_requested:R.string.monitor_reconnect_manual));
                append(getString(R.string.monitor_restore_attempt,trim(r.output,1200)));
                append(getString(R.string.monitor_stop_button_used));
            });
        }).start();
    }

    /** Called when the service reports that the capture is gone. */
    private void onCaptureEnded(int exitCode){
        if(!running&&!SettingsStore.captureSessionActive(this))return;
        running=false;createdMonitor=false;channelHopping=false;
        applyRunningState();
        final boolean canExport=isUsableCapture(latestCaptureFile);
        captureExportButton.setEnabled(canExport);
        handshakeButton.setEnabled(canExport);
        // The capture may have died because tcpdump cannot run at all (an
        // under-aligned bundled binary, missing root); say so instead of just
        // "the capture ended".
        if(!canExport){
            append(getString(R.string.monitor_capture_empty_hint));
        }
        if(exitCode>0&&exitCode!=130&&exitCode!=143){
            // 130/143 are SIGINT/SIGTERM from a normal stop; anything else is
            // tcpdump itself failing (permission, missing interface, ...).
            append(getString(R.string.monitor_capture_exit_code,exitCode));
        }
        append(getString(R.string.monitor_capture_stopped_from_notification));
        restoreInterface(restoreFromStore());
    }

    private boolean isUsableCapture(File file){
        return file!=null&&file.isFile()&&file.length()>=24L;
    }

    /**
     * Keeps the interface/channel pickers aligned with the live capture.
     *
     * <p>While a capture runs the driver is already tuned to the channel that
     * was confirmed, so changing the spinner would only mislead the user. The
     * pickers are therefore locked, and the start button is disabled while the
     * stop button takes over; both flip back together when the capture ends.</p>
     */
    private void applyRunningState(){
        if(ifaceSpinner!=null) ifaceSpinner.setEnabled(!running && !probing);
        if(channelSpinner!=null) channelSpinner.setEnabled(!running && !probing);
        if(startButton!=null){
            startButton.setEnabled(!running && rootGranted
                    && !channels.isEmpty() && !interfaces.isEmpty());
            startButton.setText(getString(running?R.string.monitor_start_running:R.string.monitor_start));
        }
        if(stopButton!=null){
            stopButton.setEnabled(running);
            stopButton.setText(getString(R.string.monitor_stop_restore));
        }
        if(refreshButton!=null) refreshButton.setEnabled(!running && !probing);
        updateHopToggle();
    }

    /**
     * Keeps the hopping button consistent with the service: it is only usable
     * during a capture with at least two discovered channels.
     */
    private void updateHopToggle(){
        if(channelHopToggle==null) return;
        channelHopToggle.setEnabled(running && channels.size()>1);
        channelHopToggle.setText(getString(channelHopping
                ? R.string.monitor_hop_on : R.string.monitor_hop_off));
        channelHopToggle.setBackgroundColor(channelHopping?0xff79e0b0:0xffe0e0e0);
    }

    private void exportCapture(){
        File source=latestCaptureFile;
        if(running){toast(getString(R.string.monitor_stop_before_export));return;}
        if(!isUsableCapture(source)){toast(getString(R.string.monitor_no_capture));return;}
        pendingCaptureExport=source;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.tcpdump.pcap");
        intent.putExtra(Intent.EXTRA_TITLE,source.getName());
        try{startActivityForResult(intent,EXPORT_CAPTURE);}
        catch(Exception e){toast(getString(R.string.monitor_save_failed,e.getMessage()));}
    }

    /** Stops the capture from the UI: the service stops, then the backend restores. */
    private void stopMonitor(){
        if(!running&&!MonitorCaptureService.isRunning(this)){
            // Nothing is capturing but a session may still need its restore.
            if(SettingsStore.captureSessionActive(this)){
                recoverStoredCapturePath();
                restoreInterface(restoreFromStore());
            }
            return;
        }
        stopButton.setEnabled(false);
        stopButton.setText(getString(R.string.monitor_stop_stopping));
        startButton.setText(getString(R.string.monitor_start));
        MonitorCaptureService.Session boundSession=session;
        if(boundSession!=null) boundSession.stop(); else MonitorCaptureService.stop(this);
        stopChannelHopping();
        restoreInterface(restoreFromStore());
    }

    private void exportReport(){
        StringBuilder report=new StringBuilder();
        report.append(getString(R.string.monitor_report_title)).append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new java.util.Date())).append("\n");
        report.append(getString(R.string.monitor_report_app_version)).append(getPackageName()).append("\n").append(getString(R.string.monitor_report_android_version)).append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        report.append(getString(R.string.monitor_report_device)).append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n").append(getString(R.string.monitor_report_root)).append(rootGranted).append("\n");
        report.append(getString(R.string.monitor_report_interface)).append(ifaceSpinner.getSelectedItem()==null?"-":ifaceSpinner.getSelectedItem()).append("\n");
        report.append(getString(R.string.monitor_report_channel)).append(channelSpinner.getSelectedItem()==null?"-":channelSpinner.getSelectedItem()).append("\n").append(getString(R.string.monitor_report_running)).append(running).append("\n\n");        synchronized(diagnostics){report.append(diagnostics);}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TITLE,"wifikill-diagnostics-"+System.currentTimeMillis()+".txt");
        pendingReport=report.toString();
        try{startActivityForResult(intent,CREATE_REPORT);}catch(Exception e){toast(getString(R.string.monitor_save_failed,e.getMessage()));}
    }
    private String pendingReport="";
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==CREATE_REPORT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();
            try(OutputStream stream=getContentResolver().openOutputStream(uri)){
                if(stream==null)throw new java.io.IOException(getString(R.string.monitor_open_target_failed));
                stream.write(pendingReport.getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.flush();toast(getString(R.string.monitor_report_exported));
            }catch(Exception e){append(getString(R.string.monitor_report_export_failed,e.getMessage()));toast(getString(R.string.monitor_export_failed,e.getMessage()));}
            return;
        }
        if(requestCode==EXPORT_CAPTURE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            File source=pendingCaptureExport;
            Uri target=data.getData();
            if(!isUsableCapture(source)){toast(getString(R.string.monitor_capture_missing_file));return;}
            new Thread(()->{
                try(InputStream in=new FileInputStream(source);
                    OutputStream out=getContentResolver().openOutputStream(target)){
                    if(out==null)throw new java.io.IOException(getString(R.string.monitor_open_target_failed));
                    byte[] buffer=new byte[16384];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                    out.flush();
                    runOnUiThread(()->toast(getString(R.string.monitor_capture_exported,source.length())));
                }catch(Exception e){
                    runOnUiThread(()->{append(getString(R.string.monitor_pcap_export_failed,e.getMessage()));toast(getString(R.string.monitor_pcap_export_failed,e.getMessage()));});
                }
            },"wifitester-pcap-export").start();
        }
    }

    private void append(String s){
        String snapshot;
        synchronized(diagnostics){
            diagnostics.append(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new java.util.Date())).append("  ").append(s).append('\n');
            if(diagnostics.length()>60000)diagnostics.delete(0,diagnostics.length()-60000);
            snapshot=trim(diagnostics.toString(),14000);
        }
        if(log!=null)log.setText(snapshot);
        UiKit.updateLogCounter(logCard,UiKit.countLines(snapshot));
    }
    /**
     * Extracts the useful part of a failed start: the first kernel/driver error
     * line, so the user can tell "operation not supported" (driver limitation)
     * from "busy" (another interface owns the channel) without opening the log.
     */
    private String driverHint(String output){
        if(output==null||output.isEmpty())return "";
        Matcher m=Pattern.compile("(?im)^.*(operation not (supported|permitted)|device or resource busy|busy|invalid argument|no such device|not supported|cannot allocate|eperm|eopnotsupp|ebusy|eagain).*$").matcher(output);
        if(m.find()){
            String line=m.group().trim();
            return line.length()>200?line.substring(0,200)+"…":line;
        }
        return "";
    }

    private Result runRoot(String command,int timeout){
        RootShell.Result result=RootShell.run(this,command,timeout);
        return new Result(result.code,result.output);
    }
    private String trim(String s,int max){if(s==null)return "";return s.length()<=max?s:getString(R.string.monitor_output_truncated)+s.substring(s.length()-max);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable bg=round(PANEL,dp(20));bg.setStroke(dp(1),UiKit.color(this,Attrs.COLOR_OUTLINE_VARIANT));l.setBackground(bg);l.setElevation(0f);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private MaterialButton primaryButton(String s){return UiKit.primary(this,s);}
    private MaterialButton tonalButton(String s){return UiKit.tonal(this,s);}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams top(int v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(v);return p;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    /**
     * Leaving the screen no longer stops the capture: it belongs to the
     * foreground service, so rotating the device or switching apps keeps it
     * running. The user stops it from the notification or from this screen.
     */
    @Override protected void onDestroy(){
        releaseBinding();
        stopChannelHopping();
        super.onDestroy();
    }
    private static final class Result{final int code;final String output;Result(int c,String o){code=c;output=o;}}
}
