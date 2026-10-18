package me.paranoid.wifikill;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generic passive monitor workflow with capability-based interface and channel discovery. */
public final class MonitorModeActivity extends Activity {
    private static final int BG=0xff0b1020, PANEL=0xff151f33, ACCENT=0xff78b8ff, MUTED=0xffa8b5ca;
    private static final int CREATE_REPORT=7021, EXPORT_CAPTURE=7022;
    private TextView rootState, capabilityState, log;
    private Spinner ifaceSpinner, channelSpinner;
    private Button startButton, stopButton, exportButton, captureExportButton, refreshButton;
    private final MonitorModeBackend monitorBackend=new IwMonitorModeBackend();
    private final ArrayList<String> interfaces=new ArrayList<>();
    private final ArrayList<WifiChannelParser.Channel> channels=new ArrayList<>();
    private final StringBuilder diagnostics=new StringBuilder();
    private volatile Process captureProcess;
    private volatile long capturePid=-1L;
    private volatile File latestCaptureFile;
    private File pendingCaptureExport;
    private volatile boolean running=false, createdMonitor=false, rootGranted=false;
    private String activeInterface="", selectedMonitor="wfkmon0";
    private String previousInterfaceState="unknown";

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        buildUi();probe();
    }

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(22),dp(20),dp(24));scroll.addView(root);
        root.addView(text("无线驱动诊断工具",11,ACCENT,true));
        root.addView(text("监听模式",30,Color.WHITE,true),top(7));
        root.addView(text("自动发现无线接口与驱动公开的频道。可将被动抓包保存为 PCAP，单次最多 50,000 帧、每帧最多 512 字节。",14,MUTED,false),top(5));

        LinearLayout status=card();
        rootState=text("Root：正在检测…",16,Color.WHITE,true);status.addView(rootState);
        capabilityState=text("无线能力：等待检测",13,MUTED,false);status.addView(capabilityState,top(7));
        refreshButton=button("重新检测设备",0xff2c3d58);status.addView(refreshButton,top(12));refreshButton.setOnClickListener(v->probe());
        root.addView(status,top(17));

        LinearLayout controls=card();
        controls.addView(text("无线接口（按适用性排序）",14,Color.WHITE,true));
        ifaceSpinner=new Spinner(this);controls.addView(ifaceSpinner,top(4));
        controls.addView(text("驱动声明支持的频道（HT20）",14,Color.WHITE,true),top(12));
        channelSpinner=new Spinner(this);controls.addView(channelSpinner,top(4));
        root.addView(controls,top(12));

        TextView warning=text("监听通常是被动接收，但切换频道可能断开当前 Wi-Fi。仅对自己拥有或获授权的设备进行测试。",12,0xffffd08a,false);
        warning.setPadding(dp(2),dp(12),dp(2),dp(4));root.addView(warning);

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(0);
        startButton=button("开始监听",ACCENT);stopButton=button("停止并恢复",0xff34435c);
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(48),1);half.rightMargin=dp(5);actions.addView(startButton,half);
        LinearLayout.LayoutParams half2=new LinearLayout.LayoutParams(0,dp(48),1);half2.leftMargin=dp(5);actions.addView(stopButton,half2);
        root.addView(actions,top(8));stopButton.setEnabled(false);startButton.setEnabled(false);
        startButton.setOnClickListener(v->confirmStart());stopButton.setOnClickListener(v->stopMonitor());
        exportButton=button("导出完整诊断报告",0xff263a56);root.addView(exportButton,top(10));exportButton.setOnClickListener(v->exportReport());
        captureExportButton=button("导出最近的 PCAP 抓包",0xff315d91);captureExportButton.setEnabled(false);root.addView(captureExportButton,top(8));captureExportButton.setOnClickListener(v->exportCapture());

        root.addView(text("诊断日志 / 抓包输出",15,Color.WHITE,true),top(18));
        log=text("等待设备检测…",12,0xffc3d1e5,false);log.setTypeface(Typeface.MONOSPACE);log.setPadding(dp(12),dp(12),dp(12),dp(12));log.setBackground(round(0xff070c16,dp(12)));root.addView(log,top(8));
        setContentView(scroll);
    }

    private void probe(){
        if(running){toast("请先停止监听，再重新检测");return;}
        startButton.setEnabled(false);rootState.setText("Root：正在验证授权…");capabilityState.setText("无线能力：正在枚举接口、频道和工具…");
        new Thread(()->{
            BundledToolchain.InstallResult embedded=new BundledToolchain(this).install();
            Result r=runRoot(monitorBackend.probeCommand(),15000);
            String out=r.output==null?"":r.output;
            boolean rootOk=r.code==0 && Pattern.compile("\\buid=0(?:\\(|\\b)").matcher(out).find();
            ArrayList<String> found=discoverInterfaces(out);
            ArrayList<WifiChannelParser.Channel> foundChannels=WifiChannelParser.parse(out);
            boolean iw=toolPath(out,"__IW_PATH__"), tcpdump=toolPath(out,"__TCPDUMP_PATH__"), ip=toolPath(out,"__IP_PATH__");
            runOnUiThread(()->{
                rootGranted=rootOk;interfaces.clear();interfaces.addAll(found);channels.clear();channels.addAll(foundChannels);
                rootState.setText(rootOk?"Root：已授权（UID 0）":"Root：未授权或不可用");
                rootState.setTextColor(rootOk?0xff79e0b0:0xffffa1a1);
                ArrayAdapter<String> ia=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,interfaces.isEmpty()?new ArrayList<String>() : interfaces);
                ifaceSpinner.setAdapter(ia);
                if(!interfaces.isEmpty()) ifaceSpinner.setSelection(0);
                ArrayList<String> labels=new ArrayList<>();for(WifiChannelParser.Channel c:channels)labels.add(c.label);
                if(labels.isEmpty()) labels.add("未发现驱动声明的频道");
                channelSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
                boolean ok=rootOk&&iw&&tcpdump&&ip&&!interfaces.isEmpty()&&!channels.isEmpty();
                capabilityState.setText("工具："+(iw?"iw ✓":"iw ×")+"  "+(ip?"ip ✓":"ip ×")+"  "+(tcpdump?"tcpdump ✓":"tcpdump ×")+
                        "\n无线接口："+interfaces.size()+"  ·  驱动公开频道："+channels.size()+
                        "\n频道列表来自 iw list；能否创建接口及调谐频道仍取决于驱动的实际运行结果。");
                startButton.setEnabled(ok);
                append("===== 设备检测 =====\n内置工具（"+new BundledToolchain(this).getAbi()+"）："+embedded.message+"；已释放："+embedded.installed+"\n退出码："+r.code+"\nRoot："+rootOk+" / iw："+iw+" / ip："+ip+" / tcpdump："+tcpdump+
                        "\n接口："+interfaces+"\n频道："+labels+"\n\n"+trim(out,6500));
            });
        }).start();
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
        new AlertDialog.Builder(this).setTitle("开始被动监听")
                .setMessage("即将使用 "+ifaceSpinner.getSelectedItem()+"，频道 "+channels.get(channelSpinner.getSelectedItemPosition()).label+
                        "。系统可能需要关闭原接口，导致当前 Wi-Fi 连接暂时中断。是否继续？")
                .setNegativeButton("取消",null).setPositiveButton("开始",(d,w)->startMonitor()).show();
    }

    private void startMonitor(){
        if(running||ifaceSpinner.getSelectedItem()==null)return;
        activeInterface=ifaceSpinner.getSelectedItem().toString();
        if(!activeInterface.matches("[A-Za-z0-9_.-]+")){append("已取消操作：无线接口名称格式不安全。");return;}
        WifiChannelParser.Channel channel=channels.get(channelSpinner.getSelectedItemPosition());
        startButton.setEnabled(false);refreshButton.setEnabled(false);append("===== 启动监听 =====\n接口："+activeInterface+"，频道："+channel.label);
        new Thread(()->{
            Result pre=runRoot("ip -o link show dev '"+activeInterface+"' 2>&1",4000);
            String old=pre.output==null?"":pre.output;
            if(pre.code!=0){
                runOnUiThread(()->{
                    append("无法可靠读取接口原始状态，已取消启动，未修改网络状态。\n"+trim(old,1200));
                    capabilityState.setText("无法读取接口状态；未更改网络接口。");
                    startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());
                    refreshButton.setEnabled(true);
                });
                return;
            }
            String state=InterfaceStateParser.parseAdministrativeState(old);
            if(state==null){
                runOnUiThread(()->{
                    append("无法解析接口状态，已取消启动，未修改网络状态。\n"+trim(old,1200));
                    capabilityState.setText("接口状态格式未知；未更改网络接口。");
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
                append("命令退出码："+r.code+"\n"+trim(r.output,2000));
                if(r.code!=0||r.output==null||!r.output.contains("MONITOR_READY")){
                    // Only delete an interface this invocation actually created.
                    // An existing wfkmon0 must never be removed on the preflight-error path.
                    String cleanup=monitorBackend.buildFailedStartCleanupCommand(
                            activeInterface,selectedMonitor,oldState,
                            r.output!=null&&r.output.contains("MONITOR_CREATED"));
                    // Complete cleanup before allowing another start attempt; otherwise a
                    // fast retry can race the old cleanup and trigger another EBUSY.
                    String failure="监听启动失败；请查看日志。";
                    if(r.output!=null && r.output.contains("MONITOR_CREATE_FAILED")) failure="驱动拒绝创建监听接口。若错误为 EBUSY（-16），可能是接口/驱动资源冲突，或该驱动不允许创建 Monitor 接口。";
                    else if(r.output!=null && r.output.contains("CHANNEL_SET_FAILED")) failure="监听接口已创建，但驱动拒绝切换频道（常见原因：驱动未实现 monitor 调谐、PHY 正被其他虚拟接口占用，或频道受监管域限制）；已采集驱动诊断并正在清理、恢复。";
                    else if(r.output!=null && r.output.contains("MONITOR_UP_FAILED")) failure="监听接口已创建，但无法启用该接口；正在清理接口并恢复原状态。";
                    final String failureMessage=failure;
                    final String cleanupCommand=cleanup;
                    startButton.setEnabled(false);refreshButton.setEnabled(false);
                    new Thread(()->{
                        Result cleanupResult=runRoot(cleanupCommand,6000);
                        runOnUiThread(()->{
                            append("===== 清理与恢复 =====\n退出码："+cleanupResult.code+"\n"+trim(cleanupResult.output,1200));
                            capabilityState.setText(failureMessage);
                            startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());
                            refreshButton.setEnabled(true);
                        });
                    }).start();
                    return;
                }
                running=true;createdMonitor=true;stopButton.setEnabled(true);
                capabilityState.setText("监听接口已启动：捕获 "+channel.label+" 的可见帧。");
                beginCapture();
            });
        }).start();
    }

    private void beginCapture(){
        new Thread(()->{
            try{
                File directory=new File(getFilesDir(),"captures");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new java.io.IOException("无法创建应用私有抓包目录");
                File target=File.createTempFile("wifitester-",".pcap",directory);
                latestCaptureFile=target;
                String path=BundledToolchain.quote(target.getAbsolutePath());
                String iface=BundledToolchain.quote(selectedMonitor);
                String owner=String.valueOf(android.os.Process.myUid());
                int packetLimit=getSharedPreferences("wifitester_settings",MODE_PRIVATE).getInt("capture_packet_limit",50000);
                if(packetLimit!=5000&&packetLimit!=20000&&packetLimit!=50000)packetLimit=50000;
                int snaplen=getSharedPreferences("wifitester_settings",MODE_PRIVATE).getInt("capture_snaplen",512);
                if(snaplen!=256&&snaplen!=512&&snaplen!=1024)snaplen=512;
                final int capturePacketLimit=packetLimit;
                final int captureSnaplen=snaplen;
                String shell=new BundledToolchain(this).shellEnvironment()
                        +"umask 022; tcpdump -i "+iface+" -U -n -s "+snaplen+" -c "+packetLimit+" -w "+path
                        +" & CAPTURE_PID=$!; echo WIFITESTER_CAPTURE_PID:$CAPTURE_PID; wait \"$CAPTURE_PID\"; "
                        +"RC=$?; chown "+owner+":"+owner+" "+path+" >/dev/null 2>&1 || true; chmod 600 "+path+" >/dev/null 2>&1 || true; exit $RC";
                Process process=new ProcessBuilder("su","-c",shell).redirectErrorStream(true).start();
                captureProcess=process;
                runOnUiThread(()->append("被动 PCAP 捕获已启动：最多 "+capturePacketLimit+" 帧，snaplen "+captureSnaplen+"；文件："+target.getName()));
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()))){
                    String line;
                    while((line=reader.readLine())!=null){
                        if(line.startsWith("WIFITESTER_CAPTURE_PID:")){
                            try{long parsed=Long.parseLong(line.substring("WIFITESTER_CAPTURE_PID:".length()).trim());if(parsed>1L)capturePid=parsed;}
                            catch(NumberFormatException ignored){}
                            final long pid=capturePid;
                            runOnUiThread(()->append("tcpdump 进程标识："+(pid>1L?String.valueOf(pid):"未能解析")));
                        }else{
                            final String item=line;runOnUiThread(()->append(item));
                        }
                    }
                }
                int exit=process.waitFor();
                runOnUiThread(()->append("抓包进程结束，退出码："+exit+"；文件大小："+target.length()+" 字节。"));
            }catch(Exception e){
                runOnUiThread(()->append("tcpdump 启动或写入 PCAP 失败："+e.getClass().getSimpleName()+": "+e.getMessage()));
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
        if(running){toast("请先停止监听，等待 PCAP 文件关闭后再导出");return;}
        if(!isUsableCapture(source)){toast("尚无有效抓包文件；请先启动并停止一次监听");return;}
        pendingCaptureExport=source;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.tcpdump.pcap");
        intent.putExtra(Intent.EXTRA_TITLE,source.getName());
        try{startActivityForResult(intent,EXPORT_CAPTURE);}
        catch(Exception e){toast("无法打开文件保存界面："+e.getMessage());}
    }

    private void stopMonitor(){
        if(!running&&!createdMonitor)return;
        stopButton.setEnabled(false);append("===== 停止监听并恢复接口 =====");
        new Thread(()->{
            long pid=capturePid;
            long pidDeadline=System.currentTimeMillis()+1200L;
            while(pid<=1L&&System.currentTimeMillis()<pidDeadline){
                try{Thread.sleep(40L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                pid=capturePid;
            }
            if(pid>1L){
                Result signal=runRoot("kill -INT "+pid+" 2>/dev/null || true; echo CAPTURE_INTERRUPT_SENT",2500);
                runOnUiThread(()->append("已请求 tcpdump 完成写入并关闭 PCAP：\n"+trim(signal.output,600)));
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
                    runOnUiThread(()->append("警告：抓包进程未及时退出，已终止等待；请检查导出的文件是否完整。"));
                }
            }
            captureProcess=null;capturePid=-1L;
            String restore=("up".equals(previousInterfaceState)?"up":"down");
            Result r=runRoot(monitorBackend.buildStopCommand(activeInterface,selectedMonitor,previousInterfaceState),8000);
            boolean canExport=isUsableCapture(latestCaptureFile);
            runOnUiThread(()->{
                running=false;createdMonitor=false;stopButton.setEnabled(false);startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());refreshButton.setEnabled(true);
                captureExportButton.setEnabled(canExport);
                capabilityState.setText("已停止；已尝试恢复接口原始状态（"+restore+"）。"+(canExport?" PCAP 已保存，可导出分析。":" 未生成有效 PCAP。")+"请确认系统 Wi-Fi 已恢复。");
                append("恢复尝试：\n"+trim(r.output,1200));
            });
        }).start();
    }

    private void exportReport(){
        StringBuilder report=new StringBuilder();
        report.append("WiFiTester 监听模式诊断\n生成时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new java.util.Date())).append("\n");
        report.append("应用版本：").append(getPackageName()).append("\nAndroid 版本：").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        report.append("设备：").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\nRoot currently granted: ").append(rootGranted).append("\n");
        report.append("选定接口：").append(ifaceSpinner.getSelectedItem()==null?"-":ifaceSpinner.getSelectedItem()).append("\n");
        report.append("选定频道：").append(channelSpinner.getSelectedItem()==null?"-":channelSpinner.getSelectedItem()).append("\nMonitor running: ").append(running).append("\n\n");
        synchronized(diagnostics){report.append(diagnostics);}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TITLE,"wifikill-diagnostics-"+System.currentTimeMillis()+".txt");
        pendingReport=report.toString();
        try{startActivityForResult(intent,CREATE_REPORT);}catch(Exception e){toast("无法打开文件保存界面："+e.getMessage());}
    }
    private String pendingReport="";
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==CREATE_REPORT&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();
            try(OutputStream stream=getContentResolver().openOutputStream(uri)){
                if(stream==null)throw new java.io.IOException("无法打开目标文件");
                stream.write(pendingReport.getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.flush();toast("诊断报告已导出");
            }catch(Exception e){append("报告导出失败："+e.getMessage());toast("导出失败："+e.getMessage());}
            return;
        }
        if(requestCode==EXPORT_CAPTURE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            File source=pendingCaptureExport;
            Uri target=data.getData();
            if(!isUsableCapture(source)){toast("抓包文件已不存在或不完整");return;}
            new Thread(()->{
                try(InputStream in=new FileInputStream(source);
                    OutputStream out=getContentResolver().openOutputStream(target)){
                    if(out==null)throw new java.io.IOException("无法打开目标文件");
                    byte[] buffer=new byte[16384];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                    out.flush();
                    runOnUiThread(()->toast("PCAP 已导出："+source.length()+" 字节"));
                }catch(Exception e){
                    runOnUiThread(()->{append("PCAP 导出失败："+e.getMessage());toast("PCAP 导出失败："+e.getMessage());});
                }
            },"wifitester-pcap-export").start();
        }
    }

    private void append(String s){
        synchronized(diagnostics){diagnostics.append(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new java.util.Date())).append("  ").append(s).append('\n');if(diagnostics.length()>60000)diagnostics.delete(0,diagnostics.length()-60000);}
        if(log!=null)log.setText(trim(diagnostics.toString(),14000));
    }
    private Result runRoot(String command,int timeout){
        Process p=null;StringBuilder out=new StringBuilder();int code=-1;
        try{
            p=new ProcessBuilder("su","-c",new BundledToolchain(this).shellEnvironment()+command).redirectErrorStream(true).start();
            final Process process=p;
            Thread reader=new Thread(()->{try(BufferedReader br=new BufferedReader(new InputStreamReader(process.getInputStream()))){String line;while((line=br.readLine())!=null)synchronized(out){out.append(line).append('\n');}}catch(Exception ignored){}});
            reader.start();long deadline=System.currentTimeMillis()+timeout;boolean finished=false;
            while(System.currentTimeMillis()<deadline){try{code=p.exitValue();finished=true;break;}catch(IllegalThreadStateException e){Thread.sleep(50);}}
            if(finished){
                reader.join(1500);
            }else{
                p.destroy();
                long stopDeadline=System.currentTimeMillis()+500L;
                while(System.currentTimeMillis()<stopDeadline){
                    try{code=p.exitValue();break;}
                    catch(IllegalThreadStateException stopping){Thread.sleep(25L);}
                }
                try{reader.join(800L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                synchronized(out){out.append("\n命令超时");}
            }
        }catch(Exception e){synchronized(out){out.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());}}
        finally{if(p!=null)try{p.exitValue();}catch(IllegalThreadStateException e){p.destroy();}}
        synchronized(out){return new Result(code,out.toString());}
    }
    private void runRootAsync(String cmd){new Thread(()->runRoot(cmd,8000)).start();}
    private String trim(String s,int max){if(s==null)return "";return s.length()<=max?s:"…（较早输出已截断）…\n"+s.substring(s.length()-max);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(17),dp(17),dp(17),dp(17));GradientDrawable bg=round(PANEL,dp(17));bg.setStroke(dp(1),0xff26344c);l.setBackground(bg);l.setElevation(dp(2));return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setMinHeight(dp(48));b.setBackground(round(color,dp(14)));if(android.os.Build.VERSION.SDK_INT>=21)b.setElevation(dp(1));return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams top(int v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(v);return p;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onDestroy(){if(running||createdMonitor)stopMonitor();super.onDestroy();}
    private static final class Result{final int code;final String output;Result(int c,String o){code=c;output=o;}}
}
