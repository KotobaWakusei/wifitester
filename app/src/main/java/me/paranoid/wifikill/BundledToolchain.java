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
    /**
     * Process-wide link prelude. A new BundledToolchain is constructed for
     * every shell command; the instance version of this cache was therefore
     * re-scanning the binaries from flash on each {@code su -c} call.
     */
    private static volatile String aircrackPathPrelude;
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

    /**
     * Re-extracts the packaged tools even when this process already succeeded
     * once. Used by the repair action for a toolchain that was deleted or
     * truncated behind the app's back.
     */
    public synchronized InstallResult reinstall() {
        cachedInstall = null;
        return install();
    }

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
        return aircrackPathPrelude() + "export PATH=" + b + ":$PATH; export LD_LIBRARY_PATH=" + l
                + ":\u0024{LD_LIBRARY_PATH:-}; export OPENSSL_CONF=" + l + "/openssl.cnf; ";
    }

    /**
     * Root-side mkdir/ln commands for the absolute dlopen() paths embedded in
     * aircrack-ng binaries (for example {@code /tmp/tmp.XXX/prefix/lib}).
     *
     * <p>The app-UID install step cannot create directories under {@code /tmp};
     * the shell environment, by contrast, is always evaluated inside
     * {@code su -c}. Emitting the links here is what actually makes aircrack-ng
     * load the bundled crypto engine on a stock device.</p>
     */
    private String aircrackPathPrelude() {
        String cached = aircrackPathPrelude;
        if (cached != null) return cached;
        StringBuilder commands = new StringBuilder();
        try {
            for (String buildPath : buildPathPrefixes()) {
                commands.append("mkdir -p ").append(quote(buildPath)).append(" 2>/dev/null; ");
                for (String name : new String[]{"libaircrack-ce-wpa.so",
                        "libaircrack-osdep-1.7.0.so"}) {
                    java.io.File target = new java.io.File(lib, name);
                    if (!target.isFile()) continue;
                    commands.append("ln -sf ").append(quote(target.getAbsolutePath()))
                            .append(' ').append(quote(new java.io.File(buildPath, name).getAbsolutePath()))
                            .append(" 2>/dev/null; ");
                }
            }
        } catch (RuntimeException ignored) {
            // A scan failure must never break the tool environment.
        }
        String result = commands.toString();
        aircrackPathPrelude = result;
        return result;
    }

    /** Unique build-time lib directories found in the bundled aircrack binaries. */
    private java.util.List<String> buildPathPrefixes() {
        java.util.LinkedHashSet<String> prefixes = new java.util.LinkedHashSet<>();
        java.io.File[] binaries = bin.listFiles((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.startsWith("aircrack") || lower.startsWith("airodump")
                    || lower.startsWith("aireplay") || lower.startsWith("airmon");
        });
        if (binaries == null) return new java.util.ArrayList<>();
        java.util.regex.Pattern pattern =
                java.util.regex.Pattern.compile("/tmp/tmp\\.[A-Za-z0-9]+/prefix/lib");
        for (java.io.File binFile : binaries) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(binFile, "r")) {
                long length = raf.length();
                if (length > 5_000_000) continue; // skip overly large files
                byte[] data = new byte[(int) length];
                raf.readFully(data);
                String content = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
                java.util.regex.Matcher matcher = pattern.matcher(content);
                while (matcher.find()) prefixes.add(matcher.group());
            } catch (Exception ignored) {
                // Unreadable binaries simply contribute no paths.
            }
        }
        return new java.util.ArrayList<>(prefixes);
    }

    /**
     * Shell expression for a tool that actually runs on this device.
     *
     * <p>The bundled binaries are cross-compiled; a build with an under-aligned
     * TLS segment is rejected by Bionic with "executable's TLS segment is
     * underaligned" even though the file is complete and executable. Falling
     * back to the system copy (present on most rooted ROMs) is better than
     * capturing nothing at all, so each candidate is executed before use.</p>
     *
     * <p>The result is a {@code $( ... )} substitution, so it must be used as
     * an argument rather than concatenated without quoting.</p>
     *
     * @return a shell word usable in place of the tool name; the bundled path
     *         is returned as last resort so the driver error stays visible.
     */
    public String executable(String name) {
        String bundled = quote(new File(bin, name).getAbsolutePath());
        if (!name.matches("[A-Za-z0-9._-]{1,32}")) return bundled;
        String system = quote("/system/bin/" + name);
        return "$( if " + bundled + " --version >/dev/null 2>&1; then printf '%s' " + bundled
                + "; elif " + system + " --version >/dev/null 2>&1; then printf '%s' " + system
                + "; elif command -v " + name + " >/dev/null 2>&1; then command -v " + name
                + "; else printf '%s' " + bundled + "; fi )";
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
