package me.paranoid.wifikill;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only analyzer for deauthentication and disassociation frames in an existing capture.
 * This tool does not transmit frames or disconnect any device.
 */
public final class FrameAnalysisActivity extends BaseActivity {
    private static final int OPEN_CAPTURE = 8301;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status, output;
    private MaterialButton choose, analyze;
    private Uri selected;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));
        getWindow().setNavigationBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));

        ScrollView scroll = UiKit.createPage(this);
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);

        UiKit.addSectionHeader(root, this,
                getString(R.string.frames_eyebrow),
                getString(R.string.frames_workbench_title),
                getString(R.string.frames_subtitle));

        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.text(this, getString(R.string.frames_choose), 16, UiKit.WHITE, true));

        status = UiKit.text(this, getString(R.string.frames_no_file), 13, UiKit.MUTED_DARK, false);
        card.addView(status, UiKit.top(7, this));

        choose = UiKit.buttonCard(this, getString(R.string.frames_choose_button));
        choose.setOnClickListener(v -> openCapture());
        card.addView(choose, UiKit.top(12, this));

        analyze = UiKit.buttonPrimary(this, getString(R.string.frames_analyze));
        analyze.setEnabled(false);
        analyze.setOnClickListener(v -> analyzeCapture());
        card.addView(analyze, UiKit.top(8, this));

        root.addView(card, UiKit.bottom(12, this));

        LinearLayout results = UiKit.card(this);
        results.addView(UiKit.text(this, getString(R.string.frames_results), 16, UiKit.WHITE, true));
        output = UiKit.resultView(this);
        output.setText(getString(R.string.frames_waiting));
        results.addView(output, UiKit.top(9, this));
        root.addView(results);

        setContentView(scroll);
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

    private void openCapture() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, OPEN_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_CAPTURE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selected = data.getData();
            status.setText(getString(R.string.frames_selected, selected.getLastPathSegment()));
            analyze.setEnabled(true);
            output.setText(getString(R.string.frames_preparing));
        }
    }

    private void analyzeCapture() {
        if (busy || selected == null) return;
        busy = true;
        analyze.setEnabled(false);
        choose.setEnabled(false);
        output.setText(getString(R.string.frames_validating));

        final Uri uri = selected;
        worker.execute(() -> {
            File copy = null;
            String result;
            try {
                copy = File.createTempFile("wifitester-frame-analysis-", ".pcap", getCacheDir());
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new IllegalStateException(getString(R.string.frames_read_failed));
                    byte[] buffer = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > CaptureFileInspector.MAX_FILE_BYTES) {
                            throw new IllegalArgumentException(getString(R.string.frames_too_large));
                        }
                        out.write(buffer, 0, n);
                    }
                    if (total == 0) throw new IllegalArgumentException(getString(R.string.frames_empty));
                }

                CaptureFileInspector.Format format;
                try (FileInputStream in = new FileInputStream(copy)) {
                    format = CaptureFileInspector.detect(in);
                }
                if (format == CaptureFileInspector.Format.UNKNOWN) {
                    throw new IllegalArgumentException(getString(R.string.frames_bad_header));
                }

                BundledToolchain.InstallResult install = new BundledToolchain(this).install();
                if (install.installed.isEmpty()) {
                    throw new IllegalStateException(getString(R.string.frames_tcpdump_missing, install.message));
                }

                String file = BundledToolchain.quote(copy.getAbsolutePath());
                // Note: 802.11 radiotap/monitor linktype required for mgt subtype filter.
                // If the capture uses a different linktype (e.g., Ethernet), tcpdump will return no matches.
                String command = new BundledToolchain(this).shellEnvironment()
                        + "tcpdump -nn -e -r " + file
                        + " 'type mgt subtype deauth or type mgt subtype disassoc' 2>&1";

                Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                // Guard: a malformed capture can make tcpdump read indefinitely.
                // The watchdog terminates it after 30 seconds so the worker cannot hang.
                Thread watchdog = new Thread(() -> {
                    try { Thread.sleep(30000L); } catch (InterruptedException interrupted) { return; }
                    if (isRunning(p)) p.destroy();
                }, "wifitester-frame-watchdog");
                watchdog.start();
                StringBuilder lines = new StringBuilder();
                int count = 0;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (lines.length() < 45000) {
                            lines.append(line).append('\n');
                            count++;
                        }
                    }
                }
                int code = p.waitFor();
                watchdog.interrupt();

                result = getString(R.string.frames_result_format, format.displayLabel(this))
                        + "\n" + getString(R.string.frames_result_filter)
                        + "\n" + getString(R.string.frames_result_exit, code)
                        + "\n" + getString(R.string.frames_result_lines, count)
                        + "\n\n"
                        + (lines.length() == 0
                                ? getString(R.string.frames_no_match)
                                : lines.toString());

                if (code != 0) {
                    result += getString(R.string.frames_tcpdump_hint);
                }
            } catch (Exception e) {
                result = getString(R.string.frames_analysis_failed, e.getClass().getSimpleName(), String.valueOf(e.getMessage()));
            } finally {
                if (copy != null) {
                    try { copy.delete(); } catch (Exception ignored) {}
                }
            }

            final String finalResult = result;
            runOnUiThread(() -> {
                busy = false;
                analyze.setEnabled(selected != null);
                choose.setEnabled(true);
                output.setText(finalResult);
            });
        });
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}