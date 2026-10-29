package me.paranoid.wifikill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Keeps a passive capture alive while the monitor workbench is not in front.
 *
 * <p>The monitor UI used to stop the capture from {@code onDestroy}, so
 * rotating the screen, leaving the app or letting the system reclaim the
 * activity silently ended the session. The capture process now belongs to this
 * foreground service instead: it survives the activity, keeps a persistent
 * notification with the running state and offers a stop button from that
 * notification.</p>
 *
 * <p>The service owns no wireless commands. Restoring the interface and
 * {@code con_mode} stays in the backend scripts, because only they know the
 * original state; the activity asks for that restore when the stop comes from
 * the UI, and a notification stop is finished by the activity on its next
 * launch.</p>
 */
public final class MonitorCaptureService extends Service {

    static final String ACTION_START = "me.paranoid.wifikill.MONITOR_CAPTURE_START";
    static final String ACTION_STOP = "me.paranoid.wifikill.MONITOR_CAPTURE_STOP";
    /** Relayed to a visible activity whenever the session state changes. */
    static final String ACTION_STATE = "me.paranoid.wifikill.MONITOR_CAPTURE_STATE";

    static final String EXTRA_INTERFACE = "interface";
    static final String EXTRA_CHANNEL = "channel";
    static final String EXTRA_PACKET_LIMIT = "packet_limit";
    static final String EXTRA_SNAPLEN = "snaplen";
    static final String EXTRA_TARGET = "target";
    static final String EXTRA_PID = "pid";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_LINE = "line";
    static final String EXTRA_EXIT = "exit";
    /** Channel-hopping state and configuration. */
    static final String EXTRA_HOPPING = "hopping";
    static final String EXTRA_HOP_INTERVAL = "hop_interval";
    static final String EXTRA_HOP_CHANNELS = "hop_channels";
    /** Set on the activity intent so it knows a session has to be restored. */
    static final String EXTRA_RESTORE_PENDING = "restore_pending";

    private static final String CHANNEL_ID = "monitor_capture";
    private static final int NOTIFICATION_ID = 2001;
    private static final long HEARTBEAT_MS = 5000L;

    /** Binder handed to the activity; it owns every state transition. */
    interface Session {
        /** Starts a capture; false when the request was refused. */
        boolean start(String interfaceName, String channel, int frameLimit, int snaplen,
                      String targetPath, String hopChannels, int hopIntervalMs, boolean hopping);

        /** Enables or disables channel hopping; false when it is unavailable. */
        boolean setChannelHopping(boolean enabled);

        /** True while the hopping loop is retuning the interface. */
        boolean isChannelHopping();

        /** Stops the capture and closes the file. */
        void stop();

        /** Current state, safe to read from the UI thread. */
        State state();

        /** Registers a listener; it is called on the main thread. */
        void setListener(Runnable listener);
    }

    /** Intent that binds to (and creates) the session service. */
    static Intent intent(Context context) {
        return new Intent(context, MonitorCaptureService.class);
    }

    /** Immutable view of the current session for the activity. */
    static final class State {
        final boolean running;
        final boolean hopping;
        final String interfaceName;
        final String channel;
        final String targetPath;
        final long pid;

        State(boolean running, boolean hopping, String interfaceName, String channel,
              String targetPath, long pid) {
            this.running = running;
            this.hopping = hopping;
            this.interfaceName = interfaceName == null ? "" : interfaceName;
            this.channel = channel == null ? "" : channel;
            this.targetPath = targetPath == null ? "" : targetPath;
            this.pid = pid;
        }
    }

    private static final State IDLE = new State(false, false, "", "", "", -1L);
    private static volatile MonitorCaptureService instance;
    /** Bumped per start so a stale session from an earlier test/run cannot match. */
    private static final java.util.concurrent.atomic.AtomicLong SESSION_ID =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LIVE_SESSION =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile long sessionId;

    private volatile String interfaceName = "";
    private volatile String channel = "";
    private volatile String targetPath = "";
    private volatile long pid = -1L;
    private volatile int frameLimit;
    private volatile int snaplen;
    private volatile boolean running;
    private volatile Process capture;
    /** Channel-hopping loop; runs beside the capture and dies with the service. */
    private volatile boolean hopping;
    private volatile int hopIntervalMs = 2000;
    /** Each entry is "channelNumber,frequencyMHz". */
    private volatile String[] hopChannels = new String[0];
    private final Object hopLock = new Object();
    private Thread hopThread;
    private Process hopProcess;
    private final MonitorModeBackend hopBackend = new IwMonitorModeBackend(this);
    /** Held while capturing so the CPU is not suspended with the screen off. */
    private android.os.PowerManager.WakeLock wakeLock;

    /** Starts a session; false when the request could not even be queued. */
    static boolean start(Context context, String interfaceName, String channel,
                         int packetLimit, int snaplen, String targetPath,
                         String hopChannels, int hopIntervalMs, boolean hopping) {
        if (context == null) return false;
        Intent intent = new Intent(context, MonitorCaptureService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_INTERFACE, interfaceName);
        intent.putExtra(EXTRA_CHANNEL, channel);
        intent.putExtra(EXTRA_PACKET_LIMIT, packetLimit);
        intent.putExtra(EXTRA_SNAPLEN, snaplen);
        intent.putExtra(EXTRA_TARGET, targetPath);
        intent.putExtra(EXTRA_HOP_CHANNELS, hopChannels);
        intent.putExtra(EXTRA_HOP_INTERVAL, hopIntervalMs);
        intent.putExtra(EXTRA_HOPPING, hopping);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (RuntimeException refused) {
            try {
                context.startService(intent);
                return true;
            } catch (RuntimeException failed) {
                return false;
            }
        }
    }

    /** Stops the session and kills the capture process. */
    static void stop(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MonitorCaptureService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (RuntimeException refused) {
            // The service (and therefore onDestroy, which kills tcpdump) still
            // runs when it is stopped directly.
            context.stopService(new Intent(context, MonitorCaptureService.class));
        }
    }

    /** Current session, or an idle state when nothing is capturing. */
    static State state(Context context) {
        MonitorCaptureService service = instance;
        if (service != null && service.running && service.sessionId == LIVE_SESSION.get()) {
            return new State(true, service.hopping, service.interfaceName, service.channel,
                    service.targetPath, service.pid);
        }
        return IDLE;
    }

    static boolean isRunning(Context context) {
        return state(context).running;
    }

    /**
     * Android 13+ hides new notification channels until the user grants
     * {@code POST_NOTIFICATIONS}; without the prompt the capture runs with no
     * visible stop button. The prompt itself belongs to an activity, so it is
     * only offered from here and never blocks the start.
     */
    static boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInternal(intent);
        return START_NOT_STICKY;
    }

    /** Shared by the bound activity and the legacy start command. */
    private boolean startInternal(Intent intent) {
        if (intent == null) return false;
        if (running) return true;
        // A bound activity may own the service before any start request, so the
        // instance has to be published here and not only in onCreate.
        instance = this;
        sessionId = SESSION_ID.incrementAndGet();
        LIVE_SESSION.set(sessionId);
        interfaceName = sanitize(intent.getStringExtra(EXTRA_INTERFACE));
        channel = clean(intent.getStringExtra(EXTRA_CHANNEL));
        targetPath = clean(intent.getStringExtra(EXTRA_TARGET));
        frameLimit = intent.getIntExtra(EXTRA_PACKET_LIMIT, 0);
        snaplen = intent.getIntExtra(EXTRA_SNAPLEN, 0);
        hopChannels = parseHopChannels(intent.getStringExtra(EXTRA_HOP_CHANNELS));
        hopIntervalMs = Math.max(500, intent.getIntExtra(EXTRA_HOP_INTERVAL, 2000));
        if (!isValidInterface(interfaceName) || targetPath.isEmpty() || snaplen <= 0 || frameLimit <= 0) {
            stopSelf();
            return false;
        }
        running = true;
        pid = -1L;
        hopping = intent.getBooleanExtra(EXTRA_HOPPING, false) && hopChannels.length > 1;
        acquireWakeLock();
        try {
            Notification notification = buildNotification(0L);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException denied) {
            // Without the notification the capture still runs while the
            // activity is alive; the user simply loses the stop button.
        }
        Thread worker = new Thread(this::capture, "wifitester-capture");
        worker.setDaemon(true);
        worker.start();
        Thread heartbeat = new Thread(this::heartbeat, "wifitester-capture-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
        if (hopping) startHopThread();
        broadcastState();
        notifyListener();
        return true;
    }

    @Override public void onDestroy() {
        running = false;
        if (sessionId == LIVE_SESSION.get()) LIVE_SESSION.set(0L);
        stopHopThread();
        terminate();
        releaseWakeLock();
        pid = -1L;
        if (instance == this) {
            instance = null;
            // The notification must never outlive the session.
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTIFICATION_ID);
        }
        broadcastState();
        notifyListener();
        super.onDestroy();
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile Runnable listener;

    private final class Binder extends android.os.Binder implements Session {
        @Override public boolean start(String iface, String channelLabel, int limit, int snaplenValue,
                                       String path, String hopChannelsSpec, int hopInterval,
                                       boolean hoppingEnabled) {
            Intent intent = new Intent(MonitorCaptureService.this, MonitorCaptureService.class);
            intent.setAction(ACTION_START);
            intent.putExtra(EXTRA_INTERFACE, iface);
            intent.putExtra(EXTRA_CHANNEL, channelLabel);
            intent.putExtra(EXTRA_PACKET_LIMIT, limit);
            intent.putExtra(EXTRA_SNAPLEN, snaplenValue);
            intent.putExtra(EXTRA_TARGET, path);
            intent.putExtra(EXTRA_HOP_CHANNELS, hopChannelsSpec);
            intent.putExtra(EXTRA_HOP_INTERVAL, hopInterval);
            intent.putExtra(EXTRA_HOPPING, hoppingEnabled);
            return startInternal(intent);
        }

        @Override public boolean setChannelHopping(boolean enabled) {
            return MonitorCaptureService.this.setChannelHopping(enabled);
        }

        @Override public boolean isChannelHopping() {
            return hopping;
        }

        @Override public void stop() {
            stopSelf();
        }

        @Override public State state() {
            return MonitorCaptureService.this.state();
        }

        @Override public void setListener(Runnable value) {
            listener = value;
        }
    }

    private final Binder binder = new Binder();

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    private State state() {
        return running
                ? new State(true, hopping, interfaceName, channel, targetPath, pid)
                : IDLE;
    }

    /** Notifies the bound activity on the main thread. */
    private void notifyListener() {
        Runnable target = listener;
        if (target == null) return;
        main.post(() -> {
            try {
                target.run();
            } catch (RuntimeException ignored) {
                // A destroyed activity must not crash the service.
            }
        });
    }

    // ---- wake lock --------------------------------------------------------

    /**
     * A capture must keep running with the screen off, otherwise a Doze
     * suspend silently stops the frame counter and the PCAP file.
     */
    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        if (wakeLock != null) return;
        android.os.PowerManager power =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":monitor-capture");
        wakeLock.setReferenceCounted(false);
        try {
            wakeLock.acquire();
        } catch (RuntimeException denied) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        android.os.PowerManager.WakeLock held = wakeLock;
        wakeLock = null;
        if (held == null) return;
        try {
            if (held.isHeld()) held.release();
        } catch (RuntimeException ignored) {
            // Released by the system already.
        }
    }

    // ---- channel hopping --------------------------------------------------

    /**
     * Enables or disables hopping. The loop lives in this service, so it is
     * cancelled with the capture instead of leaking a root shell when the
     * activity is destroyed.
     */
    private boolean setChannelHopping(boolean enabled) {
        if (!running || hopChannels.length < 2) return false;
        if (hopping == enabled) return true;
        hopping = enabled;
        if (enabled) {
            startHopThread();
        } else {
            stopHopThread();
        }
        broadcastState();
        notifyListener();
        return true;
    }

    private void startHopThread() {
        synchronized (hopLock) {
            if (hopThread != null) return;
            Thread thread = new Thread(this::hopLoop, "wifitester-channel-hop");
            thread.setDaemon(true);
            hopThread = thread;
            thread.start();
        }
    }

    private void stopHopThread() {
        Thread thread;
        Process process;
        synchronized (hopLock) {
            hopping = false;
            thread = hopThread;
            hopThread = null;
            process = hopProcess;
            hopProcess = null;
        }
        if (thread != null) thread.interrupt();
        if (process != null) {
            try { process.destroy(); } catch (RuntimeException ignored) { }
        }
    }

    /** Retunes to the next channel, then sleeps for the configured interval. */
    private void hopLoop() {
        int index = 0;
        while (running && hopping) {
            String[] channelsSnapshot = hopChannels;
            if (channelsSnapshot.length < 2) break;
            String spec = channelsSnapshot[index % channelsSnapshot.length];
            index++;
            tuneTo(spec);
            try {
                Thread.sleep(Math.max(500, hopIntervalMs));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (hopLock) {
            if (hopThread == Thread.currentThread()) hopThread = null;
        }
    }

    /** Runs one tune command built from a "number,frequency" pair. */
    private void tuneTo(String spec) {
        String[] parts = spec.split(",");
        if (parts.length != 2) return;
        int number;
        int frequency;
        try {
            number = Integer.parseInt(parts[0].trim());
            frequency = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException malformed) {
            return;
        }
        String command = hopBackend.buildTuneCommand(
                interfaceName, new WifiChannelParser.Channel(number, frequency, "ch" + number));
        if (command == null || !running || !hopping) return;
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            synchronized (hopLock) {
                if (!hopping) {
                    process.destroy();
                    return;
                }
                hopProcess = process;
            }
            process.waitFor();
        } catch (Exception ignored) {
            // A failed hop is not fatal: the next tick retries.
        } finally {
            synchronized (hopLock) {
                if (hopProcess == process) hopProcess = null;
            }
        }
    }

    /** Parses "number,frequency;number,frequency" while rejecting anything odd. */
    private static String[] parseHopChannels(String raw) {
        if (raw == null || raw.isEmpty()) return new String[0];
        java.util.ArrayList<String> parsed = new java.util.ArrayList<>();
        for (String entry : raw.split(";")) {
            String[] parts = entry.trim().split(",");
            if (parts.length != 2) continue;
            try {
                int number = Integer.parseInt(parts[0].trim());
                int frequency = Integer.parseInt(parts[1].trim());
                if (number <= 0 || number > 233 || frequency < 2300 || frequency > 7125) continue;
                parsed.add(number + "," + frequency);
            } catch (NumberFormatException ignored) {
                // Skip malformed entries.
            }
        }
        return parsed.toArray(new String[0]);
    }

    // ---- capture ----------------------------------------------------------

    private void capture() {
        Process process = null;
        try {
            String path = BundledToolchain.quote(targetPath);
            String shell = new BundledToolchain(this).shellEnvironment()
                    + "TCPDUMP=" + new BundledToolchain(this).executable("tcpdump") + "; "
                    + "umask 022; \"$TCPDUMP\" -i " + BundledToolchain.quote(interfaceName)
                    + " -U -n -s " + snaplen + " -c " + frameLimit + " -w " + path
                    + " & CAPTURE_PID=$!; echo WIFITESTER_CAPTURE_PID:$CAPTURE_PID; wait \"$CAPTURE_PID\"; "
                    + "RC=$?; chown " + android.os.Process.myUid() + ":" + android.os.Process.myUid()
                    + " " + path + " >/dev/null 2>&1 || true; chmod 600 " + path
                    + " >/dev/null 2>&1 || true; exit $RC";
            process = new ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start();
            capture = process;
            if (!running) {
                // stop() raced the start: never leave an orphan tcpdump behind.
                terminate();
                return;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("WIFITESTER_CAPTURE_PID:")) {
                        try {
                            long parsed = Long.parseLong(
                                    line.substring("WIFITESTER_CAPTURE_PID:".length()).trim());
                            if (parsed > 1L) pid = parsed;
                        } catch (NumberFormatException ignored) {
                            // The pid is only needed for a targeted kill.
                        }
                    }
                    broadcast(ACTION_STATE, line, -9999);
                }
            }
            int exit = process.waitFor();
            broadcast(ACTION_STATE, null, exit);
        } catch (Exception failure) {
            broadcast(ACTION_STATE, failure.getClass().getSimpleName() + ": " + failure.getMessage(), -1);
        } finally {
            capture = null;
            // Hopping must never outlive the capture it tunes for.
            stopHopThread();
        }
    }

    /** Stops tcpdump; SIGINT flushes and closes the PCAP file cleanly. */
    private void terminate() {
        long currentPid = pid;
        if (currentPid > 1L) {
            try {
                new ProcessBuilder("su", "-c", "kill -INT " + currentPid + " 2>/dev/null || true")
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {
                // Largest effort: the wrapper is destroyed below anyway.
            }
        }
        Process process = capture;
        if (process != null) {
            try {
                process.destroy();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }

    private void heartbeat() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) return;
            updateNotification(fileLength());
            broadcast(ACTION_STATE, null, -9999);
        }
    }

    private long fileLength() {
        try {
            java.io.File file = new java.io.File(targetPath);
            return file.isFile() ? file.length() : 0L;
        } catch (RuntimeException unreadable) {
            return 0L;
        }
    }

    // ---- notification -----------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.capture_notification_channel_detail));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(long bytes) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent open = new Intent(this, MonitorModeActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent stop = new Intent(this, MonitorCaptureService.class).setAction(ACTION_STOP);
        String text = bytes > 0
                ? getString(R.string.capture_notification_progress,
                        android.text.format.Formatter.formatFileSize(this, bytes), frameLimit)
                : getString(R.string.capture_notification_text,
                        interfaceName, channel.isEmpty() ? "-" : channel);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_nav_wifi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(PendingIntent.getActivity(this, 11, open, flags))
                .addAction(0, getString(R.string.capture_notification_stop),
                        PendingIntent.getService(this, 12, stop, flags))
                .build();
    }

    private void updateNotification(long bytes) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (!manager.areNotificationsEnabled()) {
            // Re-posting would fail silently; the capture keeps running and the
            // activity shows the state instead.
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(bytes));
        } catch (RuntimeException ignored) {
            // A notification failure never stops the capture.
        }
    }

    // ---- state relay ------------------------------------------------------

    private void broadcastState() {
        broadcast(ACTION_STATE, null, -9999);
        notifyListener();
    }

    private void broadcast(String action, String line, int exit) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_RUNNING, running);
            intent.putExtra(EXTRA_INTERFACE, interfaceName);
            intent.putExtra(EXTRA_CHANNEL, channel);
            intent.putExtra(EXTRA_TARGET, targetPath);
            intent.putExtra(EXTRA_PID, pid);
            intent.putExtra(EXTRA_HOPPING, hopping);
            if (line != null) intent.putExtra(EXTRA_LINE, line);
            if (exit != -9999) intent.putExtra(EXTRA_EXIT, exit);
            sendBroadcast(intent);
        } catch (RuntimeException ignored) {
            // The activity may be gone; the notification is the durable state.
        }
    }

    // ---- helpers ----------------------------------------------------------

    /** Keeps the interface name inside the alphabet the shell scripts accept. */
    private static String sanitize(String value) {
        String trimmed = clean(value);
        return isValidInterface(trimmed) ? trimmed : "";
    }

    /** Interface names end up in a root shell, so the alphabet is strict. */
    private static boolean isValidInterface(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,15}");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
