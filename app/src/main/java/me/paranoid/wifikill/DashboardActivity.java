package me.paranoid.wifikill;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rebuilt launcher: capability-oriented, modern, and independent of legacy screens. */
public final class DashboardActivity extends Activity {
    private static final int BG=0xff0b1020, PANEL=0xff151f33, ACCENT=0xff78b8ff, MUTED=0xffa8b5ca;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView rootValue, rootDetail, summary;
    private boolean checking;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(26),dp(22),dp(28));
        scroll.addView(root);

        root.addView(text("WIFITESTER  /  无线诊断工作台",11,ACCENT,true));
        TextView title=text("无线诊断\n工作台",32,Color.WHITE,true);
        title.setPadding(0,dp(10),0,dp(7));
        root.addView(title);
        root.addView(text("无线诊断、驱动能力检测与被动分析",14,MUTED,false));

        LinearLayout rootCard=card();
        rootCard.setOnClickListener(v->refreshRoot());
        rootCard.addView(text("ROOT 权限状态",11,ACCENT,true));
        rootValue=text("正在检测…",22,Color.WHITE,true);
        rootValue.setPadding(0,dp(8),0,dp(4));
        rootCard.addView(rootValue);
        rootDetail=text("正在验证 su 是否实际授予 UID 0 权限",13,MUTED,false);
        rootCard.addView(rootDetail);
        TextView refresh=text("点击重新检测   ↻",13,ACCENT,true);
        refresh.setPadding(0,dp(12),0,0);
        rootCard.addView(refresh);
        root.addView(rootCard, bottom());

        LinearLayout monitor=card();
        monitor.setOnClickListener(v->startActivity(new Intent(this,MonitorModeActivity.class)));
        monitor.addView(text("01  /  监听模式",12,ACCENT,true));
        monitor.addView(text("无线监听工作台",21,Color.WHITE,true), top(8));
        monitor.addView(text("接口检测 · 驱动频道枚举 · 被动抓包 · 诊断报告导出",13,MUTED,false), top(6));
        TextView action=text("打开监听工作台   →",14,ACCENT,true);
        action.setPadding(0,dp(14),0,0);
        monitor.addView(action);
        root.addView(monitor,bottom());

        LinearLayout audit=card();
        audit.addView(text("02  /  只读审计",12,ACCENT,true));
        audit.addView(text("Wi-Fi 网络信息",19,Color.WHITE,true),top(8));
        audit.addView(text("查看附近可见的 Wi-Fi 网络、信号强度、频段和安全类型。",13,MUTED,false),top(5));
        audit.setOnClickListener(v->startActivity(new Intent(this,WiFiAuditActivity.class)));
        root.addView(audit,bottom());

        LinearLayout info=card();
        info.addView(text("设备兼容性",16,Color.WHITE,true));
        summary=text("尚未检测无线能力。进入监听工作台后将检查接口、工具和驱动公开的频道。",13,MUTED,false);
        summary.setPadding(0,dp(8),0,0);
        info.addView(summary);
        root.addView(info);
        TextView footer=text("默认被动接收  ·  不发送无线注入",10,MUTED,true);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0,dp(24),0,0);
        root.addView(footer);
        setContentView(scroll);
    }

    private void refreshRoot() {
        if(checking || rootValue==null) return;
        checking=true;
        rootValue.setText("正在检测…");
        worker.execute(()->{
            String out="";
            int code=-1;
            try {
                Process p=new ProcessBuilder("su","-c","id").redirectErrorStream(true).start();
                StringBuilder b=new StringBuilder();
                Thread reader=new Thread(()->{
                    try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))){
                        String line;
                        while((line=r.readLine())!=null){
                            synchronized(b){b.append(line).append('\n');}
                        }
                    }catch(Exception ignored){}
                },"wfk-root-check-reader");
                reader.start();
                long deadline=System.currentTimeMillis()+15000L;
                boolean finished=false;
                while(System.currentTimeMillis()<deadline){
                    try{code=p.exitValue();finished=true;break;}
                    catch(IllegalThreadStateException running){Thread.sleep(50L);}
                }
                if(!finished){
                    p.destroy();
                    try{reader.join(1000L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    out="Root 权限检测超时（15 秒）。请检查 Root 管理器是否正在等待授权。";
                    code=-1;
                }else{
                    reader.join(1500L);
                    synchronized(b){out=b.toString().trim();}
                }
            } catch(Exception e){out=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}
            final String result=out; final int exit=code;
            runOnUiThread(()->{
                checking=false;
                boolean granted=exit==0 && result.matches("(?s).*\\buid=0(?:\\(|\\b).*");
                rootValue.setText(granted?"Root 已授权":"Root 不可用");
                rootValue.setTextColor(granted?0xff79e0b0:0xffffa1a1);
                rootDetail.setText(granted?"su 命令已实际返回 UID 0":"请在 Root 管理器中授权本应用；仅存在 su 文件不代表已授权。\n"+result);
            });
        });
    }

    // Check when the screen becomes active; a five-second su loop can repeatedly
    // trigger root-manager authorization prompts when access has been denied.
    @Override protected void onResume(){super.onResume();refreshRoot();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(18),dp(18),dp(18),dp(18));GradientDrawable bg=round(PANEL,dp(18));bg.setStroke(dp(1),0xff26344c);l.setBackground(bg);l.setElevation(dp(2));return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams bottom(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);return p;}
    private LinearLayout.LayoutParams top(int v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(v);return p;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
