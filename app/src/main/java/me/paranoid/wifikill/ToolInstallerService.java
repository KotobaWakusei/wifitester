package me.paranoid.wifikill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.system.Os;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台下载并安装工具包的服务。
 *
 * <p>每个工具包由 {@code config.json} 的 {@code download_url} 指定一个
 * tar.gz 压缩包，解压后把可执行文件复制到 {@code files/toolchain/bin}，
 * 动态库复制到 {@code files/toolchain/lib}。只有全部文件都成功就位后，
 * 工具包才会被标记为已安装；失败时会清理已解压的文件，不会留下
 * 让界面误以为可用的空文件。</p>
 */
public class ToolInstallerService extends Service {
    private static final String TAG = "ToolInstallerService";
    private static final String CHANNEL_ID = "tool_download";
    private static final int NOTIFICATION_ID = 1001;
    static final String ACTION_PROGRESS = "me.paranoid.wifikill.TOOL_PACKAGE_PROGRESS";
    private static final String EXTRA_PACKAGE_ID = "package_id";
    private static final String EXTRA_PACKAGE_NAME = "package_name";
    private static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 400L * 1024 * 1024;

    private ExecutorService executor;
    private volatile boolean isRunning = false;
    /** The package currently being downloaded, used for rollback on destroy. */
    private volatile ToolPackageManager.ToolPackage currentPackage;

    public static void start(Context context, ToolPackageManager.ToolPackage pkg) {
        Intent intent = new Intent(context, ToolInstallerService.class);
        intent.putExtra(EXTRA_PACKAGE_ID, pkg.id);
        intent.putExtra(EXTRA_PACKAGE_NAME, pkg.name);
        context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ToolInstallerService.class);
        context.stopService(intent);
    }

    /** Notifies open screens about progress and the final result. */
    static void notifyProgress(Context context, String packageId, boolean running,
                               boolean success, String message) {
        Intent intent = new Intent(ACTION_PROGRESS);
        intent.setPackage(context.getPackageName());
        intent.putExtra("package_id", packageId);
        intent.putExtra("running", running);
        intent.putExtra("success", success);
        intent.putExtra("message", message);
        context.sendBroadcast(intent);
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

        String packageId = intent.getStringExtra(EXTRA_PACKAGE_ID);
        String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);

        if (packageId == null || packageName == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            try {
                startForeground(NOTIFICATION_ID, createNotification(packageName, 0));
            } catch (RuntimeException e) {
                // A missing foreground permission must not take the app down;
                // the download still runs while the service is alive.
                Log.w(TAG, "startForeground failed, continuing without notification", e);
            }
            notifyProgress(this, packageId, true, false, getString(R.string.tool_download_starting));
            executor.execute(() -> downloadPackage(packageId, packageName));
        }

        // Not sticky: re-creating the service with a null Intent would start a
        // ghost download. The download is resumed by the user from the card,
        // which shows the failure reason.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (executor != null) {
            executor.shutdown();
        }
        // A destroyed service leaves whatever it extracted so far behind. Those
        // files would make a later "installed" check disagree with the flag.
        ToolPackageManager.ToolPackage interrupted = currentPackage;
        if (interrupted != null) {
            currentPackage = null;
            // Only a preinstalled package is untouched: its tools ship with the APK.
            if (!interrupted.preInstalled) {
                new ToolPackageManager(this).uninstallPackage(interrupted);
            }
            notifyProgress(this, interrupted.id, false, false,
                    getString(R.string.tool_download_interrupted));
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void downloadPackage(String packageId, String packageName) {
        boolean success = false;
        String message = getString(R.string.tool_download_failed, "");
        try {
            ToolPackageManager toolManager = new ToolPackageManager(this);
            ToolPackageManager.ToolPackage pkg = getPackageById(packageId);

            currentPackage = pkg;
            if (pkg == null) {
                message = getString(R.string.tool_package_unknown);
            } else if (toolManager.isPackageComplete(pkg)) {
                toolManager.installPackage(pkg);
                success = true;
                message = getString(R.string.tool_download_done);
            } else {
                String downloadUrl = toolManager.getDownloadUrl(pkg);
                if (downloadUrl.isEmpty()) {
                    message = getString(R.string.tool_download_unavailable, pkg.name);
                } else {
                    File archive = download(pkg, packageName, downloadUrl);
                    boolean installed;
                    try {
                        install(pkg, archive);
                        installed = toolManager.isPackageComplete(pkg);
                    } finally {
                        archive.delete();
                    }
                    if (installed) {
                        toolManager.installPackage(pkg);
                        success = true;
                        message = getString(R.string.tool_download_done);
                    } else {
                        // A partially extracted package would report tools that
                        // cannot run; drop whatever arrived.
                        toolManager.uninstallPackage(pkg);
                        message = getString(R.string.tool_download_failed,
                                getString(R.string.tool_extract_incomplete));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            message = getString(R.string.tool_download_failed, detail);
            // An exception can hit between two extracted tools (interrupted
            // service, unwritable directory); never leave a half package.
            ToolPackageManager.ToolPackage pkg = getPackageById(packageId);
            if (pkg != null && !pkg.preInstalled) {
                new ToolPackageManager(this).uninstallPackage(pkg);
            }
        } finally {
            currentPackage = null;
            notifyProgress(this, packageId, false, success, message);
            notifyDownloadComplete(packageName, success, message);
            isRunning = false;
            stopSelf();
        }
    }

    /** Downloads the archive into the cache directory and reports progress. */
    private File download(ToolPackageManager.ToolPackage pkg, String packageName, String downloadUrl)
            throws IOException {
        File archive = new File(getCacheDir(), pkg.id + ".tar.gz");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "WiFiTester");
            connection.connect();
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new IOException(getString(R.string.tool_download_not_found, code));
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException(getString(R.string.tool_download_http, code));
            }
            long total = connection.getContentLengthLong();
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(archive)) {
                byte[] buffer = new byte[16384];
                long copied = 0;
                int lastProgress = -1;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    copied += n;
                    if (copied > MAX_ARCHIVE_BYTES) {
                        throw new IOException(getString(R.string.tool_download_too_large,
                                ToolPackageManager.formatSize(MAX_ARCHIVE_BYTES)));
                    }
                    out.write(buffer, 0, n);
                    if (total > 0) {
                        int progress = (int) Math.min(100, copied * 100 / total);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            updateNotification(packageName, progress);
                        }
                    }
                }
                out.getFD().sync();
            }
            return archive;
        } catch (java.net.UnknownHostException | java.net.ConnectException e) {
            archive.delete();
            throw new IOException(getString(R.string.tool_download_no_network));
        } catch (java.net.SocketTimeoutException e) {
            archive.delete();
            throw new IOException(getString(R.string.tool_download_no_network));
        } catch (IOException e) {
            archive.delete();
            throw e;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Extracts the tools of the package into {@code files/toolchain/bin} and any
     * {@code *.so} libraries into {@code files/toolchain/lib}.
     */
    private void install(ToolPackageManager.ToolPackage pkg, File archive) throws Exception {
        File toolchain = new File(getFilesDir(), "toolchain");
        File bin = new File(toolchain, "bin");
        if (!bin.mkdirs() && !bin.isDirectory()) {
            throw new IOException(getString(R.string.bundled_tool_dir_failed, bin));
        }
        File lib = new File(toolchain, "lib");
        if (!lib.mkdirs() && !lib.isDirectory()) {
            throw new IOException(getString(R.string.bundled_lib_dir_failed, lib));
        }

        List<String> members = listMembers(archive);
        if (members.isEmpty()) {
            throw new IOException(getString(R.string.tool_extract_incomplete));
        }
        for (String tool : pkg.tools) {
            String member = findMember(members, tool);
            if (member == null) {
                throw new IOException(getString(R.string.tool_missing_in_archive, tool));
            }
            extract(archive, member, new File(bin, tool), true);
        }
        for (String member : members) {
            String name = member.substring(member.lastIndexOf('/') + 1);
            if (name.matches("[A-Za-z0-9_.+-]+\\.so(\\.[0-9]+)*")) {
                extract(archive, member, new File(lib, name), false);
            }
        }
    }

    private void extract(File archive, String member, File target, boolean executable) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".new");
        try {
            runTar(archive, member, temp);
            if (!temp.isFile() || temp.length() == 0) {
                throw new IOException(getString(R.string.tool_extract_empty, target.getName()));
            }
            if (target.exists() && !target.delete()) {
                throw new IOException(getString(R.string.bundled_replace_failed));
            }
            if (!temp.renameTo(target)) {
                throw new IOException(getString(R.string.bundled_commit_failed));
            }
            if (executable) {
                Os.chmod(target.getAbsolutePath(), 0755);
            }
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private List<String> listMembers(File archive) throws Exception {
        String[] lines = runTar(archive, null, null);
        ArrayList<String> members = new ArrayList<>();
        for (String line : lines) {
            String member = line.trim();
            // Only plain relative paths; never trust an archive with "..".
            if (member.isEmpty() || member.startsWith("/") || member.contains("..")) continue;
            members.add(member);
        }
        return members;
    }

    /** Finds the archive entry for a tool; a top level entry wins over a nested one. */
    private static String findMember(List<String> members, String tool) {
        String fallback = null;
        for (String member : members) {
            String name = member.substring(member.lastIndexOf('/') + 1);
            if (!name.equals(tool)) continue;
            if (member.indexOf('/') < 0) return member;
            if (fallback == null) fallback = member;
        }
        return fallback;
    }

    /**
     * Lists the archive contents when {@code output} is null, otherwise extracts
     * the single {@code member} into {@code output}.
     */
    private String[] runTar(File archive, @Nullable String member, @Nullable File output) throws Exception {
        String tar = resolveTar();
        if (tar == null) {
            throw new IOException(getString(R.string.tool_extract_no_tar));
        }
        ArrayList<String> command = new ArrayList<>();
        command.add(tar);
        if (output == null) {
            command.add("-tzf");
        } else {
            // -O writes the member to stdout so the archive layout is irrelevant.
            command.add("-O");
            command.add("-xzf");
        }
        command.add(archive.getAbsolutePath());
        if (output != null) {
            command.add(member);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();
        process.getOutputStream().close();
        if (output != null) {
            long copied = 0;
            try (InputStream in = process.getInputStream();
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    copied += n;
                    if (copied > MAX_EXTRACTED_BYTES) {
                        throw new IOException(getString(R.string.tool_extract_too_large,
                                ToolPackageManager.formatSize(MAX_EXTRACTED_BYTES)));
                    }
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException(getString(R.string.tool_extract_failed, exit));
            }
            return new String[0];
        }
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) text.write(buffer, 0, n);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(getString(R.string.tool_extract_failed, exit));
        }
        return text.toString("UTF-8").split("\\r?\\n");
    }

    /** Resolves a tar implementation usable from the app sandbox. */
    @Nullable
    private String resolveTar() {
        String[] candidates = {
                new BundledToolchain(this).getBinDir() + "/tar",
                "/system/bin/tar",
                "/system/xbin/tar",
                "/system/bin/toybox"
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
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
                    getString(R.string.tool_download_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.tool_download_channel_desc));

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String packageName, int progress) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.tool_downloading, packageName))
                .setContentText(progress > 0
                        ? getString(R.string.tool_download_progress, progress)
                        : getString(R.string.tool_download_starting))
                .setSmallIcon(R.drawable.ic_nav_wifi)
                .setProgress(100, progress, progress <= 0)
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
                .setContentTitle(getString(success ? R.string.tool_download_complete
                        : R.string.tool_download_failed_title))
                .setContentText(packageName + ": " + message)
                .setSmallIcon(R.drawable.ic_nav_wifi)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID + 1, notification);
        }
    }
}
