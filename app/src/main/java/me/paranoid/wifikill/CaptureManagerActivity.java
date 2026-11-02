package me.paranoid.wifikill;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Lists the capture files the app produced (monitor PCAPs and handshake
 * captures), shows for each whether it is a valid capture and whether it
 * carries EAPOL frames, and offers share / delete / hand over to the offline
 * password verification.
 */
public final class CaptureManagerActivity extends BaseActivity {

    /** Extra returned to the caller: the capture chosen for the crack module. */
    public static final String EXTRA_CAPTURE_PATH = "capture_path";
    public static final int RESULT_CAPTURE_SELECTED = 81;

    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = LightKit.page(this);
        LinearLayout root = LightKit.root(this, scroll);
        LightKit.header(root, this, getString(R.string.capture_eyebrow),
                getString(R.string.capture_title), getString(R.string.capture_subtitle));

        LinearLayout card = LightKit.card(this);
        card.addView(LightKit.text(this, getString(R.string.capture_list_title), 16, LightKit.INK, true));
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(listContainer, LightKit.top(this, 8));
        root.addView(card, LightKit.bottom(this, 12));

        setContentView(scroll);
    }

    private void refresh() {
        listContainer.removeAllViews();
        List<File> files = new ArrayList<>();
        collect(new File(getFilesDir(), "captures"), files);
        collect(new File(getFilesDir(), "attack-captures"), files);
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (files.isEmpty()) {
            listContainer.addView(LightKit.text(this,
                    getString(R.string.capture_empty), 13, LightKit.MUTED, false));
            return;
        }
        boolean first = true;
        for (File file : files) {
            if (!first) {
                listContainer.addView(LightKit.divider(this), LightKit.top(this, 8));
            }
            first = false;
            listContainer.addView(buildRow(file), LightKit.top(this, 4));
        }
    }

    private void collect(File directory, List<File> out) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        out.addAll(Arrays.asList(entries));
    }

    private View buildRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        row.addView(LightKit.text(this, file.getName(), 15, LightKit.INK, true));
        String details = getString(R.string.capture_size,
                ToolPackageManager.formatSize(file.length()))
                + "  ·  " + new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.US).format(file.lastModified());
        row.addView(LightKit.text(this, details, 12, LightKit.MUTED, false),
                LightKit.top(this, 2));

        // Format + handshake summary for readable captures (pure Java, no root).
        if (file.length() >= 24L && file.length() <= CaptureFileInspector.MAX_FILE_BYTES) {
            String quality = describeCapture(file);
            if (quality != null) {
                row.addView(LightKit.text(this, quality, 12, LightKit.MUTED, false),
                        LightKit.top(this, 2));
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton crack = LightKit.primary(this, getString(R.string.capture_use_crack));
        crack.setOnClickListener(v -> selectForCrack(file));
        actions.addView(crack, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton share = LightKit.neutral(this, getString(R.string.capture_share));
        share.setOnClickListener(v -> share(file));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, -2, 1);
        shareParams.leftMargin = LightKit.dp(this, 6);
        actions.addView(share, shareParams);
        MaterialButton delete = LightKit.danger(this, getString(R.string.capture_delete));
        delete.setOnClickListener(v -> confirmDelete(file));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, -2, 1);
        deleteParams.leftMargin = LightKit.dp(this, 6);
        actions.addView(delete, deleteParams);
        row.addView(actions, LightKit.top(this, 8));
        return row;
    }

    /** Format + EAPOL summary, or null when the file cannot be parsed. */
    private String describeCapture(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            CaptureFileInspector.Format format = CaptureFileInspector.detect(in);
            if (format == CaptureFileInspector.Format.UNKNOWN) {
                return getString(R.string.capture_format_unknown);
            }
            CaptureAnalyzer.Analysis analysis = CaptureAnalyzer.parse(in);
            return getString(R.string.capture_quality, format.displayLabel(this),
                    analysis.eapolFrames, analysis.handshakes.size());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void selectForCrack(File file) {
        Intent data = new Intent();
        data.putExtra(EXTRA_CAPTURE_PATH, file.getAbsolutePath());
        setResult(RESULT_CAPTURE_SELECTED, data);
        MainActivity.notify(this, getString(R.string.capture_selected, file.getName()));
        finish();
    }

    private void share(File file) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("*/*");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.capture_share)));
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.capture_share_failed,
                    failure.getClass().getSimpleName()));
        }
    }

    private void confirmDelete(File file) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.capture_delete))
                .setMessage(getString(R.string.capture_delete_confirm, file.getName()))
                .setPositiveButton(getString(R.string.action_yes), (dialog, which) -> {
                    if (!file.delete()) {
                        MainActivity.notify(this, getString(R.string.capture_delete_failed));
                    }
                    refresh();
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }
}
