package me.paranoid.wifikill;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Safe GUI entry point for Aircrack-ng ecosystem integration.
 * This screen detects installed tools and performs read-only capture metadata inspection.
 * It intentionally does not expose deauthentication, packet injection, or password cracking.
 */
public final class AircrackToolkitActivity extends BaseActivity {
    private static final int OPEN_CAPTURE = 7401;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView toolStatus, report;
    private MaterialButton detectButton, captureButton, exportAnalysisButton;
    private boolean busy;
    private String latestAnalysisReport = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));
        getWindow().setNavigationBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));

        buildUi();
        detectTools();
    }

    private void buildUi() {
        ScrollView scroll = UiKit.createPage(this);
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);

        UiKit.addSectionHeader(root, this,
                getString(R.string.toolkit_eyebrow),
                getString(R.string.toolkit_workbench_title),
                getString(R.string.toolkit_subtitle));

        LinearLayout status = UiKit.card(this);
        status.addView(UiKit.text(this, getString(R.string.toolkit_status), 17, UiKit.WHITE, true));
        toolStatus = UiKit.text(this, getString(R.string.toolkit_checking), 13, UiKit.MUTED_DARK, false);
        toolStatus.setPadding(0, UiKit.dp(this, 10), 0, 0);
        status.addView(toolStatus);
        detectButton = UiKit.buttonCard(this, getString(R.string.toolkit_recheck));
        detectButton.setOnClickListener(v -> detectTools());
        status.addView(detectButton, UiKit.top(12, this));
        root.addView(status, UiKit.bottom(12, this));

        LinearLayout tools = UiKit.card(this);
        tools.addView(UiKit.text(this, getString(R.string.toolkit_section_aircrack), 16, UiKit.WHITE, true));
        tools.addView(UiKit.text(this, getString(R.string.toolkit_section_aircrack_detail), 13, UiKit.MUTED_DARK, false), UiKit.top(7, this));
        MaterialButton aircrackButton = UiKit.buttonPrimary(this, getString(R.string.toolkit_open_aircrack));
        aircrackButton.setOnClickListener(v -> {
            Intent i = new Intent(this, AircrackNgActivity.class);
            startActivity(i);
        });
        tools.addView(aircrackButton, UiKit.top(12, this));
        root.addView(tools, UiKit.bottom(12, this));

        LinearLayout monitor = UiKit.card(this);
        monitor.addView(UiKit.text(this, getString(R.string.toolkit_section_monitor), 16, UiKit.WHITE, true));
        monitor.addView(UiKit.text(this, getString(R.string.toolkit_section_monitor_detail), 13, UiKit.MUTED_DARK, false), UiKit.top(7, this));
        MaterialButton monitorButton = UiKit.buttonPrimary(this, getString(R.string.toolkit_open_monitor));
        monitorButton.setOnClickListener(v -> startActivity(new Intent(this, MonitorModeActivity.class)));
        monitor.addView(monitorButton, UiKit.top(12, this));
        root.addView(monitor, UiKit.bottom(12, this));

        LinearLayout capture = UiKit.card(this);
        capture.addView(UiKit.text(this, getString(R.string.toolkit_section_capture), 16, UiKit.WHITE, true));
        capture.addView(UiKit.text(this, getString(R.string.toolkit_section_capture_detail), 13, UiKit.MUTED_DARK, false), UiKit.top(7, this));
        captureButton = UiKit.buttonPrimary(this, getString(R.string.toolkit_choose_capture));
        captureButton.setOnClickListener(v -> openCapture());
        capture.addView(captureButton, UiKit.top(12, this));
        report = UiKit.resultView(this);
        report.setText(getString(R.string.toolkit_no_file));
        capture.addView(report, UiKit.top(10, this));
        exportAnalysisButton = UiKit.buttonSecondary(this, getString(R.string.toolkit_export_report));
        exportAnalysisButton.setEnabled(false);
        exportAnalysisButton.setOnClickListener(v -> exportAnalysisReport());
        capture.addView(exportAnalysisButton, UiKit.top(10, this));
        root.addView(capture, UiKit.bottom(12, this));

        LinearLayout audit = UiKit.card(this);
        audit.addView(UiKit.text(this, getString(R.string.toolkit_section_audit), 16, UiKit.WHITE, true));
        audit.addView(UiKit.text(this, getString(R.string.toolkit_section_audit_detail), 13, UiKit.MUTED_DARK, false), UiKit.top(7, this));
        MaterialButton auditButton = UiKit.buttonPrimary(this, getString(R.string.toolkit_open_audit));
        auditButton.setOnClickListener(v -> startActivity(new Intent(this, WiFiAuditActivity.class)));
        audit.addView(auditButton, UiKit.top(12, this));
        root.addView(audit, UiKit.bottom(12, this));

        setContentView(scroll);
    }

    private void detectTools() {
        if (busy) return;
        busy = true;
        detectButton.setEnabled(false);
        toolStatus.setText(getString(R.string.toolkit_detecting));
        worker.execute(() -> {
            String command = "printf 'UID: '; id; " + ToolCatalog.probeCommand();
            BundledToolchain.InstallResult embedded = new BundledToolchain(this).install();
            Result result = runRoot(command, 10000);
            final String output = result.output;
            runOnUiThread(() -> {
                busy = false;
                detectButton.setEnabled(true);
                if (result.code != 0 && (output == null || output.trim().isEmpty())) {
                    toolStatus.setText(getString(R.string.inventory_detect_failed, result.code));
                } else {
                    String inventory = ToolCatalog.format(this, output);
                    toolStatus.setText(getString(R.string.toolkit_bundled, new BundledToolchain(this).getAbi(), embedded.message
                            + "\n" + (embedded.installed.isEmpty() ? getString(R.string.bundled_none) : getString(R.string.bundled_released) + android.text.TextUtils.join(", ", embedded.installed))
                            + "\n\n" + inventory + getString(R.string.toolkit_hint)
                            + (result.code == 0 ? "" : getString(R.string.toolkit_exit_code, result.code))));
                }
            });
        });
    }

    private void openCapture() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // PCAPNG files are reported with inconsistent MIME types by document providers.
        // Offer all file types here and validate the actual magic bytes before analysis.
        i.setType("*/*");
        startActivityForResult(i, OPEN_CAPTURE);
    }

    private void exportAnalysisReport() {
        if (latestAnalysisReport.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "wifitester-capture-analysis.txt");
        try {
            startActivityForResult(intent, 7402);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, getString(R.string.toolkit_save_failed, e.getMessage()), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7402 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri target = data.getData();
            String content = latestAnalysisReport;
            worker.execute(() -> {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IllegalStateException(getString(R.string.toolkit_open_target_failed));
                    out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                    runOnUiThread(() -> android.widget.Toast.makeText(this, getString(R.string.toolkit_report_exported), android.widget.Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, getString(R.string.toolkit_export_failed, e.getMessage()), android.widget.Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        if (requestCode != OPEN_CAPTURE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (busy) {
            android.widget.Toast.makeText(this, getString(R.string.toolkit_busy), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        captureButton.setEnabled(false);
        report.setText(getString(R.string.toolkit_reading));
        worker.execute(() -> {
            File copy = null;
            String output = "";
            int code = -1;
            try {
                copy = File.createTempFile("wifitester-capture-", ".pcap", getCacheDir());
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new IllegalStateException(getString(R.string.toolkit_read_failed));
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > CaptureFileInspector.MAX_FILE_BYTES)
                            throw new IllegalArgumentException(getString(R.string.toolkit_too_large));
                        out.write(buf, 0, n);
                    }
                    if (total == 0) throw new IllegalArgumentException(getString(R.string.toolkit_empty_file));
                }
                CaptureFileInspector.Format format;
                try (java.io.FileInputStream headerInput = new java.io.FileInputStream(copy)) {
                    format = CaptureFileInspector.detect(headerInput);
                }
                if (format == CaptureFileInspector.Format.UNKNOWN)
                    throw new IllegalArgumentException(getString(R.string.toolkit_bad_header));
                String path = copy.getAbsolutePath();
                String quoted = BundledToolchain.quote(path);
                String cmd = "if command -v capinfos >/dev/null 2>&1; then capinfos " + quoted + "; " +
                        "elif [ -x /data/data/com.termux/files/usr/bin/capinfos ]; then /data/data/com.termux/files/usr/bin/capinfos " + quoted + "; " +
                        "elif command -v tshark >/dev/null 2>&1; then tshark -r " + quoted + " -q -z io,phs; " +
                        "elif [ -x /data/data/com.termux/files/usr/bin/tshark ]; then /data/data/com.termux/files/usr/bin/tshark -r " + quoted + " -q -z io,phs; " +
                        "elif command -v tcpdump >/dev/null 2>&1; then tcpdump -nn -r " + quoted + " -c 20 2>&1; else echo '" + getString(R.string.toolkit_no_tools) + "'; exit 127; fi";
                Result result = runRoot(cmd, 20000);
                output = getString(R.string.toolkit_output, uri.getLastPathSegment(), format.displayLabel(this), result.code, result.output);
                code = result.code;
            } catch (Exception e) {
                output = getString(R.string.toolkit_analysis_failed, e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
            } finally {
                if (copy != null) try { copy.delete(); } catch (Exception ignored) {}
            }
            final String finalOutput = output;
            final int finalCode = code;
            runOnUiThread(() -> {
                busy = false;
                captureButton.setEnabled(true);
                latestAnalysisReport = finalOutput + (finalCode == 127 ? getString(R.string.toolkit_no_bundled_tools) : "");
                report.setText(latestAnalysisReport);
                exportAnalysisButton.setEnabled(!latestAnalysisReport.isEmpty());
            });
        });
    }

    private Result runRoot(String command, int timeoutMs) {
        Process p = null;
        java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.locks.Condition notEmpty = lock.newCondition();
        StringBuilder out = new StringBuilder();
        int code = -1;
        boolean writerDone = false;
        try {
            p = new ProcessBuilder("su", "-c", new BundledToolchain(this).shellEnvironment() + command).redirectErrorStream(true).start();
            final Process process = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lock.lock();
                        try {
                            out.append(line).append('\n');
                        } finally {
                            lock.unlock();
                        }
                    }
                } catch (Exception ignored) {}
            }, "wifitester-tool-reader");
            reader.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    code = p.exitValue();
                    done = true;
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(50L);
                }
            }
            if (!done) {
                p.destroy();
                code = 124;
                lock.lock();
                try {
                    out.append(getString(R.string.command_timeout));
                } finally {
                    lock.unlock();
                }
            }
            reader.join(1000L);
        } catch (Exception e) {
            lock.lock();
            try {
                out.append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage()));
            } finally {
                lock.unlock();
            }
        } finally {
            if (p != null && isRunning(p)) p.destroy();
        }
        synchronized (out) { return new Result(code, out.toString().trim()); }
    }


    /** Whether the process is still running; works on every supported release. */
    private static boolean isRunning(Process process) {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    private static final class Result {
        final int code;
        final String output;
        Result(int c, String o) {
            code = c;
            output = o;
        }
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}