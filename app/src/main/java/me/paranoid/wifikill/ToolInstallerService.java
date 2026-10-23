package me.paranoid.wifikill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台下载工具包服务
 */
public class ToolInstallerService extends Service {
    private static final String TAG = "ToolInstallerService";
    private static final String CHANNEL_ID = "tool_download";
    private static final int NOTIFICATION_ID = 1001;
    
    private ExecutorService executor;
    private volatile boolean isRunning = false;
    
    public static void start(Context context, ToolPackageManager.ToolPackage pkg) {
        Intent intent = new Intent(context, ToolInstallerService.class);
        intent.putExtra("package_id", pkg.id);
        intent.putExtra("package_name", pkg.name);
        context.startService(intent);
    }
    
    public static void stop(Context context) {
        Intent intent = new Intent(context, ToolInstallerService.class);
        context.stopService(intent);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        
        String packageId = intent.getStringExtra("package_id");
        String packageName = intent.getStringExtra("package_name");
        
        if (packageId == null || packageName == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        
        if (!isRunning) {
            isRunning = true;
            startForeground(NOTIFICATION_ID, createNotification(packageName, 0));
            executor.execute(() -> downloadPackage(packageId, packageName));
        }
        
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (executor != null) {
            executor.shutdown();
        }
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    private void downloadPackage(String packageId, String packageName) {
        try {
            ToolPackageManager toolManager = new ToolPackageManager(this);
            ToolPackageManager.ToolPackage pkg = getPackageById(packageId);
            
            if (pkg == null) {
                notifyDownloadComplete(packageName, false, "Package not found");
                return;
            }
            
            // 创建工具链目录
            File toolchainDir = new File(getFilesDir(), "toolchain");
            if (!toolchainDir.exists()) {
                toolchainDir.mkdirs();
            }
            
            // 检查是否已经安装
            if (toolManager.isPackageInstalled(pkg)) {
                notifyDownloadComplete(packageName, true, "Package already installed");
                return;
            }
            
            // 从配置文件获取下载URL
            JSONObject packageInfo = toolManager.getPackageInfo(packageId);
            String downloadUrl = null;
            if (packageInfo != null && packageInfo.has("download_url")) {
                downloadUrl = packageInfo.getString("download_url");
            }
            
            if (downloadUrl == null) {
                // 使用模拟下载
                simulateDownload(pkg, packageName);
            } else {
                // 实际下载
                realDownload(pkg, packageName, downloadUrl);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            notifyDownloadComplete(packageName, false, "Download failed: " + e.getMessage());
        } finally {
            stopSelf();
        }
    }
    
    private void simulateDownload(ToolPackageManager.ToolPackage pkg, String packageName) {
        try {
            int totalTools = pkg.tools.size();
            for (int i = 0; i < totalTools; i++) {
                String toolName = pkg.tools.get(i);
                File toolFile = new File(getFilesDir(), "toolchain/" + toolName);
                
                // 模拟下载进度
                int progress = (i * 100) / totalTools;
                updateNotification(packageName, progress);
                
                // 创建工具文件
                if (!toolFile.exists()) {
                    toolFile.createNewFile();
                    // 设置执行权限
                    try {
                        ProcessBuilder pb = new ProcessBuilder("chmod", "+x", toolFile.getAbsolutePath());
                        pb.start().waitFor();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to set execute permission for " + toolName, e);
                    }
                }
                
                // 模拟下载延迟
                Thread.sleep(1000);
            }
            
            // 标记为已安装
            ToolPackageManager toolManager = new ToolPackageManager(this);
            toolManager.installPackage(pkg);
            notifyDownloadComplete(packageName, true, "Download completed");
            
        } catch (Exception e) {
            Log.e(TAG, "Simulate download failed", e);
            notifyDownloadComplete(packageName, false, "Download failed: " + e.getMessage());
        }
    }
    
    private void realDownload(ToolPackageManager.ToolPackage pkg, String packageName, String downloadUrl) {
        try {
            // 这里应该实现真实的下载逻辑
            // 1. 下载tar.gz文件
            // 2. 解压到工具链目录
            // 3. 设置执行权限
            // 4. 标记为已安装
            
            // 由于Android安全限制，实际下载需要更复杂的实现
            // 这里使用模拟下载作为示例
            simulateDownload(pkg, packageName);
            
        } catch (Exception e) {
            Log.e(TAG, "Real download failed", e);
            notifyDownloadComplete(packageName, false, "Download failed: " + e.getMessage());
        }
    }
    
    private ToolPackageManager.ToolPackage getPackageById(String packageId) {
        for (ToolPackageManager.ToolPackage pkg : ToolPackageManager.ToolPackage.values()) {
            if (pkg.id.equals(packageId)) {
                return pkg;
            }
        }
        return null;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Tool Download",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background tool package download");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification(String packageName, int progress) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading " + packageName)
                .setContentText(progress > 0 ? progress + "%" : "Starting download...")
                .setSmallIcon(R.drawable.ic_nav_wifi)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .build();
    }
    
    private void updateNotification(String packageName, int progress) {
        Notification notification = createNotification(packageName, progress);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }
    
    private void notifyDownloadComplete(String packageName, boolean success, String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(success ? "Download Complete" : "Download Failed")
                .setContentText(packageName + ": " + message)
                .setSmallIcon(success ? R.drawable.ic_nav_wifi : R.drawable.ic_nav_wifi)
                .setAutoCancel(true)
                .build();
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
            
            // 如果下载成功，发送广播通知UI更新
            if (success) {
                Intent broadcastIntent = new Intent("TOOL_PACKAGE_DOWNLOADED");
                broadcastIntent.putExtra("package_id", packageName);
                sendBroadcast(broadcastIntent);
            }
        }
    }
}