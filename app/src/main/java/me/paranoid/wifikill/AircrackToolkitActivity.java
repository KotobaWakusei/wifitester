package me.paranoid.wifikill;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Safe GUI entry point for Aircrack-ng ecosystem integration.
 * This screen detects installed tools and performs read-only capture metadata inspection.
 * It intentionally does not expose deauthentication, packet injection, or password cracking.
 */
public final class AircrackToolkitActivity extends Activity {
    private static final int BG=0xff0b1020, PANEL=0xff151f33, ACCENT=0xff78b8ff, MUTED=0xffa8b5ca;
    private static final int OPEN_CAPTURE=7401;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView toolStatus, report;
    private Button detectButton, captureButton;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        detectTools();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(24),dp(20),dp(28));
        scroll.addView(root);

        root.addView(text("WIFITESTER  /  工具链集成",11,ACCENT,true));
        root.addView(text("Aircrack-ng 工作台",28,Color.WHITE,true),top(9));
        root.addView(text("工具环境检测、被动监听入口与抓包文件只读分析。Aircrack-ng 本身不会随 APK 自动安装。",14,MUTED,false),top(8));

        LinearLayout status=card();
        status.addView(text("工具链状态",17,Color.WHITE,true));
        toolStatus=text("正在检查 Root 环境与常用工具…",13,MUTED,false);
        toolStatus.setPadding(0,dp(10),0,0);
        status.addView(toolStatus);
        detectButton=button("重新检测工具链",0xff315d91);
        detectButton.setOnClickListener(v->detectTools());
        status.addView(detectButton,top(12));
        root.addView(status,bottom(12));

        LinearLayout monitor=card();
        monitor.addView(text("01  /  被动无线监听",16,Color.WHITE,true));
        monitor.addView(text("复用现有监听工作台，检测接口、驱动频道并查看抓包输出。是否支持 Monitor 模式取决于设备驱动。",13,MUTED,false),top(7));
        Button monitorButton=button("打开监听工作台",0xff315d91);
        monitorButton.setOnClickListener(v->startActivity(new Intent(this,MonitorModeActivity.class)));
        monitor.addView(monitorButton,top(12));
        root.addView(monitor,bottom(12));

        LinearLayout capture=card();
        capture.addView(text("02  /  抓包文件分析",16,Color.WHITE,true));
        capture.addView(text("选择已有的 PCAP/PCAPNG 文件。优先使用 capinfos 或 tshark 读取文件元数据；不会修改原文件，也不会尝试恢复密码。",13,MUTED,false),top(7));
        captureButton=button("选择抓包文件",0xff263a56);
        captureButton.setOnClickListener(v->openCapture());
        capture.addView(captureButton,top(12));
        report=text("尚未选择文件。",12,0xffdce6f5,false);
        report.setPadding(dp(12),dp(12),dp(12),dp(12));
        report.setBackground(round(0xff101827,dp(12)));
        capture.addView(report,top(10));
        root.addView(capture,bottom(12));

        LinearLayout audit=card();
        audit.addView(text("03  /  Wi-Fi 网络信息",16,Color.WHITE,true));
        audit.addView(text("查看 Android 系统提供的附近网络、信号强度、频率和安全类型。",13,MUTED,false),top(7));
        Button auditButton=button("打开网络信息",0xff263a56);
        auditButton.setOnClickListener(v->startActivity(new Intent(this,WiFiAuditActivity.class)));
        audit.addView(auditButton,top(12));
        root.addView(audit,bottom(12));

        TextView note=text("⚠ 使用免责声明与授权要求\\n本工具仅供合法的无线网络诊断、课程实验和经授权的安全测试。使用者必须拥有目标网络/设备，或事先取得网络所有者明确、可验证的书面授权，并遵守所在地法律、无线电监管要求及组织政策。监听、切换频道或关闭接口可能中断本机连接，并可能捕获第三方通信元数据；请优先使用隔离实验网络，尽量减少采集并妥善保护日志与抓包文件。开发者不对误用、数据丢失、服务中断或由使用者行为导致的损害承担责任。免责声明不构成任何法律许可，也不能替代真实授权。本工作台当前不提供无线注入、强制断开客户端或口令破解功能。",12,MUTED,false);
        note.setPadding(dp(4),dp(8),dp(4),0);
        root.addView(note);
        setContentView(scroll);
    }

    private void detectTools() {
        if(busy)return;
        busy=true;
        detectButton.setEnabled(false);
        toolStatus.setText("正在检测 Root 和工具路径…");
        worker.execute(()->{
            String command="printf 'UID: '; id; "+
                "for t in aircrack-ng airodump-ng airmon-ng capinfos tshark tcpdump iw ip; do "+
                "p=$(command -v \"$t\" 2>/dev/null || true); "+
                "if [ -z \"$p\" ]; then "+
                "for q in /data/data/com.termux/files/usr/bin/\"$t\" /system/bin/\"$t\" /usr/bin/\"$t\" /usr/sbin/\"$t\"; do "+
                "[ -x \"$q\" ] && { p=\"$q\"; break; }; done; fi; "+
                "if [ -n \"$p\" ]; then printf '%s: %s\\n' \"$t\" \"$p\"; else printf '%s: 未找到\\n' \"$t\"; fi; done";
            BundledToolchain.InstallResult embedded=new BundledToolchain(this).install();
            Result result=runRoot(command,10000);
            final String output=result.output;
            runOnUiThread(()->{
                busy=false;
                detectButton.setEnabled(true);
                if(result.code!=0 && (output==null||output.trim().isEmpty())){
                    toolStatus.setText("无法完成检测。请确认 Root 管理器已授权本应用。\n退出码："+result.code);
                }else{
                    toolStatus.setText("内置工具（"+new BundledToolchain(this).getAbi()+"）："+embedded.message+"\n"+(embedded.installed.isEmpty()?"未释放任何内置命令":"已释放："+android.text.TextUtils.join(", ",embedded.installed))+"\n"+output+(result.code==0?"\n\n检测完成。工具是否存在不代表无线驱动支持全部功能。":"\n\n检测命令退出码："+result.code));
                }
            });
        });
    }

    private void openCapture() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.tcpdump.pcap","application/octet-stream"});
        startActivityForResult(i,OPEN_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=OPEN_CAPTURE||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(busy){Toast.makeText(this,"已有任务正在运行",Toast.LENGTH_SHORT).show();return;}
        busy=true;
        captureButton.setEnabled(false);
        report.setText("正在读取抓包文件并检查分析工具…");
        worker.execute(()->{
            File copy=null;
            String output="";
            int code=-1;
            try{
                copy=File.createTempFile("wifitester-capture-",".pcap",getCacheDir());
                try(InputStream in=getContentResolver().openInputStream(uri);
                    FileOutputStream out=new FileOutputStream(copy)){
                    if(in==null)throw new IllegalStateException("无法读取所选文件");
                    byte[] buf=new byte[8192];int n;long total=0;
                    while((n=in.read(buf))!=-1){
                        total+=n;
                        if(total>200L*1024L*1024L)throw new IllegalArgumentException("文件超过 200 MiB 安全读取上限");
                        out.write(buf,0,n);
                    }
                    if(total==0)throw new IllegalArgumentException("文件为空");
                }
                String path=copy.getAbsolutePath();
                String quoted="'"+path.replace("'","'\\''")+"'";
                String cmd="if command -v capinfos >/dev/null 2>&1; then capinfos "+quoted+"; "+
                    "elif [ -x /data/data/com.termux/files/usr/bin/capinfos ]; then /data/data/com.termux/files/usr/bin/capinfos "+quoted+"; "+
                    "elif command -v tshark >/dev/null 2>&1; then tshark -r "+quoted+" -q -z io,phs; "+
                    "elif [ -x /data/data/com.termux/files/usr/bin/tshark ]; then /data/data/com.termux/files/usr/bin/tshark -r "+quoted+" -q -z io,phs; "+
                    "else echo '未找到 capinfos 或 tshark。请先在受信任的环境中安装 Wireshark CLI 工具。'; exit 127; fi";
                Result result=runRoot(cmd,20000);
                output="文件："+uri.getLastPathSegment()+"\n退出码："+result.code+"\n\n"+result.output;
                code=result.code;
            }catch(Exception e){output="读取或分析失败："+e.getClass().getSimpleName()+"："+String.valueOf(e.getMessage());}
            finally{if(copy!=null)try{copy.delete();}catch(Exception ignored){}}
            final String finalOutput=output;final int finalCode=code;
            runOnUiThread(()->{
                busy=false;
                captureButton.setEnabled(true);
                report.setText(finalOutput+(finalCode==127?"\n\n提示：APK 不会捆绑或自动安装这些命令。":""));
            });
        });
    }

    private Result runRoot(String command,int timeoutMs) {
        Process p=null;
        StringBuilder out=new StringBuilder();
        int code=-1;
        try{
            p=new ProcessBuilder("su","-c",new BundledToolchain(this).shellEnvironment()+command).redirectErrorStream(true).start();
            final Process process=p;
            Thread reader=new Thread(()->{
                try(BufferedReader r=new BufferedReader(new InputStreamReader(process.getInputStream()))){
                    String line;while((line=r.readLine())!=null){synchronized(out){out.append(line).append('\n');}}
                }catch(Exception ignored){}
            },"wifitester-tool-reader");
            reader.start();
            long deadline=System.currentTimeMillis()+timeoutMs;
            boolean done=false;
            while(System.currentTimeMillis()<deadline){
                try{code=p.exitValue();done=true;break;}
                catch(IllegalThreadStateException running){Thread.sleep(50L);}
            }
            if(!done){p.destroy();code=124;synchronized(out){out.append("命令超时，已终止等待。\n");}}
            reader.join(1000L);
        }catch(Exception e){synchronized(out){out.append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage()));}}
        finally{if(p!=null&&p.isAlive())p.destroy();}
        synchronized(out){return new Result(code,out.toString().trim());}
    }

    private static final class Result {
        final int code;final String output;
        Result(int c,String o){code=c;output=o;}
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(17),dp(17),dp(17),dp(17));GradientDrawable d=round(PANEL,dp(18));d.setStroke(dp(1),0xff26344c);l.setBackground(d);l.setElevation(dp(2));return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setMinHeight(dp(48));b.setBackground(round(color,dp(14)));return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams bottom(int v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(v);return p;}
    private LinearLayout.LayoutParams top(int v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(v);return p;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
