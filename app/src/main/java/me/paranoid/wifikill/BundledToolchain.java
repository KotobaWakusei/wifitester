package me.paranoid.wifikill;

import android.content.Context;
import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Installs read-only Android command-line tools shipped in assets/toolchain/<abi>.
 * No network access or third-party package manager is used at runtime.
 */
public final class BundledToolchain {
    private static final String ASSET_ROOT = "toolchain/";
    private static final String ABI = android.os.Build.SUPPORTED_ABIS.length > 0
            ? android.os.Build.SUPPORTED_ABIS[0] : "unknown";
    /**
     * Process-wide result of the first successful install attempt. Re-checking
     * digests on every call is wasteful, and concurrent installs from separate
     * instances could race on the same <name>"" .new"" temp files.
     */
    private static volatile InstallResult cachedInstall;
    private final Context context;
    private final File root;
    private final File bin;
    private final File lib;

    public BundledToolchain(Context context) {
        this.context = context.getApplicationContext();
        this.root = new File(this.context.getFilesDir(), "toolchain");
        this.bin = new File(root, "bin");
        this.lib = new File(root, "lib");
    }

    public String getAbi() { return ABI; }
    public File getBinDir() { return bin; }
    public File getLibDir() { return lib; }

    /** Copies packaged assets to app-private storage and marks executable files executable. */
    public synchronized InstallResult install() {
        if (cachedInstall != null) return cachedInstall;
        ArrayList<String> installed = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        if (!"arm64-v8a".equals(ABI) && !"armeabi-v7a".equals(ABI)
                && !"x86_64".equals(ABI) && !"x86".equals(ABI)) {
            return new InstallResult(installed, errors, context.getString(R.string.bundled_unsupported_abi, ABI));
        }
        if (!bin.mkdirs() && !bin.isDirectory()) {
            return new InstallResult(installed, errors, context.getString(R.string.bundled_tool_dir_failed, bin));
        }
        if (!lib.mkdirs() && !lib.isDirectory()) {
            return new InstallResult(installed, errors, context.getString(R.string.bundled_lib_dir_failed, lib));
        }
        String assetDir = ASSET_ROOT + ABI;
        try {
            String[] entries = context.getAssets().list(assetDir);
            if (entries == null || entries.length == 0) {
                return new InstallResult(installed, errors,
                        context.getString(R.string.bundled_missing, assetDir));
            }
            for (String name : entries) {
                if (!name.matches("[A-Za-z0-9_.+-]+")) {
                    errors.add(context.getString(R.string.bundled_skip_name, name));
                    continue;
                }
                boolean isLibrary = name.endsWith(".so") || name.contains(".so.");
                File target = new File(isLibrary ? lib : bin, name);
                try (InputStream in = context.getAssets().open(assetDir + "/" + name)) {
                    File temp = new File(target.getParentFile(), name + ".new");
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                        out.getFD().sync();
                    }
                    if (target.exists() && digest(target).equals(digest(temp))) {
                        temp.delete();
                    } else {
                        if (target.exists() && !target.delete()) {
                            temp.delete();
                            throw new java.io.IOException(context.getString(R.string.bundled_replace_failed));
                        }
                        if (!temp.renameTo(target)) {
                            temp.delete();
                            throw new java.io.IOException(context.getString(R.string.bundled_commit_failed));
                        }
                    }
                    if (!isLibrary) {
                        try { Os.chmod(target.getAbsolutePath(), 0755); }
                        catch (Exception chmodError) {
                            if (!target.setExecutable(true, true))
                                throw new java.io.IOException(context.getString(R.string.bundled_chmod_failed), chmodError);
                        }
                        installed.add(name);
                    }
                } catch (Exception e) {
                    errors.add(name + "：" + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add(context.getString(R.string.bundled_read_failed, e.getClass().getSimpleName() + " " + e.getMessage()));
        }
        Collections.sort(installed);
        // Only cache fully clean results; partial failures must stay retryable.
        if (errors.isEmpty()) {
            cachedInstall = new InstallResult(installed, errors,
                    installed.isEmpty() ? context.getString(R.string.bundled_none_found) : context.getString(R.string.bundled_installed));
            return cachedInstall;
        }
        return new InstallResult(installed, errors,
                installed.isEmpty() ? context.getString(R.string.bundled_none_found) : context.getString(R.string.bundled_installed));
    }

    public String shellEnvironment() {
        String b = quote(bin.getAbsolutePath());
        String l = quote(lib.getAbsolutePath());
        return "export PATH=" + b + ":$PATH; export LD_LIBRARY_PATH=" + l
                + ":\u0024{LD_LIBRARY_PATH:-}; ";
    }

    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String digest(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : md.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    public static final class InstallResult {
        public final List<String> installed;
        public final List<String> errors;
        public final String message;
        InstallResult(List<String> installed, List<String> errors, String message) {
            this.installed = installed;
            this.errors = errors;
            this.message = message;
        }
    }
}
