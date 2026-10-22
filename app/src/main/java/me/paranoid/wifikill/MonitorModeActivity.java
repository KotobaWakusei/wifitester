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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private MaterialButton startButton, stopButton, exportButton, captureExportButton, refreshButton;
    private final MonitorModeBackend monitorBackend=new IwMonitorModeBackend(this);
    private final ArrayList<String> interfaces=new ArrayList<>();
    private final ArrayList<WifiChannelParser.Channel> channels=new ArrayList<>();
    private final StringBuilder diagnostics=new StringBuilder();
    private volatile Process captureProcess;
    private volatile long capturePid=-1L;
    private volatile File latestCaptureFile;
    private File pendingCaptureExport;
    private volatile boolean running=false, createdMonitor=false, rootGranted=false;
    private volatile boolean probing=false;
    private String activeInterface="", selectedMonitor="wfkmon0";
    private String previousInterfaceState="unknown";

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        BG=UiKit.color(this,Attrs.COLOR_SURFACE);
        PANEL=UiKit.color(this,Attrs.COLOR_SURFACE_CONTAINER_LOWEST);
        INK=UiKit.color(this,Attrs.COLOR_ON_SURFACE);
        ACCENT=UiKit.color(this,Attrs.COLOR_PRIMARY);
        MUTED=UiKit.color(this,Attrs.COLOR_ON_SURFACE_VARIANT);
        buildUi();probe();
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
            runOnUiThread(()->{
                probing=false;
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
        WifiChannelParser.Channel channel=channels.get(channelSpinner.getSelectedItemPosition());
        startButton.setEnabled(false);refreshButton.setEnabled(false);append(getString(R.string.monitor_starting,activeInterface,channel.label));
        new Thread(()->{
            Result pre=runRoot("ip -o link show dev '"+activeInterface+"' 2>&1",4000);
            String old=pre.output==null?"":pre.output;
            if(pre.code!=0){
                runOnUiThread(()->{
                    append(getString(R.string.monitor_state_unreadable,trim(old,1200)));
                    capabilityState.setText(getString(R.string.monitor_state_unreadable_short));
                    startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());
                    refreshButton.setEnabled(true);
                });
                return;
            }
            String state=InterfaceStateParser.parseAdministrativeState(old);
            if(state==null){
                runOnUiThread(()->{
                    append(getString(R.string.monitor_state_unparsable,trim(old,1200)));
                    capabilityState.setText(getString(R.string.monitor_state_unknown));
                    startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());
                    refreshButton.setEnabled(true);
                });
                return;
            }
            String cmd=monitorBackend.buildStartCommand(activeInterface,selectedMonitor,channel);
            Result r=runRoot(cmd,12000);
            final String oldState=state;
            runOnUiThread(()->{
                previousInterfaceState=oldState;
                append(getString(R.string.monitor_exit_code,r.code,trim(r.output,2000)));
                if(r.code!=0||r.output==null||!r.output.contains("MONITOR_READY")){
                    // Only delete an interface this invocation actually created.
                    // An existing wfkmon0 must never be removed on the preflight-error path.
                    String cleanup=monitorBackend.buildFailedStartCleanupCommand(
                            activeInterface,selectedMonitor,oldState,
                            r.output!=null&&r.output.contains("MONITOR_CREATED"));
                    // Complete cleanup before allowing another start attempt; otherwise a
                    // fast retry can race the old cleanup and trigger another EBUSY.
                    String failure=getString(R.string.monitor_start_failed);
                    if(r.output!=null && r.output.contains("MONITOR_CREATE_FAILED")) failure=getString(R.string.monitor_create_failed);
                    else if(r.output!=null && r.output.contains("CHANNEL_SET_FAILED")) failure=getString(R.string.monitor_channel_failed);
                    else if(r.output!=null && r.output.contains("MONITOR_UP_FAILED")) failure=getString(R.string.monitor_up_failed);
                    final String failureMessage=failure;
                    final String cleanupCommand=cleanup;
                    startButton.setEnabled(false);refreshButton.setEnabled(false);
                    new Thread(()->{
                        Result cleanupResult=runRoot(cleanupCommand,6000);
                        runOnUiThread(()->{
                            append(getString(R.string.monitor_cleanup,cleanupResult.code,trim(cleanupResult.output,1200)));
                            capabilityState.setText(failureMessage);
                            startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());
                            refreshButton.setEnabled(true);
                        });
                    }).start();
                    return;
                }
                running=true;createdMonitor=true;stopButton.setEnabled(true);
                capabilityState.setText(getString(R.string.monitor_started,channel.label));
                beginCapture();
            });
        }).start();
    }

    private void beginCapture(){
        new Thread(()->{
            try{
                File directory=new File(getFilesDir(),"captures");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException(getString(R.string.monitor_capture_dir_failed));
                File target=File.createTempFile("wifitester-",".pcap",directory);
                latestCaptureFile=target;
                String path=BundledToolchain.quote(target.getAbsolutePath());
                String iface=BundledToolchain.quote(selectedMonitor);
                String owner=String.valueOf(android.os.Process.myUid());
                int packetLimit=SettingsStore.capturePacketLimit(this);
                int snaplen=SettingsStore.captureSnaplen(this);
                final int capturePacketLimit=packetLimit;
                final int captureSnaplen=snaplen;
                String shell=new BundledToolchain(this).shellEnvironment()
                        +"umask 022; tcpdump -i "+iface+" -U -n -s "+snaplen+" -c "+packetLimit+" -w "+path
                        +" & CAPTURE_PID=$!; echo WIFITESTER_CAPTURE_PID:$CAPTURE_PID; wait \"$CAPTURE_PID\"; "
                        +"RC=$?; chown "+owner+":"+owner+" "+path+" >/dev/null 2>&1 || true; chmod 600 "+path+" >/dev/null 2>&1 || true; exit $RC";
                Process process=new ProcessBuilder("su","-c",shell).redirectErrorStream(true).start();
                captureProcess=process;
                runOnUiThread(()->append(getString(R.string.monitor_capture_started,capturePacketLimit,captureSnaplen,target.getName())));
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){
                    String line;
                    while((line=reader.readLine())!=null){
                        if(line.startsWith("WIFITESTER_CAPTURE_PID:")){
                            try{long parsed=Long.parseLong(line.substring("WIFITESTER_CAPTURE_PID:".length()).trim());if(parsed>1L)capturePid=parsed;}
                            catch(NumberFormatException ignored){}
                            final long pid=capturePid;
                            runOnUiThread(()->append(getString(R.string.monitor_pid,pid>1L?String.valueOf(pid):"-")));
                        }else{
                            final String item=line;runOnUiThread(()->append(item));
                        }
                    }
                }
                int exit=process.waitFor();
                runOnUiThread(()->append(getString(R.string.monitor_capture_finished,exit,target.length())));
            }catch(Exception e){
                runOnUiThread(()->append(getString(R.string.monitor_capture_write_failed,e.getClass().getSimpleName()+": "+e.getMessage())));
            }finally{
                runOnUiThread(()->{
                    if(captureExportButton!=null)captureExportButton.setEnabled(!running&&isUsableCapture(latestCaptureFile));
                });
            }
        },"wifitester-pcap-capture").start();
    }

    private boolean isUsableCapture(File file){
        return file!=null&&file.isFile()&&file.length()>=24L;
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

    private void stopMonitor(){
        if(!running&&!createdMonitor)return;
        stopButton.setEnabled(false);append(getString(R.string.monitor_stopping));
        new Thread(()->{
            long pid=capturePid;
            long pidDeadline=System.currentTimeMillis()+1200L;
            while(pid<=1L&&System.currentTimeMillis()<pidDeadline){
                try{Thread.sleep(40L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                pid=capturePid;
            }
            if(pid>1L){
                Result signal=runRoot("kill -INT "+pid+" 2>/dev/null || true; echo CAPTURE_INTERRUPT_SENT",2500);
                runOnUiThread(()->append(getString(R.string.monitor_flush,trim(signal.output,600))));
            }
            Process p=captureProcess;
            if(p!=null){
                long deadline=System.currentTimeMillis()+2200L;
                boolean finished=false;
                while(System.currentTimeMillis()<deadline){
                    try{p.exitValue();finished=true;break;}
                    catch(IllegalThreadStateException e){try{Thread.sleep(40L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}
                }
                if(!finished){
                    p.destroy();
                    runOnUiThread(()->append(getString(R.string.monitor_capture_timeout)));
                }
            }
            captureProcess=null;capturePid=-1L;
            String restore=("up".equals(previousInterfaceState)?"up":"down");
            Result r=runRoot(monitorBackend.buildStopCommand(activeInterface,selectedMonitor,previousInterfaceState),8000);
            boolean canExport=isUsableCapture(latestCaptureFile);
            runOnUiThread(()->{
                running=false;createdMonitor=false;stopButton.setEnabled(false);startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());refreshButton.setEnabled(true);
                captureExportButton.setEnabled(canExport);
                capabilityState.setText(getString(R.string.monitor_stopped,restore,canExport?getString(R.string.monitor_capture_saved):getString(R.string.monitor_capture_missing)));
                append(getString(R.string.monitor_restore_attempt,trim(r.output,1200)));
            });
        }).start();
    }

    private void exportReport(){
        StringBuilder report=new StringBuilder();
        report.append(getString(R.string.monitor_report_title)).append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new java.util.Date())).append("\n");
        report.append(getString(R.string.monitor_report_app_version)).append(getPackageName()).append("\n").append(getString(R.string.monitor_report_android_version)).append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        report.append(getString(R.string.monitor_report_device)).append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n").append(getString(R.string.monitor_report_root)).append(rootGranted).append("\n");
        report.append(getString(R.string.monitor_report_interface)).append(ifaceSpinner.getSelectedItem()==null?"-":ifaceSpinner.getSelectedItem()).append("\n");
        report.append(getString(R.string.monitor_report_channel)).append(channelSpinner.getSelectedItem()==null?"-":channelSpinner.getSelectedItem()).append("\n").append(getString(R.string.monitor_report_running)).append(running).append("\n\n");
        synchronized(diagnostics){report.append(diagnostics);}
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
    @Override protected void onDestroy(){if(running||createdMonitor)stopMonitor();super.onDestroy();}
    private static final class Result{final int code;final String output;Result(int c,String o){code=c;output=o;}}
}
