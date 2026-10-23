package me.paranoid.wifikill;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Executes shell commands with elevated privileges.
 *
 * <p>Every call is bounded by a timeout so an unanswered Root authorization
 * prompt or a hung driver call can never block a worker thread forever. The
 * bundled toolchain {@code PATH}/{@code LD_LIBRARY_PATH} are prepended unless
 * the caller explicitly asks for a bare environment.</p>
 */
final class RootShell {

    /** Timeouts used across the app, in milliseconds. */
    static final int TIMEOUT_PROBE = 15000;
    static final int TIMEOUT_SHORT = 4000;
    static final int TIMEOUT_COMMAND = 12000;
    static final int TIMEOUT_CLEANUP = 6000;
    static final int TIMEOUT_LONG = 30000;
    /** A Root manager that never answers must not wedge the check forever. */
    static final int TIMEOUT_ROOT_CHECK = 6000;

    private RootShell() {
    }

    /** Runs a command with the bundled toolchain environment prepended. */
    static Result run(Context context, String command, int timeoutMs) {
        String environment = context == null ? "" : new BundledToolchain(context).shellEnvironment();
        return execute(environment + command, timeoutMs);
    }

    /** Runs a command with a bare environment (no bundled toolchain paths). */
    static Result runBare(String command, int timeoutMs) {
        return execute(command, timeoutMs);
    }

    /**
     * Verifies that {@code su} really returns UID 0 rather than merely
     * reporting that a {@code su} binary exists on the device.
     */
    static boolean hasRoot(Context context) {
        Result result = run(context, "id", TIMEOUT_ROOT_CHECK);
        return result.succeeded() && result.output.matches("(?s).*\\buid=0(?:\\(|\\b).*");
    }

    private static Result execute(String shell, int timeoutMs) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        int code = -1;
        try {
            process = new ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start();
            final Process running = process;
            // Several Root managers keep the su wrapper alive until stdin is
            // closed. Commands that never read input must not wait for the
            // timeout, and a timed out command would be interrupted halfway
            // through creating the monitor interface.
            closeQuietly(process.getOutputStream());
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(running.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // The stream closes when the process is destroyed; nothing to report.
                }
            }, "wifitester-root-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean finished = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    code = process.exitValue();
                    finished = true;
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    sleep(40L);
                }
            }
            if (finished) {
                try {
                    reader.join(1500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else {
                process.destroy();
                long stopDeadline = System.currentTimeMillis() + 500L;
                while (System.currentTimeMillis() < stopDeadline) {
                    try {
                        code = process.exitValue();
                        break;
                    } catch (IllegalThreadStateException stopping) {
                        sleep(25L);
                    }
                }
                try {
                    reader.join(800L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                synchronized (output) {
                    output.append("\ntimeout");
                }
            }
        } catch (Exception e) {
            synchronized (output) {
                output.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            }
        } finally {
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException e) {
                    process.destroy();
                }
            }
        }
        synchronized (output) {
            return new Result(code, output.toString());
        }
    }

    /** Signals end of input to the shell; failures are expected when it already exited. */
    private static void closeQuietly(java.io.OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Exception ignored) {
            // The process may have exited already.
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Result of a bounded shell invocation. */
    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output == null ? "" : output;
        }

        boolean succeeded() {
            return code == 0;
        }

        boolean contains(String needle) {
            return needle != null && output.contains(needle);
        }
    }
}
