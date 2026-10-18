package me.paranoid.wifikill;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only analyzer for deauthentication and disassociation frames in an existing capture. */
public final class FrameAnalysisActivity extends Activity {
    private static final int OPEN_CAPTURE=8301;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status,output;
    private Button choose,analyze;
    private Uri selected;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xfff5f7fb);
        getWindow().setNavigationBarColor(Color.WHITE);
        if(android.os.Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(18),dp(20),dp(18),dp(22));root.setBackgroundColor(0xfff5f7fb);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);
        root.addView(text("WIFITESTER  /  只读分析",11,0xff1769e0,true));
        root.addView(text("断开帧分析",27,0xff172033,true),top(5));
        root.addView(text("从已有 PCAP/PCAPNG 中筛选 802.11 deauthentication 与 disassociation 管理帧，辅助诊断异常掉线。此工具不会发送帧或使设备断开连接。",13,0xff64748b,false),top(7));
        LinearLayout card=card();
        card.addView(text("选择抓包文件",16,0xff172033,true));
        status=text("尚未选择文件。支持 PCAP / PCAPNG，最大 200 MiB。",13,0xff64748b,false);card.addView(status,top(7));
        choose=button("选择 PCAP 文件",0xffe7f0ff,0xff1769e0);choose.setOnClickListener(v->openCapture());card.addView(choose,top(12));
        analyze=button("分析断开帧",0xff1769e0,Color.WHITE);analyze.setEnabled(false);analyze.setOnClickListener(v->analyzeCapture());card.addView(analyze,top(8));
        root.addView(card,bottom(12));
        LinearLayout results=card();results.addView(text("分析结果",16,0xff172033,true));
        output=text("选择文件后开始分析。",12,0xff263449,false);output.setTypeface(Typeface.MONOSPACE);output.setTextIsSelectable(true);output.setPadding(dp(11),dp(11),dp(11),dp(11));output.setBackground(round(0xffedf1f7,dp(11)));results.addView(output,top(9));root.addView(results);
        setContentView(scroll);
    }

    private void openCapture() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        startActivityForResult(i,OPEN_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==OPEN_CAPTURE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null) {
            selected=data.getData();status.setText("已选择："+selected.getLastPathSegment());analyze.setEnabled(true);
            output.setText("准备分析所选文件。");
        }
    }

    private void analyzeCapture() {
        if(busy||selected==null)return;
        busy=true;analyze.setEnabled(false);choose.setEnabled(false);output.setText("正在校验文件并筛选管理帧…");
        final Uri uri=selected;
        worker.execute(()->{
            File copy=null;String result;
            try {
                copy=File.createTempFile("wifitester-frame-analysis-",".pcap",getCacheDir());
                try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(copy)) {
                    if(in==null)throw new IllegalStateException("无法读取所选文件");
                    byte[] buffer=new byte[8192];int n;long total=0;
                    while((n=in.read(buffer))!=-1){total+=n;if(total>CaptureFileInspector.MAX_FILE_BYTES)throw new IllegalArgumentException("文件超过 200 MiB 上限");out.write(buffer,0,n);}
                    if(total==0)throw new IllegalArgumentException("文件为空");
                }
                CaptureFileInspector.Format format;
                try(FileInputStream in=new FileInputStream(copy)){format=CaptureFileInspector.detect(in);}
                if(format==CaptureFileInspector.Format.UNKNOWN)throw new IllegalArgumentException("文件头不是有效的 PCAP/PCAPNG");
                BundledToolchain.InstallResult install=new BundledToolchain(this).install();
                if(install.installed.isEmpty())throw new IllegalStateException("APK 未能释放内置 tcpdump："+install.message);
                String file=BundledToolchain.quote(copy.getAbsolutePath());
                String command=new BundledToolchain(this).shellEnvironment()
                        +"tcpdump -nn -e -r "+file+" 'type mgt subtype deauth or type mgt subtype disassoc' 2>&1";
                Process p=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
                StringBuilder lines=new StringBuilder();int count=0;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;while((line=reader.readLine())!=null){if(lines.length()<45000){lines.append(line).append('\n');count++;}}
                }
                int code=p.waitFor();
                result="文件格式："+format.label+"\n过滤器：deauthentication / disassociation\n退出码："+code+"\n显示行数："+count+"\n\n"+(lines.length()==0?"没有匹配帧，或当前抓包格式/链路类型不支持此过滤器。":lines.toString());
                if(code!=0)result+="\n提示：tcpdump 可能不支持该抓包的 802.11 链路类型，或 Root 未授权。";
            } catch(Exception e) {result="分析失败："+e.getClass().getSimpleName()+"："+String.valueOf(e.getMessage());}
            finally {if(copy!=null)try{copy.delete();}catch(Exception ignored){}}
            final String finalResult=result;
            runOnUiThread(()->{busy=false;analyze.setEnabled(selected!=null);choose.setEnabled(true);output.setText(finalResult);});
        });
    }

    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(15),dp(15),dp(15),dp(15));c.setBackground(round(Color.WHITE,dp(16)));return c;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s,int bg,int fg){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(fg);b.setMinHeight(dp(46));b.setBackground(round(bg,dp(12)));return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(n);return p;}
    private LinearLayout.LayoutParams bottom(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(n);return p;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
