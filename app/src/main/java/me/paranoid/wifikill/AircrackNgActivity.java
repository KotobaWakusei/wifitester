package me.paranoid.wifikill;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only GUI for aircrack-ng suite tools.
 * Shows tool availability and allows running safe informational commands.
 * Does NOT provide password cracking, injection, or deauthentication features.
 */
public final class AircrackNgActivity extends BaseActivity {
    private static final int ACCENT = 0xff78b8ff;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView statusView, outputView;
    private LinearLayout outputCard;
    private MaterialButton checkButton;
    private boolean busy = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(UiKit.color(this, R.attr.colorSurface));
        getWindow().setNavigationBarColor(UiKit.color(this, R.attr.colorSurface));

        ScrollView scroll = UiKit.createPage(this);
        LinearLayout root = (LinearLayout) scroll.getChildAt(0);

        UiKit.addSectionHeader(root, this,
                getString(R.string.inventory_eyebrow),
                getString(R.string.inventory_workbench_title),
                getString(R.string.inventory_subtitle));

        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.text(this, getString(R.string.inventory_status), 16, UiKit.WHITE, true));

        statusView = UiKit.text(this, getString(R.string.inventory_click_to_detect), 13, UiKit.MUTED_DARK, false);
        card.addView(statusView, UiKit.top(7, this));

        checkButton = UiKit.buttonPrimary(this, getString(R.string.inventory_detect_button));
        checkButton.setOnClickListener(v -> detectTools());
        card.addView(checkButton, UiKit.top(12, this));

        root.addView(card, UiKit.bottom(12, this));

        LinearLayout infoCard = UiKit.card(this);
        infoCard.addView(UiKit.text(this, getString(R.string.inventory_readonly_commands), 16, UiKit.WHITE, true));
        infoCard.addView(UiKit.text(this, getString(R.string.inventory_readonly_hint), 12, UiKit.MUTED_DARK, false), UiKit.top(5, this));
        addToolButton(infoCard, "aircrack-ng --help", getString(R.string.inventory_help_aircrack), "aircrack-ng --help 2>&1 | head -40");
        addToolButton(infoCard, "airodump-ng --help", getString(R.string.inventory_help_airodump), "airodump-ng --help 2>&1 | head -40");
        addToolButton(infoCard, "airmon-ng --help", getString(R.string.inventory_help_airmon), "airmon-ng --help 2>&1");
        addToolButton(infoCard, "aircrack-ng --version", getString(R.string.inventory_version_aircrack), "aircrack-ng --version 2>&1");
        root.addView(infoCard, UiKit.bottom(12, this));

        outputView = UiKit.logView(this);
        outputView.setText(getString(R.string.inventory_waiting));
        outputCard = UiKit.logCard(this, outputView, getString(R.string.inventory_output));
        root.addView(outputCard);

        setContentView(scroll);
    }

    private void addToolButton(LinearLayout parent, String label, String description, String command) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        TextView name = UiKit.text(this, label, 13, UiKit.WHITE, true);
        row.addView(name);
        TextView desc = UiKit.text(this, description, 11, UiKit.MUTED_DARK, false);
        row.addView(desc, UiKit.top(3, this));
        row.setOnClickListener(v -> runCommand(command));
        row.setBackground(UiKit.round(UiKit.color(this, R.attr.colorSurfaceContainer), UiKit.dp(this, 12)));
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));
        parent.addView(row);
    }

    private void detectTools() {
        if (busy) return;
        busy = true;
        checkButton.setEnabled(false);
        statusView.setText(getString(R.string.inventory_detecting));
        worker.execute(() -> {
            String command = "printf 'UID: '; id; " + ToolCatalog.probeCommand();
            BundledToolchain.InstallResult embedded = new BundledToolchain(this).install();
            Result result = runRoot(command, 10000);
            final String output = result.output;
            runOnUiThread(() -> {
                busy = false;
                checkButton.setEnabled(true);
                if (result.code != 0 && (output == null || output.trim().isEmpty())) {
                    statusView.setText(getString(R.string.inventory_detect_failed, result.code));
                } else {
                    String inventory = ToolCatalog.format(this, output);
                    statusView.setText(getString(R.string.inventory_bundled, new BundledToolchain(this).getAbi(), embedded.message
                            + "\n" + (embedded.installed.isEmpty() ? getString(R.string.bundled_none) : getString(R.string.bundled_released) + android.text.TextUtils.join(", ", embedded.installed))
                            + "\n\n" + inventory));
                }
            });
        });
    }

    private void runCommand(String command) {
        if (busy) return;
        busy = true;
        outputView.setText(getString(R.string.inventory_running, command));
        worker.execute(() -> {
            Result result = runRoot(command, 8000);
            final String output = getString(R.string.inventory_command_result, command, result.code,
                    result.output.isEmpty() ? getString(R.string.inventory_no_output) : result.output);
            final int lineCount = UiKit.countLines(output);
            runOnUiThread(() -> {
                busy = false;
                UiKit.updateLogCounter(outputCard, lineCount);
                outputView.setText(output);
            });
        });
    }

    private Result runRoot(String command, int timeoutMs) {
        Process p = null;
        StringBuilder out = new StringBuilder();
        int code = -1;
        try {
            p = new ProcessBuilder("su", "-c", new BundledToolchain(this).shellEnvironment() + command).redirectErrorStream(true).start();
            final Process process = p;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) { out.append(line).append('\n'); }
                    }
                } catch (Exception ignored) {}
            }, "wifitester-tool-reader");
            reader.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (System.currentTimeMillis() < deadline) {
                try { code = p.exitValue(); done = true; break; }
                catch (IllegalThreadStateException running) { Thread.sleep(50L); }
            }
            if (!done) { p.destroy(); code = 124; synchronized (out) { out.append(getString(R.string.command_timeout)); } }
            reader.join(1000L);
        } catch (Exception e) { synchronized (out) { out.append(e.getClass().getSimpleName()).append(": ").append(String.valueOf(e.getMessage())); } }
        finally { if (p != null && isRunning(p)) p.destroy(); }
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
        Result(int c, String o) { code = c; output = o; }
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}