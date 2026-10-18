package me.paranoid.wifikill;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Modern capability-oriented launcher for WiFiTester. */
public final class DashboardActivity extends Activity {
    private static final int BG=0xff0b1020, PANEL=0xff151f33, PANEL_ALT=0xff10192b;
    private static final int ACCENT=0xff8fc5ff, MUTED=0xffa8b5ca, WHITE=Color.WHITE;
    private static final String PREFS="wifitester_settings", KEY_AUTO_ROOT="auto_root_check";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView rootValue,rootDetail,toolchainSummary;
    private boolean checking;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
    }

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20),dp(22),dp(20),dp(28));
        scroll.addView(page);

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout brand=new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(text("WIFITESTER  /  WIRELESS LAB",11,ACCENT,true));
        brand.addView(text("无线诊断工作台",27,WHITE,true),top(5));
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView settings=text("⚙ 设置",14,ACCENT,true);
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(dp(13),dp(10),dp(13),dp(10));
        settings.setBackground(round(PANEL,dp(14)));
        settings.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        header.addView(settings);
        page.addView(header,bottom(18));
        page.addView(text("从设备能力检测开始，再进入对应的无线工具。",14,MUTED,false),bottom(18));

        LinearLayout status=card();
        status.setBackground(round(PANEL_ALT,dp(20)));
        status.addView(text("设备权限",11,ACCENT,true));
        rootValue=text("正在检测 Root…",21,WHITE,true);
        status.addView(rootValue,top(7));
        rootDetail=text("验证 su 是否实际返回 UID 0。",13,MUTED,false);
        status.addView(rootDetail,top(4));
        TextView refresh=text("手动重新检测  ↻",13,ACCENT,true);
        refresh.setPadding(0,dp(12),0,0);
        status.addView(refresh);
        status.setOnClickListener(v->refreshRoot());
        page.addView(status,bottom(20));

        page.addView(text("工具工作台",17,WHITE,true),bottom(10));
        addFeature(page,"01","监听模式","接口与频道能力检测 · 被动抓包 · 诊断报告",
                "打开监听工作台  →",()->startActivity(new Intent(this,MonitorModeActivity.class)));
        addFeature(page,"02","Aircrack-ng GUI 工具","工具链状态 · 抓包文件检查 · 只读离线分析",
                "打开 Aircrack-ng 工具  →",()->startActivity(new Intent(this,AircrackToolkitActivity.class)));
        addFeature(page,"03","Wi-Fi / SSID 扫描","查看系统可见网络、信号强度、频段与安全类型",
                "查看附近网络  →",()->startActivity(new Intent(this,WiFiAuditActivity.class)));

        LinearLayout tools=card();
        tools.addView(text("工具链概览",16,WHITE,true));
        toolchainSummary=text("启用 Root 检测后，可查看无线工具是否可用。",13,MUTED,false);
        tools.addView(toolchainSummary,top(8));
        TextView details=text("查看完整工具检测  →",13,ACCENT,true);
        tools.addView(details,top(12));
        tools.setOnClickListener(v->startActivity(new Intent(this,AircrackToolkitActivity.class)));
        page.addView(tools,bottom(18));

        TextView safety=text("被动优先  ·  仅对有权测试的网络进行诊断",11,MUTED,false);
        safety.setGravity(Gravity.CENTER);
        safety.setPadding(0,dp(10),0,0);
        page.addView(safety);
        setContentView(scroll);
    }

    private void addFeature(LinearLayout parent,String number,String title,String description,String action,Runnable click){
        LinearLayout item=card();
        item.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading=new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView index=text(number,12,ACCENT,true);
        index.setGravity(Gravity.CENTER);
        index.setBackground(round(0xff203754,dp(10)));
        index.setPadding(dp(10),dp(8),dp(10),dp(8));
        heading.addView(index);
        TextView name=text(title,19,WHITE,true);
        LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(0,-2,1);
        nameParams.leftMargin=dp(11);
        heading.addView(name,nameParams);
        item.addView(heading);
        item.addView(text(description,13,MUTED,false),top(10));
        item.addView(text(action,14,ACCENT,true),top(14));
        item.setOnClickListener(v->click.run());
        parent.addView(item,bottom(11));
    }

    private void refreshRoot(){
        if(checking||rootValue==null)return;
        checking=true;
        rootValue.setText("正在检测…");
        worker.execute(()->{
            String out="";int code=-1;Process process=null;
            try{
                String command="id; for t in aircrack-ng airodump-ng airmon-ng tcpdump iw ip capinfos tshark; do p=$(command -v \"$t\" 2>/dev/null || true); if [ -z \"$p\" ]; then for q in /data/data/com.termux/files/usr/bin/$t /system/bin/$t /usr/bin/$t /usr/sbin/$t; do [ -x \"$q\" ] && { p=\"$q\"; break; }; done; fi; if [ -n \"$p\" ]; then printf 'TOOL:%s:%s\\n' \"$t\" \"$p\"; else printf 'TOOL:%s:MISSING\\n' \"$t\"; fi; done";
                process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
                StringBuilder output=new StringBuilder();
                Process p=process;
                Thread reader=new Thread(()->{
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))){
                        String line;while((line=r.readLine())!=null){synchronized(output){output.append(line).append('\n');}}
                    }catch(Exception ignored){}
                },"wfk-root-check-reader");
                reader.start();
                long deadline=System.currentTimeMillis()+15000L;
                boolean finished=false;
                while(System.currentTimeMillis()<deadline){
                    try{code=process.exitValue();finished=true;break;}
                    catch(IllegalThreadStateException running){Thread.sleep(50L);}
                }
                if(!finished){
                    process.destroy();
                    try{reader.join(1000L);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                    out="Root 检测超时（15 秒）；请检查 Root 管理器授权提示。";code=-1;
                }else{
                    reader.join(1500L);
                    synchronized(output){out=output.toString().trim();}
                }
            }catch(Exception e){out=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}
            final String result=out;final int exit=code;
            runOnUiThread(()->{
                checking=false;
                boolean granted=exit==0&&result.matches("(?s).*\\buid=0(?:\\(|\\b).*");
                rootValue.setText(granted?"Root 已授权":"Root 不可用");
                rootValue.setTextColor(granted?0xff79e0b0:0xffffa1a1);
                rootDetail.setText(granted?"已确认 su 返回 UID 0。":"请检查 Root 管理器是否已授权本应用。\n"+result);
                StringBuilder summary=new StringBuilder();
                for(String line:result.split("\\n")){
                    if(!line.startsWith("TOOL:"))continue;
                    String[] parts=line.split(":",3);
                    if(parts.length<3)continue;
                    if(summary.length()>0)summary.append('\n');
                    summary.append(parts[1]).append("  ·  ").append("MISSING".equals(parts[2])?"未安装":parts[2]);
                }
                toolchainSummary.setText(granted?(summary.length()==0?"已获得 Root，但未读取到工具信息。":summary.toString()):"请先授予 Root，再打开 Aircrack-ng GUI 查看工具链。");
            });
        });
    }

    @Override protected void onResume(){
        super.onResume();
        if(getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean(KEY_AUTO_ROOT,true)){
            refreshRoot();
        }else if(rootValue!=null){
            rootValue.setText("自动检测已关闭");
            rootValue.setTextColor(WHITE);
            rootDetail.setText("点按此卡片可随时手动检查 Root 权限。");
            toolchainSummary.setText("自动检测已关闭；可进入 Aircrack-ng GUI 手动检测工具链。");
        }
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private LinearLayout card(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(17),dp(17),dp(17),dp(17));
        GradientDrawable bg=round(PANEL,dp(18));bg.setStroke(dp(1),0xff26344c);l.setBackground(bg);l.setElevation(dp(2));return l;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams bottom(int value){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(value);return p;}
    private LinearLayout.LayoutParams top(int value){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(value);return p;}
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
}
