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

import java.util.List;

/**
 * Guards the inventory parser: a tool that exists on disk but refuses to start
 * ("CANNOT RUN") must never be reported as missing, and the parsed paths must
 * end up available to the workbenches.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ToolCatalogTest {

    private static String probeOutput() {
        return "WIFITESTER_TOOL\taircrack-ng\t/toolchain/bin/aircrack-ng\n"
                + "WIFITESTER_TOOL\taireplay-ng\tCANNOT RUN\n"
                + "WIFITESTER_TOOL\ttcpdump\t /system/bin/tcpdump \n"
                + "WIFITESTER_TOOL\tiw\t\n"
                + "WIFITESTER_TOOL\tip\tnot found\n"
                // Unknown tool names are ignored, not invented into the report.
                + "WIFITESTER_TOOL\tfoo\t/some/foo\n"
                + "garbage line\n";
    }

    @Test public void presentButUnrunnableToolIsNotMissing() {
        Context context = RuntimeEnvironment.getApplication();
        List<ToolCatalog.Tool> tools = ToolCatalog.parse(probeOutput());
        // Unknown tool names from the probe output are not invented entries.
        for (ToolCatalog.Tool entry : tools) {
            assertFalse("foo must not appear", entry.name.equals("foo"));
        }

        ToolCatalog.Tool airodump = find(tools, "airodump-ng");
        // Never reported by the probe: neither path nor status -> missing.
        assertFalse(airodump.isAvailable());
        assertFalse(airodump.isPresent());

        ToolCatalog.Tool aireplay = find(tools, "aireplay-ng");
        // Found on disk but cannot start: present, not missing, not usable.
        assertFalse(aireplay.isAvailable());
        assertTrue(aireplay.isPresent());
        assertEquals("CANNOT RUN", aireplay.status);

        ToolCatalog.Tool aircrack = find(tools, "aircrack-ng");
        assertTrue(aircrack.isAvailable());
    }

    @Test public void reportShowsCannotRunInsteadOfMissing() {
        Context context = RuntimeEnvironment.getApplication();
        String report = ToolCatalog.format(context, probeOutput());
        String aireplayLine = lineFor(report, "aireplay-ng");
        String airodumpLine = lineFor(report, "airodump-ng");

        assertTrue("CANNOT RUN state is surfaced",
                aireplayLine.contains(context.getString(R.string.tool_cannot_run)));
        assertTrue("genuinely missing tools stay missing",
                airodumpLine.contains(context.getString(R.string.tool_missing)));
        assertTrue("paths are reported", report.contains("/toolchain/bin/aircrack-ng"));
    }

    private static ToolCatalog.Tool find(List<ToolCatalog.Tool> tools, String name) {
        for (ToolCatalog.Tool tool : tools) {
            if (tool.name.equals(name)) return tool;
        }
        throw new AssertionError("no tool named " + name);
    }

    private static String lineFor(String report, String name) {
        for (String line : report.split("\n")) {
            if (line.startsWith(name)) return line;
        }
        return "";
    }
}
