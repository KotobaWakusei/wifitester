package me.paranoid.wifikill;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Runs a long-lived privileged command and keeps track of the inner process id.
 *
 * <p>{@code su -c} normally spawns an intermediate shell, so destroying the
 * Java-side process would leave the real tool running. The command is
 * therefore started in the background and its pid is echoed back, which allows
 * the workbench to terminate the actual tool process on stop.</p>
 */
final class RootProcess {

    /** Marker used to detect the echoed pid line. */
    private static final String PID_MARKER = "WIFITESTER_PID:";

    /** Receives the live output of the managed process. */
    interface OutputListener {
        void onLine(String line);

        void onFinished(int exitCode);
    }

    private final Process process;
    private volatile long innerPid = -1L;
    private volatile boolean stopped;

    private RootProcess(Process process) {
        this.process = process;
    }

    /**
     * Starts {@code command} in the background and reads its output.
     * The toolchain environment is prepended so bundled binaries are found.
     */
    static RootProcess start(Context context, String command, OutputListener listener) {
        String environment = context == null ? "" : new BundledToolchain(context).shellEnvironment();
        // Run in the background so the pid of the real tool can be captured and
        // terminated independently of the su wrapper. "exec" replaces the
        // subshell with the tool itself, which guarantees that the reported pid
        // belongs to the tool and not to an intermediate shell.
        String body = isSimpleCommand(command) ? "exec " + command : command;
        String shell = environment
                + "{ " + body + " ; } & echo " + PID_MARKER + "$!; wait $!; exit $?";
        try {
            Process started = new ProcessBuilder("su", "-c", shell)
                    .redirectErrorStream(true)
                    .start();
            // Some Root managers wait for stdin to close before running the
            // command; the managed tools never read stdin themselves.
            try {
                started.getOutputStream().close();
            } catch (Exception ignored) {
                // The process may have exited already.
            }
            RootProcess managed = new RootProcess(started);
            managed.pump(listener);
            return managed;
        } catch (Exception failure) {
            listener.onLine(failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            listener.onFinished(-1);
            return null;
        }
    }

    /**
     * Whether the command can be executed with "exec", i.e. it is a single
     * simple command without shell operators that would change the parsing.
     */
    static boolean isSimpleCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        return !command.matches(".*[|;&<>`$()\\\\\"'].*");
    }

    private void pump(final OutputListener listener) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(PID_MARKER)) {
                        try {
                            long parsed = Long.parseLong(line.substring(PID_MARKER.length()).trim());
                            if (parsed > 1L) {
                                innerPid = parsed;
                            }
                        } catch (NumberFormatException ignored) {
                            // Keep the previous value; the pid is optional.
                        }
                        continue;
                    }
                    listener.onLine(line);
                }
            } catch (Exception ignored) {
                // The stream ends when the process is destroyed.
            }
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                code = -1;
            }
            listener.onFinished(code);
        }, "wifitester-attack-output");
        reader.setDaemon(true);
        reader.start();
    }

    /** The pid of the managed tool process, or -1 when it was never reported. */
    long innerPid() {
        return innerPid;
    }

    boolean isAlive() {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    /**
     * Stops the tool: the inner process is signalled first so it can flush its
     * output files, and the su wrapper is destroyed afterwards.
     */
    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        long pid = innerPid;
        if (pid > 1L) {
            RootShell.runBare("kill -TERM " + pid + " 2>/dev/null || true", 3000);
            long deadline = System.currentTimeMillis() + 1500L;
            while (System.currentTimeMillis() < deadline) {
                if (!isAlive()) {
                    break;
                }
                RootShell.sleep(80L);
            }
            if (isAlive()) {
                RootShell.runBare("kill -KILL " + pid + " 2>/dev/null || true", 3000);
            }
        }
        try {
            process.destroy();
        } catch (Exception ignored) {
            // The process may already be gone.
        }
    }
}
