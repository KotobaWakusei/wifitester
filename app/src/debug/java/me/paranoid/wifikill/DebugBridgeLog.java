package me.paranoid.wifikill;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Append-only log for the adb debug bridge.
 *
 * <p>Everything the bridge does — received commands, status answers, live
 * attack output — is appended here and flushed to
 * {@code files/debug-bridge.log}. The file lives in app-private storage and
 * is meant to be read from the host with:</p>
 *
 * <pre>adb shell run-as me.paranoid.wifikill.debug cat files/debug-bridge.log</pre>
 *
 * <p>All methods are static and thread safe: broadcast receivers, the RootProcess
 * output threads and UI threads may append concurrently.</p>
 */
final class DebugBridgeLog {

    private static final int MAX_MEMORY_LINES = 4000;
    private static final long MAX_FILE_BYTES = 512L * 1024L;

    private static final Object LOCK = new Object();
    private static final Deque<String> MEMORY = new ArrayDeque<>();
    private static volatile File file;

    private DebugBridgeLog() {
    }

    /** Appends one line with a timestamp and persists it. */
    static void append(String line) {
        String stamped = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + "  " + line;
        synchronized (LOCK) {
            MEMORY.addLast(stamped);
            while (MEMORY.size() > MAX_MEMORY_LINES) {
                MEMORY.pollFirst();
            }
        }
        flush();
    }

    /** Appends several lines as one entry (used for multi-line answers). */
    static void appendBlock(String block) {
        append("-- begin --");
        String[] lines = block.split("\n", -1);
        synchronized (LOCK) {
            for (String line : lines) {
                MEMORY.addLast(line);
                while (MEMORY.size() > MAX_MEMORY_LINES) {
                    MEMORY.pollFirst();
                }
            }
        }
        flush();
        append("-- end --");
    }

    /** Clears the log; used by the "clear" command. */
    static void clear() {
        synchronized (LOCK) {
            MEMORY.clear();
        }
        synchronized (LOCK) {
            if (file != null && file.isFile()) {
                file.delete();
            }
        }
    }

    /** The in-memory tail, newest last; used by the status answer. */
    static String tail(int lines) {
        synchronized (LOCK) {
            int from = Math.max(0, MEMORY.size() - lines);
            StringBuilder out = new StringBuilder();
            int index = 0;
            for (String line : MEMORY) {
                if (index++ >= from) {
                    out.append(line).append('\n');
                }
            }
            return out.toString();
        }
    }

    private static void flush() {
        File target = file;
        if (target == null) {
            return;
        }
        String snapshot;
        synchronized (LOCK) {
            snapshot = tail(MAX_MEMORY_LINES);
        }
        try {
            // Rewrite in place: app-private file, small enough, and it keeps
            // the format stable for "cat" from the host.
            long length = target.isFile() ? target.length() : 0L;
            if (length > MAX_FILE_BYTES) {
                target.delete();
            }
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(target, false), StandardCharsets.UTF_8)) {
                writer.write(snapshot);
                writer.flush();
            }
        } catch (Exception ignored) {
            // A debug facility must never crash the app.
        }
    }

    /** Initialises the backing file; called from the receiver's onCreate. */
    static void init(Context context) {
        try {
            File bridge = new File(context.getFilesDir(), "debug-bridge.log");
            if (!bridge.isFile()) {
                bridge.createNewFile();
            }
            file = bridge;
        } catch (Exception ignored) {
            file = null;
        }
    }
}
