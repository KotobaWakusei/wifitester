package me.paranoid.wifikill;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WifiScanParserTest {
    @Test
    public void classifiesCommonSecurityModes() {
        assertEquals("Enhanced Open (OWE)", WifiScanParser.classifySecurity("[OWE][ESS]"));
        assertEquals("WPA3", WifiScanParser.classifySecurity("[WPA3-SAE-CCMP][ESS]"));
        assertEquals("WPA2", WifiScanParser.classifySecurity("[WPA2-PSK-CCMP][ESS]"));
        assertEquals("WPA (legacy)", WifiScanParser.classifySecurity("[WPA-PSK-TKIP][ESS]"));
        assertEquals("WEP (legacy)", WifiScanParser.classifySecurity("[WEP][ESS]"));
        assertEquals("Open", WifiScanParser.classifySecurity("[ESS]"));
    }

    @Test
    public void unknownSecurityDoesNotDefaultToOpen() {
        assertEquals("Unknown", WifiScanParser.classifySecurity(null));
        assertEquals("Unknown", WifiScanParser.classifySecurity(""));
        assertEquals("Unknown", WifiScanParser.classifySecurity("[vendor-specific]"));
    }

    @Test
    public void mapsCommonChannelsAcrossBands() {
        assertEquals("1", WifiScanParser.channelForFrequency(2412));
        assertEquals("14", WifiScanParser.channelForFrequency(2484));
        assertEquals("36", WifiScanParser.channelForFrequency(5180));
        assertEquals("182", WifiScanParser.channelForFrequency(4910));
        assertEquals("2", WifiScanParser.channelForFrequency(5935));
        assertEquals("1", WifiScanParser.channelForFrequency(5955));
        assertEquals("233", WifiScanParser.channelForFrequency(7115));
    }

    @Test
    public void rejectsFrequenciesThatDoNotMapToAChannel() {
        assertEquals("?", WifiScanParser.channelForFrequency(2413));
        assertEquals("?", WifiScanParser.channelForFrequency(7200));
    }

    @Test
    public void identifiesBandsWithoutMislabelingInvalidFrequencies() {
        assertEquals("2.4 GHz", WifiScanParser.bandForFrequency(2412));
        assertEquals("4.9 GHz", WifiScanParser.bandForFrequency(4910));
        assertEquals("5 GHz", WifiScanParser.bandForFrequency(5180));
        assertEquals("6 GHz", WifiScanParser.bandForFrequency(5955));
        assertEquals("Unknown", WifiScanParser.bandForFrequency(7200));
        assertEquals("Unknown", WifiScanParser.bandForFrequency(0));
    }
}
