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
        ArrayList<String> installed = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        if (!"arm64-v8a".equals(ABI) && !"armeabi-v7a".equals(ABI)
                && !"x86_64".equals(ABI) && !"x86".equals(ABI)) {
            return new InstallResult(installed, errors, "不支持的 ABI：" + ABI);
        }
        if (!bin.mkdirs() && !bin.isDirectory()) {
            return new InstallResult(installed, errors, "无法创建工具目录：" + bin);
        }
        if (!lib.mkdirs() && !lib.isDirectory()) {
            return new InstallResult(installed, errors, "无法创建库目录：" + lib);
        }
        String assetDir = ASSET_ROOT + ABI;
        try {
            String[] entries = context.getAssets().list(assetDir);
            if (entries == null || entries.length == 0) {
                return new InstallResult(installed, errors,
                        "APK 未包含 " + assetDir + " 下的预编译工具。请使用包含对应 ABI 工具的构建版本。");
            }
            for (String name : entries) {
                if (!name.matches("[A-Za-z0-9_.+-]+")) {
                    errors.add("跳过非法文件名：" + name);
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
                            throw new java.io.IOException("无法替换旧文件");
                        }
                        if (!temp.renameTo(target)) {
                            temp.delete();
                            throw new java.io.IOException("无法提交工具文件");
                        }
                    }
                    if (!isLibrary) {
                        try { Os.chmod(target.getAbsolutePath(), 0755); }
                        catch (Exception chmodError) {
                            if (!target.setExecutable(true, true))
                                throw new java.io.IOException("无法设置可执行权限", chmodError);
                        }
                        installed.add(name);
                    }
                } catch (Exception e) {
                    errors.add(name + "：" + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("读取 APK 工具资源失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        Collections.sort(installed);
        return new InstallResult(installed, errors,
                installed.isEmpty() ? "未发现可安装的内置可执行文件" : "内置工具已释放到应用私有目录");
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
