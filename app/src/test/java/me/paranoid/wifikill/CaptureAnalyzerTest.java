package me.paranoid.wifikill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Guards the pure capture analyzer with synthetic pcaps: a beacon must end
 * up as SSID/channel/security, the four EAPOL-Key frames of a handshake must
 * classify per message, and hostile inputs (wrong link type, pcapng,
 * truncated tail) must degrade gracefully instead of crashing.
 */
public class CaptureAnalyzerTest {

    private static final String AP = "AA:BB:CC:DD:EE:01";
    private static final String CLIENT = "CC:11:22:33:44:55";
    private static final byte[] BSSID = {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, 0x01};
    private static final byte[] STA = {(byte) 0xcc, 0x11, 0x22, 0x33, 0x44, 0x55};

    // ---- pcap assembly ----

    /** Classic pcap global header, microsecond resolution. */
    private static byte[] globalHeader(boolean little, int linkType) {
        byte[] out = new byte[24];
        if (little) {
            out[0] = (byte) 0xd4; out[1] = (byte) 0xc3; out[2] = (byte) 0xb2; out[3] = (byte) 0xa1;
        } else {
            out[0] = (byte) 0xa1; out[1] = (byte) 0xb2; out[2] = (byte) 0xc3; out[3] = (byte) 0xd4;
        }
        put16(out, 4, 2, little);      // version major
        put16(out, 6, 4, little);      // version minor
        put32(out, 20, linkType, little);
        return out;
    }

    private static byte[] record(byte[] frame, boolean little) {
        byte[] out = new byte[16 + frame.length];
        put32(out, 0, 1700000000, little); // ts_sec
        put32(out, 4, 123456, little);     // ts_frac
        put32(out, 8, frame.length, little);
        put32(out, 12, frame.length, little);
        System.arraycopy(frame, 0, out, 16, frame.length);
        return out;
    }

    private static byte[] pcap(boolean little, int linkType, byte[]... frames) throws IOException {
        int total = 24;
        for (byte[] frame : frames) total += 16 + frame.length;
        byte[] out = new byte[total];
        System.arraycopy(globalHeader(little, linkType), 0, out, 0, 24);
        int pos = 24;
        for (byte[] frame : frames) {
            System.arraycopy(record(frame, little), 0, out, pos, 16 + frame.length);
            pos += 16 + frame.length;
        }
        return out;
    }

    private static byte[] radiotap(byte[] frame) {
        byte[] out = new byte[8 + frame.length];
        out[0] = 0;      // version
        out[1] = 0;      // pad
        out[2] = 8;      // length LE
        out[3] = 0;
        // present bitmap all zero: no optional fields
        System.arraycopy(frame, 0, out, 8, frame.length);
        return out;
    }

    /** 802.11 beacon with SSID/channel/RSN IEs and a configurable privacy bit. */
    private static byte[] beacon(String ssid, int channel, boolean rsn, boolean sae, boolean privacy) {
        byte[] iEs;
        byte[] ssidBytes = ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int rsnLen = rsn ? 22 : 0;
        int total = 2 + ssidBytes.length + (channel >= 0 ? 3 : 0) + (rsn ? 2 + rsnLen : 0);
        iEs = new byte[total];
        int pos = 0;
        iEs[pos] = 0; iEs[pos + 1] = (byte) ssidBytes.length; pos += 2;
        System.arraycopy(ssidBytes, 0, iEs, pos, ssidBytes.length); pos += ssidBytes.length;
        if (channel >= 0) {
            iEs[pos] = 3; iEs[pos + 1] = 1; iEs[pos + 2] = (byte) channel; pos += 3;
        }
        if (rsn) {
            iEs[pos] = 48; iEs[pos + 1] = (byte) rsnLen; pos += 2;
            iEs[pos] = 1; iEs[pos + 1] = 0; pos += 2;              // version 1
            iEs[pos] = 0; iEs[pos + 1] = 0x0f; iEs[pos + 2] = (byte) 0xac; iEs[pos + 3] = 0x04; pos += 4; // group CCMP
            iEs[pos] = 1; iEs[pos + 1] = 0; pos += 2;              // 1 pairwise suite
            iEs[pos] = 0; iEs[pos + 1] = 0x0f; iEs[pos + 2] = (byte) 0xac; iEs[pos + 3] = 0x04; pos += 4; // CCMP
            iEs[pos] = 1; iEs[pos + 1] = 0; pos += 2;              // 1 AKM suite
            int akm = sae ? 0x08 : 0x02;                            // SAE or PSK
            iEs[pos] = 0; iEs[pos + 1] = 0x0f; iEs[pos + 2] = (byte) 0xac; iEs[pos + 3] = (byte) akm; pos += 4;
            iEs[pos] = 0; iEs[pos + 1] = 0; pos += 2;               // capabilities
        }

        byte[] out = new byte[36 + iEs.length];
        out[0] = (byte) 0x80;                                       // beacon frame control
        out[1] = 0;
        System.arraycopy(BSSID, 0, out, 10, 6);                     // addr2 = transmitter
        put16(out, 24, 0, true);  // timestamp (2 of 8 bytes shown)
        put16(out, 34, privacy ? 0x0011 : 0x0001, true);            // capabilities (privacy bit 4)
        System.arraycopy(iEs, 0, out, 36, iEs.length);
        return out;
    }

    /**
     * QoS data frame carrying an EAPOL-Key message.
     *
     * @param fromAp true encodes M1/M3 direction (BSSID is the transmitter)
     */
    private static byte[] eapolKey(boolean fromAp, int keyInfo) {
        byte[] body = new byte[13];
        body[0] = 2;                                  // RSN descriptor type
        body[1] = (byte) (keyInfo >> 8);              // Key Information, network byte order
        body[2] = (byte) keyInfo;
        body[3] = 0; body[4] = 16;                    // key length
        for (int i = 5; i < 13; i++) body[i] = (byte) (keyInfo + i); // replay counter-ish

        byte[] out = new byte[24 + 2 + 8 + 4 + 13];
        out[0] = (byte) 0x88;                         // QoS data
        out[1] = (byte) (fromAp ? 0x02 : 0x01);       // fromDS / toDS
        byte[] a1 = fromAp ? STA : BSSID;             // addr1: receiver
        byte[] a2 = fromAp ? BSSID : STA;             // addr2: transmitter
        System.arraycopy(a1, 0, out, 4, 6);
        System.arraycopy(a2, 0, out, 10, 6);
        System.arraycopy(a2, 0, out, 16, 6);          // addr3 (ignored)
        out[24] = 7; out[25] = 0;                     // QoS control
        int off = 26;
        out[off] = (byte) 0xaa; out[off + 1] = (byte) 0xaa; out[off + 2] = 0x03;
        out[off + 3] = 0x00; out[off + 4] = 0x00; out[off + 5] = 0x00;
        out[off + 6] = (byte) 0x88; out[off + 7] = (byte) 0x8e;
        off += 8;
        out[off] = 1; out[off + 1] = 3;             // EAPOL: version 1, EAPOL-Key
        out[off + 2] = 0; out[off + 3] = (byte) body.length;
        System.arraycopy(body, 0, out, off + 4, body.length);
        return out;
    }

    /** Plain (non-QoS) data frame without LLC, e.g. an encrypted bulk frame. */
    private static byte[] encryptedData(boolean fromAp, byte[] payload) {
        byte[] out = new byte[24 + payload.length];
        out[0] = 0x08;                               // plain data
        out[1] = (byte) (fromAp ? 0x02 : 0x01);
        byte[] a1 = fromAp ? STA : BSSID;
        byte[] a2 = fromAp ? BSSID : STA;
        System.arraycopy(a1, 0, out, 4, 6);
        System.arraycopy(a2, 0, out, 10, 6);
        System.arraycopy(a2, 0, out, 16, 6);
        System.arraycopy(payload, 0, out, 24, payload.length);
        return out;
    }

    private static void put16(byte[] out, int off, int value, boolean little) {
        if (little) {
            out[off] = (byte) value; out[off + 1] = (byte) (value >> 8);
        } else {
            out[off] = (byte) (value >> 8); out[off + 1] = (byte) value;
        }
    }

    private static void put32(byte[] out, int off, int value, boolean little) {
        for (int i = 0; i < 4; i++) {
            int shift = little ? i * 8 : (3 - i) * 8;
            out[off + i] = (byte) (value >>> shift);
        }
    }

    private static CaptureAnalyzer.Analysis parse(byte[] pcap) throws IOException {
        return CaptureAnalyzer.parse(new ByteArrayInputStream(pcap));
    }

    // ---- tests ----

    @Test public void beaconBecomesAnAccessPointWithSecurity() throws Exception {
        CaptureAnalyzer.Analysis analysis = parse(pcap(true, 127, radiotap(beacon("FooNet", 6, true, false, true))));

        assertTrue(analysis.headerValid);
        assertEquals(127, analysis.linkType);
        assertFalse(analysis.unsupported);
        assertEquals(1, analysis.accessPoints.size());
        CaptureAnalyzer.AccessPoint ap = analysis.accessPoints.get(0);
        assertEquals(AP, ap.bssid);
        assertEquals("FooNet", ap.ssid);
        assertEquals(6, ap.channel);
        assertTrue("RSN without SAE is WPA2", ap.security.contains(CaptureAnalyzer.Security.WPA2));
        assertTrue(ap.beaconFrames == 1);
    }

    @Test public void saeAkmIsDetectedAsWpa3() throws Exception {
        CaptureAnalyzer.Analysis analysis =
                parse(pcap(true, 127, radiotap(beacon("Saenet", 36, true, true, true))));
        assertTrue(analysis.accessPoints.get(0).security.contains(CaptureAnalyzer.Security.WPA3));
    }

    @Test public void fourWayHandshakeAuthTokenClassifySequentially() throws Exception {
        CaptureAnalyzer.Analysis analysis = parse(pcap(true, 127,
                radiotap(beacon("FooNet", 6, true, false, true)),
                radiotap(eapolKey(true, 0x0048)),   // M1: pairwise + ACK
                radiotap(eapolKey(false, 0x0088)),  // M2/M4 signature -> M2
                radiotap(eapolKey(true, 0x01c8)),   // M3: ACK+MIC+SECURE
                radiotap(eapolKey(false, 0x0088))));

        assertEquals(1, analysis.mgmtFrames);
        assertEquals(4, analysis.dataFrames);
        assertEquals(4, analysis.eapolFrames);
        assertEquals(0, analysis.groupKeyFrames);
        assertEquals(1, analysis.handshakes.size());
        CaptureAnalyzer.Handshake hs = analysis.handshakes.get(0);
        assertEquals(AP, hs.bssid);
        assertEquals(CLIENT, hs.station);
        assertEquals(1, hs.m1);
        assertEquals(2, hs.m2); // both client frames share the signature; conservative
        assertEquals(1, hs.m3);
        assertTrue(hs.crackable());
        assertTrue(analysis.handshakes.get(0).crackable());
        assertTrue(analysis.accessPoints.get(0).security.contains(CaptureAnalyzer.Security.WPA2));
    }

    /** Second-from-ap beacon of an already-known AP updates, not duplicates. */
    @Test public void accessPointsDeduplicateByBssid() throws Exception {
        CaptureAnalyzer.Analysis analysis = parse(pcap(true, 127,
                radiotap(beacon("FooNet", 6, true, false, true)),
                radiotap(beacon("FooNet", 6, true, false, true))));
        assertEquals(1, analysis.accessPoints.size());
        assertEquals(2, analysis.accessPoints.get(0).beaconFrames);
    }

    /** Encrypted bulk frames still register the client as a station. */
    @Test public void dataFramesRememberStationsWithoutHandshake() throws Exception {
        byte[] payload = {0x11, 0x22, 0x33, 0x44};
        CaptureAnalyzer.Analysis analysis = parse(pcap(true, 127,
                radiotap(beacon("FooNet", 6, true, false, true)),
                radiotap(encryptedData(false, payload)),
                radiotap(encryptedData(true, payload))));

        assertEquals(0, analysis.eapolFrames);
        assertTrue(analysis.handshakes.isEmpty());
        CaptureAnalyzer.AccessPoint ap = analysis.accessPoints.get(0);
        assertEquals(1, ap.stations.size());
        assertTrue(ap.stations.contains(CLIENT));
        assertEquals(2, analysis.dataFrames);
    }

    /** GTK rekey EAPOL-Key frames never become a pairwise handshake. */
    @Test public void groupKeyFramesAreNotHandshakes() throws Exception {
        CaptureAnalyzer.Analysis analysis = parse(pcap(true, 127,
                radiotap(beacon("FooNet", 6, true, false, true)),
                radiotap(eapolKey(true, 0x0050)))); // ACK without pairwise bit

        assertEquals(1, analysis.eapolFrames);
        assertEquals(1, analysis.groupKeyFrames);
        assertTrue(analysis.handshakes.isEmpty());
    }

    @Test public void bigEndianPcapAndRaw80211ParseSimilarly() throws Exception {
        // Big-endian file, raw 802.11 (no radiotap) link type.
        CaptureAnalyzer.Analysis analysis = parse(pcap(false, 105,
                beacon("BarNet", 11, false, false, false)));
        assertEquals(105, analysis.linkType);
        assertEquals(1, analysis.accessPoints.size());
        CaptureAnalyzer.AccessPoint ap = analysis.accessPoints.get(0);
        assertEquals("BarNet", ap.ssid);
        assertEquals(11, ap.channel);
        assertTrue(ap.security.contains(CaptureAnalyzer.Security.OPEN));
        assertFalse(ap.security.contains(CaptureAnalyzer.Security.WEP));
    }

    @Test public void privacyBitWithoutCryptoIesMeansWep() throws Exception {
        CaptureAnalyzer.Analysis analysis = parse(pcap(true, 127,
                radiotap(beacon("OpenNet", 1, false, false, true))));
        assertTrue(analysis.accessPoints.get(0).security.contains(CaptureAnalyzer.Security.WEP));
        assertFalse(analysis.accessPoints.get(0).security.contains(CaptureAnalyzer.Security.OPEN));
    }

    @Test public void truncatedTailIsReportedNotThrown() throws Exception {
        byte[] full = pcap(true, 127, radiotap(beacon("FooNet", 6, true, false, true)),
                radiotap(eapolKey(true, 0x0048)));
        // Cut the stream inside the second frame: header claims more bytes.
        byte[] cut = new byte[full.length - 30];
        System.arraycopy(full, 0, cut, 0, cut.length);
        CaptureAnalyzer.Analysis analysis = parse(cut);

        assertTrue(analysis.corruptTail);
        // The prefix parsed before the cut stays valid; the cut record was
        // too short to even reach the LLC header, so no handshake was added.
        assertEquals(1, analysis.accessPoints.size());
        assertEquals(0, analysis.eapolFrames);
        assertTrue(analysis.handshakes.isEmpty());
    }

    @Test public void pcapngAndUnknownLinkTypesAreFlagged() throws Exception {
        byte[] pcapng = new byte[24];
        pcapng[0] = (byte) 0x0a; pcapng[1] = (byte) 0x0d;
        pcapng[2] = (byte) 0x0d; pcapng[3] = (byte) 0x0a;
        CaptureAnalyzer.Analysis container = parse(pcapng);
        assertTrue(container.isPcapng);
        assertFalse(container.headerValid);

        CaptureAnalyzer.Analysis ethernet = parse(pcap(true, 1,
                new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0, 0}));
        assertTrue(ethernet.unsupported);
        assertTrue(ethernet.headerValid); // classic pcap header, just the wrong link type
        assertEquals(1, ethernet.linkType);
    }

    @Test public void corruptRecordLengthStopsTheLoop() throws Exception {
        byte[] pcap = pcap(true, 127, radiotap(beacon("FooNet", 6, true, false, true)));
        byte[] hostile = new byte[24 + 16 + 4];
        System.arraycopy(pcap, 0, hostile, 0, 24 + 10); // header intact
        // Claim a huge caplen without any record data behind it.
        put32(hostile, 24 + 8, 0x7fffffff, true);
        CaptureAnalyzer.Analysis analysis = parse(hostile);
        assertTrue(analysis.corruptTail);
        assertFalse(analysis.headerValid == false);
    }

    @Test public void emptyStreamYieldsInertAnalysis() throws Exception {
        CaptureAnalyzer.Analysis analysis = parse(new byte[0]);
        assertFalse(analysis.headerValid);
        assertFalse(analysis.isPcapng);
        assertEquals(0, analysis.totalFrames);
        assertNull(analysis.accessPoints.stream().findFirst().orElse(null));
    }
}
