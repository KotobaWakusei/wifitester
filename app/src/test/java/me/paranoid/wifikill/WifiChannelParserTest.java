package me.paranoid.wifikill;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WifiChannelParserTest {
    @Test
    public void parsesChannelsFromIwListSectionOnly() {
        String output = "root output\n"
                + "__IW_LIST__\n"
                + "Wiphy phy0\n"
                + "  * 2412 MHz [1] (20.0 dBm)\n"
                + "  * 5180 MHz [36] (20.0 dBm)\n"
                + "  * 5955 MHz [1] (20.0 dBm)\n"
                + "__IW_DEV__\n"
                + "Interface wlan0\n";
        ArrayList<WifiChannelParser.Channel> channels = WifiChannelParser.parse(output);
        assertEquals(3, channels.size());
        assertEquals(2412, channels.get(0).frequency);
        assertEquals("Ch 36  ·  5180 MHz  ·  5 GHz", channels.get(1).label);
        assertEquals("Ch 1  ·  5955 MHz  ·  6 GHz", channels.get(2).label);
    }

    @Test
    public void skipsDisabledInvalidAndOutOfBandFrequencies() {
        String output = "__IW_LIST__\n"
                + "  * 2412 MHz [1] (disabled)\n"
                + "  * 3000 MHz [1] (20.0 dBm)\n"
                + "  * 5180 MHz [0] (20.0 dBm)\n"
                + "  * 7200 MHz [234] (20.0 dBm)\n"
                + "__IW_DEV__\n";
        assertTrue(WifiChannelParser.parse(output).isEmpty());
    }

    @Test
    public void deduplicatesFrequenciesAndLabelsThe49GhzBand() {
        String output = "__IW_LIST__\n"
                + "  * 4910 MHz [182] (20.0 dBm)\n"
                + "  * 4910 MHz [182] (20.0 dBm)\n"
                + "__IW_DEV__\n";
        ArrayList<WifiChannelParser.Channel> channels = WifiChannelParser.parse(output);
        assertEquals(1, channels.size());
        assertEquals("Ch 182  ·  4910 MHz  ·  4.9 GHz", channels.get(0).label);
    }

    @Test
    public void rejectsChannelNumbersThatDoNotMatchFrequency() {
        String output = "__IW_LIST__\n"
                + "  * 2412 MHz [36] (20.0 dBm)\n"
                + "  * 5180 MHz [40] (20.0 dBm)\n"
                + "  * 5181 MHz [36] (20.0 dBm)\n"
                + "  * 5935 MHz [1] (20.0 dBm)\n"
                + "__IW_DEV__\n";
        assertTrue(WifiChannelParser.parse(output).isEmpty());
    }

    @Test
    public void returnsEmptyForMissingOrMalformedSections() {
        assertTrue(WifiChannelParser.parse(null).isEmpty());
        assertTrue(WifiChannelParser.parse("no iw output").isEmpty());
    }
}
