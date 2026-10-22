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
        assertEquals(11, tools.size());
        assertEquals("aircrack-ng", tools.get(0).name);
        assertTrue(tools.get(0).isAvailable());
        assertTrue("tcpdump must be reported as available", byName(tools, "tcpdump").isAvailable());
        assertFalse(byName(tools, "iw").isAvailable());
        assertFalse(byName(tools, "hostapd").isAvailable());
    }

    private static ToolCatalog.Tool byName(List<ToolCatalog.Tool> tools, String name) {
        for (ToolCatalog.Tool tool : tools) {
            if (tool.name.equals(name)) {
                return tool;
            }
        }
        throw new AssertionError("tool not listed: " + name);
    }

    @Test public void reportsInjectionToolsAsPartOfTheInventory() {
        List<ToolCatalog.Tool> tools = ToolCatalog.parse("");
        boolean aireplay = false;
        boolean hostapd = false;
        boolean arpspoof = false;
        for (ToolCatalog.Tool tool : tools) {
            if ("aireplay-ng".equals(tool.name)) aireplay = true;
            if ("hostapd".equals(tool.name)) hostapd = true;
            if ("arpspoof".equals(tool.name)) arpspoof = true;
        }
        assertTrue("aireplay-ng must be part of the inventory", aireplay);
        assertTrue("hostapd must be part of the inventory", hostapd);
        assertTrue("arpspoof must be part of the inventory", arpspoof);
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

    @Test public void probeCommandMentionsEveryCatalogTool() {
        String command = ToolCatalog.probeCommand();
        for (ToolCatalog.Tool tool : ToolCatalog.parse("")) {
            assertTrue("probe command must query " + tool.name, command.contains(tool.name));
        }
    }
}
