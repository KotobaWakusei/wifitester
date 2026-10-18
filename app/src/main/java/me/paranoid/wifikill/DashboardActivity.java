package me.paranoid.wifikill;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Main three-tab Wi-Fi workspace: system-style Wi-Fi, practical tools, and settings. */
public final class DashboardActivity extends Activity {
    private static final int BG=0xfff5f7fb, PANEL=Color.WHITE, INK=0xff172033;
    private static final int MUTED=0xff64748b, ACCENT=0xff1769e0, LINE=0xffdfe6ef;
    private static final int REQUEST_WIFI_PERMISSION=810;
    private static final String PREFS="wifitester_settings";
    private final List<ScanResult> scanResults=new ArrayList<>();
    private WifiManager wifi;
    private LinearLayout content, navigation;
    private TextView wifiState, connectedName, connectedDetails, rootWarning, scanSummary;
    private LinearLayout networkList;
    private int selectedTab=0;
    private volatile boolean rootCheckRunning=false;
    private Boolean rootAvailable=null;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.WHITE);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        wifi=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        buildShell();
        showTab(0);
        checkRootWarning();
        requestWifiPermissionIfNeeded();
    }

    private void buildShell() {
        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        navigation=new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(8),dp(7),dp(8),dp(7));
        navigation.setBackgroundColor(Color.WHITE);
        navigation.setElevation(dp(8));
        shell.addView(navigation,new LinearLayout.LayoutParams(-1,dp(66)));
        setContentView(shell);
        addNavItem("◉","Wi-Fi",0);
        addNavItem("⌘","无线工具",1);
        addNavItem("⚙","设置",2);
    }

    private void addNavItem(String symbol,String label,int tab) {
        LinearLayout item=new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0,dp(4),0,dp(3));
        TextView icon=text(symbol,21,tab==selectedTab?ACCENT:MUTED,true);
        icon.setGravity(Gravity.CENTER);
        TextView name=text(label,11,tab==selectedTab?ACCENT:MUTED,tab==selectedTab);
        item.addView(icon);
        item.addView(name,top(2));
        item.setOnClickListener(v->showTab(tab));
        navigation.addView(item,new LinearLayout.LayoutParams(0,-1,1));
        item.setTag(tab);
    }

    private void refreshNavigation() {
        for(int i=0;i<navigation.getChildCount();i++) {
            LinearLayout item=(LinearLayout)navigation.getChildAt(i);
            int tab=(Integer)item.getTag();
            TextView icon=(TextView)item.getChildAt(0), name=(TextView)item.getChildAt(1);
            int color=tab==selectedTab?ACCENT:MUTED;
            icon.setTextColor(color); name.setTextColor(color);
            name.setTypeface(Typeface.DEFAULT,tab==selectedTab?Typeface.BOLD:Typeface.NORMAL);
        }
    }

    private void showTab(int tab) {
        selectedTab=tab;
        refreshNavigation();
        content.removeAllViews();
        if(tab==0) buildWifiTab();
        else if(tab==1) buildToolsTab();
        else buildSettingsTab();
        animatePageIn();
        animateSelectedNavigation();
    }

    /** Lightweight native-property transitions; no extra animation dependency. */
    private void animatePageIn() {
        if(content==null||content.getChildCount()==0)return;
        View page=content.getChildAt(0);
        page.setAlpha(0f);
        page.setTranslationY(dp(8));
        page.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        if(page instanceof ScrollView) {
            View child=((ScrollView)page).getChildAt(0);
            if(child instanceof LinearLayout) {
                LinearLayout root=(LinearLayout)child;
                for(int i=0;i<root.getChildCount();i++) {
                    View item=root.getChildAt(i);
                    item.setAlpha(0f);
                    item.setTranslationY(dp(10));
                    item.animate().alpha(1f).translationY(0f)
                            .setStartDelay(Math.min(i*35,210)).setDuration(240)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            }
        }
    }

    private void animateSelectedNavigation() {
        if(navigation==null)return;
        for(int i=0;i<navigation.getChildCount();i++) {
            View item=navigation.getChildAt(i);
            if(item.getTag() instanceof Integer && ((Integer)item.getTag())==selectedTab) {
                item.setScaleX(0.96f); item.setScaleY(0.96f);
                item.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
            }
        }
    }

    private ScrollView page(String eyebrow,String title,String subtitle) {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(20),dp(18),dp(24));
        scroll.addView(root);
        root.addView(text(eyebrow.toUpperCase(Locale.ROOT),11,ACCENT,true));
        root.addView(text(title,28,INK,true),top(5));
        if(subtitle!=null&&!subtitle.isEmpty()) root.addView(text(subtitle,13,MUTED,false),top(5));
        content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        return scroll;
    }

    private LinearLayout pageRoot(ScrollView scroll) { return (LinearLayout)scroll.getChildAt(0); }

    private void buildWifiTab() {
        LinearLayout root=pageRoot(page("WIFITESTER  /  网络","Wi-Fi","查看当前连接、附近网络与无线链路信息。"));
        LinearLayout status=card();
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView symbol=text("◉",27,ACCENT,true);
        symbol.setGravity(Gravity.CENTER);
        symbol.setBackground(round(0xffe7f0ff,dp(16)));
        symbol.setPadding(dp(12),dp(9),dp(12),dp(9));
        row.addView(symbol);
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(12);
        row.addView(labels,lp);
        wifiState=text("正在读取 Wi-Fi 状态…",17,INK,true);
        labels.addView(wifiState);
        labels.addView(text("连接与开关由 Android 系统管理",12,MUTED,false),top(4));
        status.addView(row);
        Button systemWifi=button("打开系统 Wi-Fi 控制面板",ACCENT,Color.WHITE);
        systemWifi.setOnClickListener(v->openWifiPanel());
        status.addView(systemWifi,top(14));
        root.addView(status,bottom(12));

        rootWarning=text("",13,0xffa34d00,false);
        rootWarning.setPadding(dp(13),dp(11),dp(13),dp(11));
        rootWarning.setBackground(round(0xfffff0d9,dp(13)));
        root.addView(rootWarning,bottom(12));
        // Hidden unless Root really is unavailable.
        rootWarning.setVisibility(Boolean.FALSE.equals(rootAvailable)?View.VISIBLE:View.GONE);
        rootWarning.setText("⚠ 未获得 Root 权限。普通 Wi-Fi 信息仍可查看；监听模式、底层接口操作和部分抓包功能需要在 Root 管理器中授权。");

        LinearLayout connected=card();
        connected.addView(text("当前连接",16,INK,true));
        connectedName=text("正在读取…",19,INK,true);
        connected.addView(connectedName,top(9));
        connectedDetails=text("",13,MUTED,false);
        connectedDetails.setTextIsSelectable(true);
        connected.addView(connectedDetails,top(6));
        Button details=button("刷新连接信息",0xffeaf1fb,ACCENT);
        details.setOnClickListener(v->refreshWifiInfo());
        connected.addView(details,top(12));
        root.addView(connected,bottom(12));

        LinearLayout nearby=card();
        LinearLayout heading=new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("附近的网络",16,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button refresh=button("扫描",0xffeaf1fb,ACCENT);
        refresh.setOnClickListener(v->scanWifi());
        heading.addView(refresh);
        nearby.addView(heading);
        scanSummary=text("等待扫描。Android 可能限制扫描频率。",12,MUTED,false);
        nearby.addView(scanSummary,top(6));
        networkList=new LinearLayout(this);networkList.setOrientation(LinearLayout.VERTICAL);
        nearby.addView(networkList,top(8));
        root.addView(nearby,bottom(12));

        LinearLayout quick=card();
        quick.addView(text("快捷操作",16,INK,true));
        addAction(quick,"系统网络设置","连接/切换网络、管理已保存网络",this::openWifiSettings);
        addAction(quick,"无线诊断工具","监听、抓包、接口与频道工作台",()->showTab(1));
        root.addView(quick);
        refreshWifiInfo();
        renderNetworkList();
    }

    private void buildToolsTab() {
        LinearLayout root=pageRoot(page("WIFITESTER  /  工具","无线工具","打开实际工具工作台。工具会使用 APK 内置的 tcpdump，并在需要时调用设备权限。"));
        addTool(root,"01","被动监听与抓包","选择无线接口和频道，保存 PCAP；支持停止、恢复和导出诊断报告。",
                "打开监听工作台",()->startActivity(new Intent(this,MonitorModeActivity.class)));
        addTool(root,"02","抓包文件分析","读取 PCAP/PCAPNG 元数据与数据包摘要；不修改原始文件。",
                "打开抓包分析",()->startActivity(new Intent(this,AircrackToolkitActivity.class)));
        addTool(root,"03","断开帧分析","从已有抓包中筛选 802.11 deauthentication / disassociation 管理帧，帮助排查异常掉线。",
                "分析断开帧",()->startActivity(new Intent(this,FrameAnalysisActivity.class)));
        addTool(root,"04","附近网络与信号","查看 SSID、BSSID、信号强度、频率、频道和安全类型。",
                "查看 Wi-Fi 扫描",()->startActivity(new Intent(this,WiFiAuditActivity.class)));
        LinearLayout bundled=card();
        bundled.addView(text("APK 内置工具",16,INK,true));
        bundled.addView(text("tcpdump（ARM64 构建时打包）用于被动抓包与离线过滤；运行时释放到应用私有目录，不要求用户另行安装。其他命令依设备/驱动能力而定。",13,MUTED,false),top(7));
        root.addView(bundled,bottom(12));
        LinearLayout note=card();
        note.addView(text("使用边界",15,INK,true));
        note.addView(text("抓包与断开帧分析是只读诊断。此版本不提供向第三方设备发送断连帧、无线注入或口令破解的一键操作。仅在自有或明确授权的实验网络中使用。",12,MUTED,false),top(6));
        root.addView(note);
    }

    private void buildSettingsTab() {
        LinearLayout root=pageRoot(page("WIFITESTER  /  配置","设置","配置抓包默认值与应用行为。"));
        LinearLayout capture=card();
        capture.addView(text("抓包默认值",16,INK,true));
        capture.addView(text("单次最大帧数",13,MUTED,false),top(10));
        addSettingChoices(capture,new String[]{"5,000","20,000","50,000"},new int[]{5000,20000,50000},"capture_packet_limit",50000);
        capture.addView(text("每帧最大保存长度（snaplen）",13,MUTED,false),top(14));
        addSettingChoices(capture,new String[]{"256 B","512 B","1024 B"},new int[]{256,512,1024},"capture_snaplen",512);
        root.addView(capture,bottom(12));

        LinearLayout permissions=card();
        permissions.addView(text("Root 权限",16,INK,true));
        permissions.addView(text("只有检测到当前应用没有 Root 权限时，首页才会显示警告。Root 授权状态由系统 Root 管理器决定。",13,MUTED,false),top(6));
        Button check=button("重新检查 Root",0xffeaf1fb,ACCENT);
        check.setOnClickListener(v->checkRootWarning());
        permissions.addView(check,top(10));
        root.addView(permissions,bottom(12));

        LinearLayout about=card();
        about.addView(text("关于 WIFITESTER",16,INK,true));
        about.addView(text("Android 无线诊断工作台 · 版本 2.4.0",13,MUTED,false),top(6));
        Button repository=button("打开 GitHub 仓库",0xffeaf1fb,ACCENT);
        repository.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/KotobaWakusei/wifitester")));}catch(Exception e){Toast.makeText(this,"无法打开浏览器",Toast.LENGTH_SHORT).show();}});
        about.addView(repository,top(10));
        root.addView(about);
    }

    private void addSettingChoices(LinearLayout parent,String[] labels,int[] values,String key,int defaultValue) {
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<values.length;i++) {
            final int value=values[i];
            TextView choice=text(labels[i],13,INK,true);
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(4),dp(11),dp(4),dp(11));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.rightMargin=dp(6);
            row.addView(choice,p);
            styleChoice(choice,key,value,defaultValue);
            choice.setOnClickListener(v->{getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(key,value).apply();for(int j=0;j<row.getChildCount();j++)styleChoice((TextView)row.getChildAt(j),key,values[j],defaultValue);});
        }
        parent.addView(row,top(7));
    }

    private void styleChoice(TextView choice,String key,int value,int defaultValue) {
        boolean active=getSharedPreferences(PREFS,MODE_PRIVATE).getInt(key,defaultValue)==value;
        choice.setBackground(round(active?0xffe7f0ff:0xfff1f4f8,dp(11)));
        choice.setTextColor(active?ACCENT:INK);
    }

    private void addTool(LinearLayout root,String index,String title,String description,String action,Runnable click) {
        LinearLayout c=card();
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge=text(index,12,ACCENT,true);badge.setPadding(dp(9),dp(7),dp(9),dp(7));badge.setBackground(round(0xffe7f0ff,dp(9)));
        heading.addView(badge);
        TextView name=text(title,17,INK,true);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=dp(10);heading.addView(name,np);
        c.addView(heading);
        c.addView(text(description,13,MUTED,false),top(8));
        Button open=button(action,ACCENT,Color.WHITE);open.setOnClickListener(v->click.run());
        c.addView(open,top(12));root.addView(c,bottom(11));
    }

    private void addAction(LinearLayout parent,String title,String detail,Runnable action) {
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,14,INK,true));labels.addView(text(detail,12,MUTED,false),top(3));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("›",28,ACCENT,true);row.addView(arrow);
        row.setPadding(0,dp(12),0,dp(12));row.setOnClickListener(v->action.run());
        parent.addView(row);
        View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
    }

    private void refreshWifiInfo() {
        if(wifi==null){wifiState.setText("此设备没有 Wi-Fi 服务");connectedName.setText("不可用");connectedDetails.setText("");return;}
        boolean enabled=wifi.isWifiEnabled();
        wifiState.setText(enabled?"Wi-Fi 已开启":"Wi-Fi 已关闭");
        wifiState.setTextColor(enabled?0xff15803d:0xffb42318);
        WifiInfo info=null;
        try{info=wifi.getConnectionInfo();}catch(Exception ignored){}
        if(!enabled||info==null||info.getNetworkId()==-1||isBlank(info.getSSID())) {
            connectedName.setText("未连接到 Wi-Fi");
            connectedDetails.setText("打开系统 Wi-Fi 控制面板连接网络。");
            return;
        }
        String ssid=cleanSsid(info.getSSID());
        connectedName.setText(ssid);
        String bssid=safe(info.getBSSID());
        String freq=info.getFrequency()>0?info.getFrequency()+" MHz ("+frequencyBand(info.getFrequency())+")":"未知";
        String ip=ipToString(info.getIpAddress());
        String mac=safe(info.getMacAddress());
        connectedDetails.setText("BSSID： "+bssid+"\n信号： "+info.getRssi()+" dBm\n链路速率： "+info.getLinkSpeed()+" Mbps\n频率： "+freq+"\nIP： "+ip+"\n设备 MAC： "+mac);
    }

    private void requestWifiPermissionIfNeeded() {
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQUEST_WIFI_PERMISSION);
        } else scanWifi();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==REQUEST_WIFI_PERMISSION) {
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED) scanWifi();
            else if(scanSummary!=null) scanSummary.setText("未授予附近网络扫描所需的位置权限。当前连接信息仍可查看。");
        }
    }

    private void scanWifi() {
        if(wifi==null||networkList==null)return;
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestWifiPermissionIfNeeded();return;
        }
        if(!wifi.isWifiEnabled()){scanSummary.setText("Wi-Fi 已关闭，请先打开系统控制面板。");renderNetworkList();return;}
        scanSummary.setText("正在请求系统扫描…");
        try {
            boolean started=wifi.startScan();
            List<ScanResult> results=wifi.getScanResults();
            scanResults.clear();if(results!=null)scanResults.addAll(results);
            Collections.sort(scanResults,(a,b)->Integer.compare(b.level,a.level));
            scanSummary.setText((started?"已请求刷新；":"显示系统缓存；")+"发现 "+scanResults.size()+" 个网络");
            renderNetworkList();
        } catch(SecurityException e) {
            scanSummary.setText("系统拒绝扫描。请授予附近网络权限并打开系统定位服务。");
        } catch(Exception e) {
            scanSummary.setText("扫描失败："+e.getClass().getSimpleName());
        }
    }

    private void renderNetworkList() {
        if(networkList==null)return;
        networkList.removeAllViews();
        if(scanResults.isEmpty()) {
            TextView empty=text("暂时没有扫描结果。点按“扫描”或打开系统 Wi-Fi 设置。",13,MUTED,false);
            empty.setPadding(0,dp(12),0,dp(12));networkList.addView(empty);return;
        }
        for(ScanResult result:scanResults) {
            LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(0,dp(10),0,dp(10));
            LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
            String ssid=result.SSID==null||result.SSID.isEmpty()?"（隐藏 SSID）":result.SSID;
            labels.addView(text(ssid,15,INK,true));
            labels.addView(text(safe(result.BSSID)+"  ·  "+result.level+" dBm  ·  "+result.frequency+" MHz",11,MUTED,false),top(4));
            labels.addView(text(securityType(result.capabilities)+"  ·  "+frequencyBand(result.frequency),11,MUTED,false),top(3));
            item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            Button connect=button("连接",0xffe7f0ff,ACCENT);
            connect.setOnClickListener(v->openWifiPanel());
            item.addView(connect);
            item.setOnClickListener(v->showNetworkDetails(result));
            item.setAlpha(0f);
            item.setTranslationY(dp(6));
            networkList.addView(item);
            item.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(networkList.getChildCount()*24,180)).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            View line=new View(this);line.setBackgroundColor(LINE);networkList.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }

    private void showNetworkDetails(ScanResult r) {
        String body="SSID： "+(isBlank(r.SSID)?"（隐藏）":r.SSID)+"\nBSSID： "+safe(r.BSSID)+"\n信号： "+r.level+" dBm\n频率： "+r.frequency+" MHz\n频道： "+frequencyChannel(r.frequency)+"\n安全： "+securityType(r.capabilities)+"\n能力： "+r.capabilities;
        new AlertDialog.Builder(this).setTitle(isBlank(r.SSID)?"网络详情":r.SSID).setMessage(body).setPositiveButton("连接/切换",(d,w)->openWifiPanel()).setNegativeButton("关闭",null).show();
    }

    private void openWifiPanel() {
        try {
            if(Build.VERSION.SDK_INT>=29) startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            else openWifiSettings();
        } catch(Exception e) {openWifiSettings();}
    }
    private void openWifiSettings() {
        try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}
        catch(Exception e){Toast.makeText(this,"无法打开系统 Wi-Fi 设置",Toast.LENGTH_SHORT).show();}
    }

    private void checkRootWarning() {
        if(rootCheckRunning)return;
        rootCheckRunning=true;
        new Thread(()->{
            boolean granted=false;
            Process p=null;
            try {
                p=new ProcessBuilder("su","-c","id").redirectErrorStream(true).start();
                java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder out=new StringBuilder();String line;
                while((line=reader.readLine())!=null)out.append(line).append('\n');
                granted=p.waitFor()==0&&out.toString().matches("(?s).*\\buid=0(?:\\(|\\b).*");
            } catch(Exception ignored) {granted=false;}
            finally{if(p!=null&&p.isAlive())p.destroy();}
            final boolean hasRoot=granted;
            runOnUiThread(()->{
                rootCheckRunning=false;
                rootAvailable=hasRoot;
                if(rootWarning!=null)rootWarning.setVisibility(hasRoot?View.GONE:View.VISIBLE);
            });
        },"wifitester-root-check").start();
    }

    @Override protected void onResume() {
        super.onResume();
        if(selectedTab==0){refreshWifiInfo();scanWifi();}
    }

    private String cleanSsid(String value) {if(value==null)return "未知";return value.replace("\"","");}
    private boolean isBlank(String s){return s==null||s.trim().isEmpty()||"<unknown ssid>".equalsIgnoreCase(s);}
    private String safe(String s){return s==null||s.isEmpty()?"未知":s;}
    private String frequencyBand(int f){if(f>=5925)return "6 GHz";if(f>=4900)return "5 GHz";return f>0?"2.4 GHz":"频段未知";}
    private int frequencyChannel(int f){if(f==2484)return 14;if(f>=2412&&f<=2472)return (f-2407)/5;if(f>=5000&&f<5925)return (f-5000)/5;if(f>=5955)return (f-5950)/5;return -1;}
    private String securityType(String caps){String c=caps==null?"":caps.toUpperCase(Locale.ROOT);if(c.contains("WPA3"))return "WPA3";if(c.contains("WPA2"))return "WPA2";if(c.contains("WPA"))return "WPA";if(c.contains("EAP"))return "802.1X";if(c.contains("WEP"))return "WEP";return "开放网络";}
    private String ipToString(int ip){if(ip==0)return "未知";return (ip&255)+"."+((ip>>8)&255)+"."+((ip>>16)&255)+"."+((ip>>24)&255);}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(15),dp(15),dp(15),dp(15));c.setBackground(round(PANEL,dp(17)));return c;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t;}
    private Button button(String s,int bg,int fg){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);
        b.setTextColor(fg);b.setMinHeight(dp(42));b.setPadding(dp(12),0,dp(12),0);
        b.setBackground(round(bg,dp(12)));
        b.setOnTouchListener((view,event)->{
            if(event.getAction()==android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(75).start();
            } else if(event.getAction()==android.view.MotionEvent.ACTION_UP
                    ||event.getAction()==android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
            return false;
        });
        return b;
    }
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(n);return p;}
    private LinearLayout.LayoutParams bottom(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(n);return p;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
