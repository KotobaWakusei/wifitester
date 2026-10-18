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
    private static final int CREATE_REPORT=7021;
    private TextView rootState, capabilityState, log;
    private Spinner ifaceSpinner, channelSpinner;
    private Button startButton, stopButton, exportButton, refreshButton;
    private final ArrayList<String> interfaces=new ArrayList<>();
    private final ArrayList<WifiChannelParser.Channel> channels=new ArrayList<>();
    private final StringBuilder diagnostics=new StringBuilder();
    private Process captureProcess;
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
        root.addView(text("自动发现无线接口与驱动公开的频道。启动前会提示可能造成的 Wi-Fi 中断风险。",14,MUTED,false),top(5));

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

        root.addView(text("诊断日志 / 抓包输出",15,Color.WHITE,true),top(18));
        log=text("等待设备检测…",12,0xffc3d1e5,false);log.setTypeface(Typeface.MONOSPACE);log.setPadding(dp(12),dp(12),dp(12),dp(12));log.setBackground(round(0xff070c16,dp(12)));root.addView(log,top(8));
        setContentView(scroll);
    }

    private void probe(){
        if(running){toast("请先停止监听，再重新检测");return;}
        startButton.setEnabled(false);rootState.setText("Root：正在验证授权…");capabilityState.setText("无线能力：正在枚举接口、频道和工具…");
        new Thread(()->{
            Result r=runRoot("id; echo __IW_PATH__; command -v iw; echo __TCPDUMP_PATH__; command -v tcpdump; echo __IP_PATH__; command -v ip; echo __IW_LIST__; iw list 2>&1; echo __IW_DEV__; iw dev 2>&1; echo __LINKS__; ip -o link show 2>&1",15000);
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
                append("===== 设备检测 =====\n退出码："+r.code+"\nRoot："+rootOk+" / iw："+iw+" / ip："+ip+" / tcpdump："+tcpdump+
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
            String cmd="set -e; IF='"+activeInterface+"'; MON='"+selectedMonitor+"'; "+
                    "command -v iw >/dev/null; command -v ip >/dev/null; "+
                    "if iw dev \"$MON\" info >/dev/null 2>&1; then echo 'ERROR: monitor interface already exists'; exit 7; fi; "+
                    "ip link set \"$IF\" down; "+
                    "if ! iw dev \"$IF\" interface add \"$MON\" type monitor flags otherbss; then "+
                    "iw dev \"$IF\" interface add \"$MON\" type monitor || { rc=$?; echo MONITOR_CREATE_FAILED:$rc; exit $rc; }; fi; "+
                    "echo MONITOR_CREATED; "+
                    "iw dev \"$MON\" set freq "+channel.frequency+" HT20 || { rc=$?; echo CHANNEL_SET_FAILED:$rc; exit $rc; }; "+
                    "ip link set \"$MON\" up || { rc=$?; echo MONITOR_UP_FAILED:$rc; exit $rc; }; echo MONITOR_READY";
            Result r=runRoot(cmd,12000);
            final String oldState=state;
            runOnUiThread(()->{
                previousInterfaceState=oldState;
                append("命令退出码："+r.code+"\n"+trim(r.output,2000));
                if(r.code!=0||r.output==null||!r.output.contains("MONITOR_READY")){
                    // Only delete an interface this invocation actually created.
                    // An existing wfkmon0 must never be removed on the preflight-error path.
                    String cleanup="";
                    if(r.output!=null && r.output.contains("MONITOR_CREATED")){
                        cleanup+="iw dev '"+selectedMonitor+"' del >/dev/null 2>&1 || true; ";
                    }
                    cleanup+="ip link set '"+activeInterface+"' "+("up".equals(oldState)?"up":"down")+" >/dev/null 2>&1 || true";
                    // Keep monitor deletion and original-interface restoration in one
                    // privileged shell so two independent su processes cannot race.
                    runRootAsync(cleanup);
                    String failure="监听启动失败；请查看日志。";
                    if(r.output!=null && r.output.contains("MONITOR_CREATE_FAILED")) failure="驱动拒绝创建监听接口。若错误为 EBUSY（-16），可能是接口/驱动资源冲突，或该驱动不允许创建 Monitor 接口。";
                    else if(r.output!=null && r.output.contains("CHANNEL_SET_FAILED")) failure="监听接口已创建，但驱动拒绝切换到所选频道；程序将清理本次创建的接口并恢复原状态。";
                    else if(r.output!=null && r.output.contains("MONITOR_UP_FAILED")) failure="监听接口已创建，但无法启用该接口；程序将清理本次创建的接口并恢复原状态。";
                    capabilityState.setText(failure);
                    startButton.setEnabled(true);refreshButton.setEnabled(true);return;
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
                captureProcess=new ProcessBuilder("su","-c","exec tcpdump -i '"+selectedMonitor+"' -n -e -l").redirectErrorStream(true).start();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(captureProcess.getInputStream()))){
                    String line;while((line=reader.readLine())!=null){final String item=line;runOnUiThread(()->append(item));}
                }
            }catch(Exception e){runOnUiThread(()->append("tcpdump 启动失败："+e.getClass().getSimpleName()+": "+e.getMessage()));}
        },"wfk-capture-reader").start();
    }

    private void stopMonitor(){
        if(!running&&!createdMonitor)return;
        stopButton.setEnabled(false);append("===== 停止监听并恢复接口 =====");
        new Thread(()->{
            Process p=captureProcess;if(p!=null){p.destroy();captureProcess=null;}
            String restore=("up".equals(previousInterfaceState)?"up":"down");
            Result r=runRoot("ip link set '"+selectedMonitor+"' down >/dev/null 2>&1 || true; iw dev '"+selectedMonitor+"' del >/dev/null 2>&1 || true; ip link set '"+activeInterface+"' "+restore+" >/dev/null 2>&1 || true; echo RESTORE_ATTEMPTED",8000);
            runOnUiThread(()->{
                running=false;createdMonitor=false;stopButton.setEnabled(false);startButton.setEnabled(rootGranted&&!channels.isEmpty()&&!interfaces.isEmpty());refreshButton.setEnabled(true);
                capabilityState.setText("已停止；已尝试恢复接口原始状态（"+restore+"）。请确认系统 Wi-Fi 已恢复。");
                append("恢复尝试：\n"+trim(r.output,1200));
            });
        }).start();
    }

    private void exportReport(){
        StringBuilder report=new StringBuilder();
        report.append("WiFiKill 监听模式 diagnostics\n生成时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new java.util.Date())).append("\n");
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
        }
    }

    private void append(String s){
        synchronized(diagnostics){diagnostics.append(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new java.util.Date())).append("  ").append(s).append('\n');if(diagnostics.length()>60000)diagnostics.delete(0,diagnostics.length()-60000);}
        if(log!=null)log.setText(trim(diagnostics.toString(),14000));
    }
    private Result runRoot(String command,int timeout){
        Process p=null;StringBuilder out=new StringBuilder();int code=-1;
        try{
            p=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
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
