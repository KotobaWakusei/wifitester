package me.paranoid.wifikill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The packaged manifest decides which packages are built in and which can be
 * downloaded. A package that is neither must never show a download button:
 * that was the reason "下载总是失败" was reported while every URL was empty.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ToolPackageManifestTest {

    private Context app() {
        return RuntimeEnvironment.getApplication();
    }

    @Test public void theCorePackIsBuiltInAndCannotBeDownloaded() {
        ToolPackageManager manager = new ToolPackageManager(app());
        ToolPackageManager.ToolPackage core = ToolPackageManager.ToolPackage.CORE;
        assertTrue("tcpdump/arpspoof ship inside the APK", manager.isPreInstalled(core));
        assertEquals("a built-in pack has no download endpoint", "", manager.getDownloadUrl(core));
        assertFalse(manager.canDownload(core));
        // Built-in packages are never uninstallable.
        assertEquals(0L, manager.uninstallPackage(core));
    }

    /**
     * The suite is cross-compiled into the APK by
     * tools/build-android-toolchain.sh, so the manifest must not advertise a
     * download: that combination produced the "下载总是失败" reports.
     */
    @Test public void theAircrackPackIsBundledRatherThanDownloaded() {
        ToolPackageManager manager = new ToolPackageManager(app());
        ToolPackageManager.ToolPackage aircrack = ToolPackageManager.ToolPackage.AIRCRACK;
        assertTrue("aircrack-ng is built into the APK", manager.isPreInstalled(aircrack));
        assertFalse("a bundled pack must not offer a download", manager.canDownload(aircrack));
        assertEquals("", manager.getDownloadUrl(aircrack));
        assertEquals(0L, manager.uninstallPackage(aircrack));
    }

    @Test public void declaredToolsMatchTheManifest() {
        ToolPackageManager manager = new ToolPackageManager(app());
        for (ToolPackageManager.ToolPackage pkg : manager.getAllPackages()) {
            org.json.JSONObject info = manager.getPackageInfo(pkg.id);
            org.junit.Assert.assertNotNull("missing manifest entry for " + pkg.id, info);
            org.json.JSONArray tools = info.optJSONArray("tools");
            org.junit.Assert.assertNotNull("missing tools for " + pkg.id, tools);
            assertEquals(pkg.id + ": tools in the manifest and in the enum differ",
                    tools.length(), pkg.tools.size());
            for (int i = 0; i < tools.length(); i++) {
                assertTrue(pkg.id + " is missing " + tools.optString(i),
                        pkg.tools.contains(tools.optString(i)));
            }
        }
    }
}
