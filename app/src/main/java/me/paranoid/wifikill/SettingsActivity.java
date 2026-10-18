package me.paranoid.wifikill;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** App-specific preferences with persistent capture defaults and project link. */
public final class SettingsActivity extends Activity {
    private static final int BG=0xff0b1020,PANEL=0xff151f33,ACCENT=0xff8fc5ff,MUTED=0xffa8b5ca,WHITE=Color.WHITE;
    private static final String PREFS="wifitester_settings";
    private static final String KEY_AUTO_ROOT="auto_root_check",KEY_PACKET_LIMIT="capture_packet_limit",KEY_SNAPLEN="capture_snaplen";
    private static final String REPOSITORY_URL="https://github.com/KotobaWakusei/wifitester";
    private LinearLayout page;
    private TextView packetSummary,snaplenSummary;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20),dp(22),dp(20),dp(24));
        scroll.addView(page);

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=text("‹",32,ACCENT,true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round(PANEL,dp(14)));
        back.setPadding(dp(14),0,dp(14),dp(4));
        back.setOnClickListener(v->finish());
        top.addView(back);
        LinearLayout heading=new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(0,-2,1);
        hp.leftMargin=dp(14);
        top.addView(heading,hp);
        heading.addView(text("PREFERENCES",10,ACCENT,true));
        heading.addView(text("设置",27,WHITE,true),top(3));
        page.addView(top,bottom(22));
        page.addView(text("常规",16,WHITE,true),bottom(9));

        LinearLayout rootCard=card();
        LinearLayout rootRow=new LinearLayout(this);
        rootRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout rootLabels=new LinearLayout(this);
        rootLabels.setOrientation(LinearLayout.VERTICAL);
        rootLabels.addView(text("启动时自动检测 Root",15,WHITE,true));
        rootLabels.addView(text("关闭后不会自动触发 su 授权检查；仍可在首页手动检测。",12,MUTED,false),top(5));
        rootRow.addView(rootLabels,new LinearLayout.LayoutParams(0,-2,1));
        Switch rootSwitch=new Switch(this);
        rootSwitch.setChecked(prefs().getBoolean(KEY_AUTO_ROOT,true));
        rootSwitch.setOnCheckedChangeListener((button,checked)->prefs().edit().putBoolean(KEY_AUTO_ROOT,checked).apply());
        rootRow.addView(rootSwitch);
        rootCard.addView(rootRow);
        page.addView(rootCard,bottom(20));

        page.addView(text("被动抓包默认值",16,WHITE,true),bottom(9));
        LinearLayout capture=card();
        capture.addView(text("单次捕获帧数上限",15,WHITE,true));
        packetSummary=text("",12,MUTED,false);
        capture.addView(packetSummary,top(5));
        LinearLayout packetChoices=new LinearLayout(this);
        packetChoices.setOrientation(LinearLayout.HORIZONTAL);
        addChoice(packetChoices,"5,000",5000,KEY_PACKET_LIMIT);
        addChoice(packetChoices,"20,000",20000,KEY_PACKET_LIMIT);
        addChoice(packetChoices,"50,000",50000,KEY_PACKET_LIMIT);
        capture.addView(packetChoices,top(12));
        capture.addView(text("较小的上限有助于控制文件大小与存储占用。",12,MUTED,false),top(8));
        page.addView(capture,bottom(12));

        LinearLayout snap=card();
        snap.addView(text("每帧最大保存长度（snaplen）",15,WHITE,true));
        snaplenSummary=text("",12,MUTED,false);
        snap.addView(snaplenSummary,top(5));
        LinearLayout snapChoices=new LinearLayout(this);
        snapChoices.setOrientation(LinearLayout.HORIZONTAL);
        addChoice(snapChoices,"256 B",256,KEY_SNAPLEN);
        addChoice(snapChoices,"512 B",512,KEY_SNAPLEN);
        addChoice(snapChoices,"1024 B",1024,KEY_SNAPLEN);
        snap.addView(snapChoices,top(12));
        snap.addView(text("较大的值可能保存更多包内容，也会增加文件体积。",12,MUTED,false),top(8));
        page.addView(snap,bottom(20));
        updateSummaries();

        LinearLayout about=card();
        about.addView(text("关于 WiFiTester",16,WHITE,true));
        about.addView(text("通用无线能力诊断与被动抓包工具。监听模式取决于设备、驱动、内核和 Root 环境；工具存在不等于驱动支持。",13,MUTED,false),top(8));
        about.addView(text("版本 2.3.0 · Android",12,MUTED,false),top(8));
        page.addView(about,bottom(12));

        // Repository is deliberately the last visible item in the settings page.
        LinearLayout repo=card();
        repo.setOrientation(LinearLayout.VERTICAL);
        repo.addView(text("项目仓库",12,MUTED,true));
        repo.addView(text(REPOSITORY_URL,14,ACCENT,true),top(7));
        repo.addView(text("查看源代码、问题反馈与更新记录  ↗",12,MUTED,false),top(5));
        repo.setOnClickListener(v->openRepository());
        page.addView(repo);
        setContentView(scroll);
    }

    private void addChoice(LinearLayout row,String label,int value,String key){
        TextView choice=text(label,13,WHITE,true);
        choice.setGravity(Gravity.CENTER);
        choice.setPadding(dp(7),dp(11),dp(7),dp(11));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
        lp.rightMargin=dp(6);
        row.addView(choice,lp);
        choice.setOnClickListener(v->{
            prefs().edit().putInt(key,value).apply();
            updateSummaries();
            refreshChoiceStyles(row,key);
        });
        choice.setTag(key+":"+value);
        styleChoice(choice,key,value);
    }

    private void refreshChoiceStyles(LinearLayout row,String key){
        for(int i=0;i<row.getChildCount();i++){
            View child=row.getChildAt(i);
            if(child instanceof TextView){
                String tag=String.valueOf(child.getTag());
                if(tag.startsWith(key+":")){
                    try{styleChoice((TextView)child,key,Integer.parseInt(tag.substring(tag.indexOf(':')+1)));}
                    catch(NumberFormatException ignored){}
                }
            }
        }
    }

    private void styleChoice(TextView view,String key,int value){
        boolean selected=prefs().getInt(key,key.equals(KEY_PACKET_LIMIT)?50000:512)==value;
        GradientDrawable bg=round(selected?0xff203754:PANEL,dp(12));
        bg.setStroke(dp(1),selected?ACCENT:0xff34445d);
        view.setBackground(bg);
        view.setTextColor(selected?ACCENT:WHITE);
    }

    private void updateSummaries(){
        if(packetSummary!=null){
            int packets=prefs().getInt(KEY_PACKET_LIMIT,50000);
            packetSummary.setText("当前：最多 "+String.format("%,d",packets)+" 帧/次");
        }
        if(snaplenSummary!=null){
            int snaplen=prefs().getInt(KEY_SNAPLEN,512);
            snaplenSummary.setText("当前：每帧最多 "+snaplen+" 字节");
        }
    }

    private void openRepository(){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(REPOSITORY_URL)));}
        catch(Exception e){Toast.makeText(this,"无法打开浏览器",Toast.LENGTH_SHORT).show();}
    }

    private android.content.SharedPreferences prefs(){return getSharedPreferences(PREFS,MODE_PRIVATE);}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable bg=round(PANEL,dp(18));bg.setStroke(dp(1),0xff26344c);l.setBackground(bg);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams bottom(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(n);return p;}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(n);return p;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
