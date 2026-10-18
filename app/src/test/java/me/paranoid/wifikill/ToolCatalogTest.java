package me.paranoid.wifikill;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ToolCatalogTest {
    @Test public void parsesAvailableAndMissingToolsInStableOrder() {
        String output = "uid=0(root)\n"
                + "WIFITESTER_TOOL\taircrack-ng\t/data/data/com.termux/files/usr/bin/aircrack-ng\n"
                + "WIFITESTER_TOOL\ttcpdump\t/data/user/0/example/files/toolchain/bin/tcpdump\n"
                + "WIFITESTER_TOOL\tiw\t\n";
        List<ToolCatalog.Tool> tools = ToolCatalog.parse(output);
        assertEquals(8, tools.size());
        assertEquals("aircrack-ng", tools.get(0).name);
        assertTrue(tools.get(0).isAvailable());
        assertTrue(tools.get(3).isAvailable());
        assertFalse(tools.get(4).isAvailable());
        assertFalse(tools.get(7).isAvailable());
    }

    @Test public void ignoresUnexpectedToolNamesAndMalformedLines() {
        List<ToolCatalog.Tool> tools = ToolCatalog.parse(
                "WIFITESTER_TOOL\tunknown-tool\t/tmp/unknown\n"
                        + "WIFITESTER_TOOL\taircrack-ng\t\n"
                        + "bad line");
        assertFalse(tools.get(0).isAvailable());
        assertFalse(tools.get(1).isAvailable());
    }

    @Test public void rejectsRelativePathsAsNotUsable() {
        List<ToolCatalog.Tool> tools = ToolCatalog.parse(
                "WIFITESTER_TOOL\taircrack-ng\tbin/aircrack-ng");
        assertFalse(tools.get(0).isAvailable());
    }
}
