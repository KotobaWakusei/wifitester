package me.paranoid.wifikill;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages the wordlists used by the offline password verification module.
 *
 * <p>Wordlists live in {@code files/wordlists/}. They can be imported from
 * the device storage (SAF picker) or downloaded from a URL, selected as the
 * default for the attack workbench, and deleted. The chosen default is
 * returned to the caller so the crack module picks it up immediately.</p>
 */
public final class WordlistActivity extends BaseActivity {

    /** Extra returned to the caller: the absolute path of the selected wordlist. */
    public static final String EXTRA_SELECTED_PATH = "selected_wordlist";
    /** Result code used by the attack workbench. */
    public static final int RESULT_SELECTED = 71;

    private static final long MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;
    private static final long MAX_LINE_COUNT_BYTES = 20L * 1024 * 1024;

    private static final int REQ_IMPORT = 72;

    private LinearLayout listContainer;
    private TextView defaultLabel;
    private File directory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UiKit.color(this, Attrs.COLOR_SURFACE));
        directory = new File(getFilesDir(), "wordlists");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            MainActivity.notify(this, getString(R.string.wordlist_dir_failed));
        }
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = LightKit.page(this);
        LinearLayout root = LightKit.root(this, scroll);
        LightKit.header(root, this, getString(R.string.wordlist_eyebrow),
                getString(R.string.wordlist_title), getString(R.string.wordlist_subtitle));

        LinearLayout defaultCard = LightKit.card(this);
        defaultCard.addView(LightKit.text(this, getString(R.string.wordlist_default_title), 16, LightKit.INK, true));
        defaultLabel = LightKit.text(this, "", 13, LightKit.MUTED, false);
        defaultLabel.setTextIsSelectable(true);
        defaultCard.addView(defaultLabel, LightKit.top(this, 6));
        root.addView(defaultCard, LightKit.bottom(this, 12));

        LinearLayout importCard = LightKit.card(this);
        importCard.addView(LightKit.text(this, getString(R.string.wordlist_add_title), 16, LightKit.INK, true));

        MaterialButton importButton = LightKit.secondary(this, getString(R.string.wordlist_import));
        importButton.setOnClickListener(v -> pickFile());
        importCard.addView(importButton, LightKit.top(this, 10));

        TextView urlHint = LightKit.text(this, getString(R.string.wordlist_url_hint), 12, LightKit.MUTED, false);
        importCard.addView(urlHint, LightKit.top(this, 10));
        final EditText urlInput = UiKit.plainInput(this, "https://example.com/wordlist.txt");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setTextSize(13);
        importCard.addView(urlInput, LightKit.top(this, 6));
        MaterialButton download = LightKit.primary(this, getString(R.string.wordlist_download));
        download.setOnClickListener(v -> downloadWordlist(urlInput.getText().toString().trim(), download));
        importCard.addView(download, LightKit.top(this, 8));
        root.addView(importCard, LightKit.bottom(this, 12));

        LinearLayout listCard = LightKit.card(this);
        listCard.addView(LightKit.text(this, getString(R.string.wordlist_list_title), 16, LightKit.INK, true));
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(listContainer, LightKit.top(this, 8));
        root.addView(listCard, LightKit.bottom(this, 12));

        setContentView(scroll);
    }

    private void refresh() {
        defaultLabel.setText(getString(R.string.wordlist_default_value,
                SettingsStore.wordlistPath(this).isEmpty()
                        ? getString(R.string.wordlist_none) : SettingsStore.wordlistPath(this)));
        listContainer.removeAllViews();
        File[] files = directory.listFiles();
        List<File> sorted = new ArrayList<>();
        if (files != null) {
            sorted.addAll(Arrays.asList(files));
        }
        Collections.sort(sorted, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (sorted.isEmpty()) {
            TextView empty = LightKit.text(this, getString(R.string.wordlist_empty), 13, LightKit.MUTED, false);
            listContainer.addView(empty, LightKit.top(this, 6));
            return;
        }
        boolean first = true;
        for (File file : sorted) {
            if (!first) {
                listContainer.addView(LightKit.divider(this), LightKit.top(this, 8));
            }
            first = false;
            listContainer.addView(buildRow(file), LightKit.top(this, 4));
        }
    }

    private View buildRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView name = LightKit.text(this, file.getName(), 15, LightKit.INK, true);
        row.addView(name);

        StringBuilder details = new StringBuilder();
        details.append(getString(R.string.wordlist_size,
                ToolPackageManager.formatSize(file.length())));
        if (file.length() <= MAX_LINE_COUNT_BYTES) {
            details.append("  ·  ").append(getString(R.string.wordlist_lines, countLines(file)));
        }
        TextView detail = LightKit.text(this, details.toString(), 12, LightKit.MUTED, false);
        row.addView(detail, LightKit.top(this, 2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton use = LightKit.primary(this, getString(R.string.wordlist_use));
        use.setOnClickListener(v -> select(file));
        actions.addView(use, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton share = LightKit.neutral(this, getString(R.string.wordlist_share));
        share.setOnClickListener(v -> share(file));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, -2, 1);
        shareParams.leftMargin = LightKit.dp(this, 6);
        actions.addView(share, shareParams);
        MaterialButton delete = LightKit.danger(this, getString(R.string.wordlist_delete));
        delete.setOnClickListener(v -> confirmDelete(file));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, -2, 1);
        deleteParams.leftMargin = LightKit.dp(this, 6);
        actions.addView(delete, deleteParams);
        row.addView(actions, LightKit.top(this, 8));
        return row;
    }

    private void select(File file) {
        String path = file.getAbsolutePath();
        SettingsStore.setWordlistPath(this, path);
        refresh();
        Intent data = new Intent();
        data.putExtra(EXTRA_SELECTED_PATH, path);
        setResult(RESULT_SELECTED, data);
        MainActivity.notify(this, getString(R.string.wordlist_selected, file.getName()));
    }

    private void share(File file) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.wordlist_share)));
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.wordlist_share_failed,
                    failure.getClass().getSimpleName()));
        }
    }

    private void confirmDelete(File file) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.wordlist_delete))
                .setMessage(getString(R.string.wordlist_delete_confirm, file.getName()))
                .setPositiveButton(getString(R.string.action_yes), (dialog, which) -> {
                    if (!file.delete()) {
                        MainActivity.notify(this, getString(R.string.wordlist_delete_failed));
                    }
                    refresh();
                })
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.wordlist_import_failed,
                    failure.getClass().getSimpleName()));
        }
    }

    private void downloadWordlist(String url, MaterialButton button) {
        if (url.isEmpty() || !url.startsWith("http")) {
            MainActivity.notify(this, getString(R.string.wordlist_url_invalid));
            return;
        }
        button.setEnabled(false);
        new Thread(() -> {
            String error = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "WiFiTester");
                connection.connect();
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    error = getString(R.string.wordlist_download_http, code);
                } else {
                    String name = url.substring(url.lastIndexOf('/') + 1);
                    int query = name.indexOf('?');
                    if (query >= 0) {
                        name = name.substring(0, query);
                    }
                    if (name.isEmpty()) {
                        name = "wordlist-" + System.currentTimeMillis() + ".txt";
                    }
                    if (!name.matches("[A-Za-z0-9._+-]{1,64}")) {
                        name = "wordlist-" + System.currentTimeMillis() + ".txt";
                    }
                    File target = new File(directory, name);
                    long copied = 0;
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            copied += n;
                            if (copied > MAX_DOWNLOAD_BYTES) {
                                target.delete();
                                error = getString(R.string.wordlist_download_too_large);
                                break;
                            }
                            out.write(buffer, 0, n);
                        }
                    }
                    if (error == null && copied == 0) {
                        target.delete();
                        error = getString(R.string.wordlist_download_empty);
                    }
                }
                connection.disconnect();
            } catch (Exception failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
            final String message = error;
            runOnUiThread(() -> {
                button.setEnabled(true);
                if (message == null) {
                    MainActivity.notify(this, getString(R.string.wordlist_download_done));
                    refresh();
                } else {
                    MainActivity.notify(this, getString(R.string.wordlist_download_failed, message));
                }
            });
        }, "wifitester-wordlist-download").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = "imported-" + System.currentTimeMillis() + ".txt";
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(new File(directory, name))) {
            if (in == null) {
                throw new java.io.IOException(getString(R.string.wordlist_open_failed));
            }
            byte[] buffer = new byte[16384];
            long copied = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                copied += n;
                if (copied > MAX_DOWNLOAD_BYTES) {
                    throw new java.io.IOException(getString(R.string.wordlist_download_too_large));
                }
                out.write(buffer, 0, n);
            }
            MainActivity.notify(this, getString(R.string.wordlist_import_done, name));
            refresh();
        } catch (Exception failure) {
            MainActivity.notify(this, getString(R.string.wordlist_import_failed,
                    failure.getMessage()));
        }
    }

    private static int countLines(File file) {
        int lines = 0;
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            boolean lastWasNewline = true;
            while ((n = in.read(buffer)) != -1) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        lines++;
                        lastWasNewline = true;
                    } else {
                        lastWasNewline = false;
                    }
                }
            }
            if (!lastWasNewline) {
                lines++;
            }
        } catch (Exception ignored) {
            return lines;
        }
        return lines;
    }
}
