package me.paranoid.wifikill;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-Java analyzer for classic pcap captures written in monitor mode.
 *
 * <p>The goal is a passive, dependency-free check of what was actually
 * captured: total/management/data/control frame counts, the access points
 * visible through beacons (SSID, channel, security posture) and, most
 * importantly, whether a WPA2/3 EAPOL-Key handshake is present for some
 * access point and client pair. The result drives the one-tap
 * "handshake check" in monitor mode and the capture overview of the frame
 * analysis screen.</p>
 *
 * <p>No native helper, root shell or tcpdump run is involved, and nothing
 * here is Android specific, which keeps the parser fully unit-testable.
 * Truncated input is tolerated and only rerouted to {@code corruptTail}; the
 * parser never invents evidence, so counts and lists only grow from frames
 * that could actually be parsed.</p>
 */
final class CaptureAnalyzer {

    /** Largest plausible single record; protects against a corrupt length field. */
    private static final int MAX_RECORD_BYTES = 262144;
    /** Upper bounds so a hostile capture cannot balloon the report. */
    private static final int MAX_ACCESS_POINTS = 256;
    private static final int MAX_HANDSHAKES = 256;
    private static final int MAX_STATIONS_PER_AP = 512;

    /** DLT radiotap (tcpdump monitor mode default) and raw 802.11. */
    static final int LINK_IEEE802_11 = 105;
    static final int LINK_IEEE802_11_RADIO = 127;

    /** Security posture extracted from beacon information elements. */
    enum Security {
        OPEN, WEP, WPA1, WPA2, WPA3
    }

    /** An access point as seen from beacons: BSSID, SSID, channel, security. */
    static final class AccessPoint {
        public final String bssid;
        public String ssid = "";
        public int channel = -1;
        public final java.util.Set<Security> security =
                java.util.EnumSet.noneOf(Security.class);
        public int beaconFrames;
        public int probeResponses;
        /** Distinct client addresses seen in data frames for this BSSID. */
        public final java.util.Set<String> stations = new java.util.LinkedHashSet<>();

        AccessPoint(String bssid) { this.bssid = bssid; }
    }

    /**
     * An EAPOL-Key exchange observed between one access point (BSSID) and
     * one station.
     *
     * <p>M2 and M4 share their Key Information signature (client to AP with
     * the MIC bit set); without replay-counter bookkeeping such frames are
     * counted conservatively as M2, and a real aircrack-ng pass remains the
     * final verdict.</p>
     */
    static final class Handshake {
        public final String bssid;
        public final String station;
        public int frames;
        public int m1, m2, m3, m4;

        Handshake(String bssid, String station) {
            this.bssid = bssid;
            this.station = station;
        }

        /** aircrack-ng can crack with the M1+M2 or the M3+M4 pair. */
        boolean crackable() {
            return (m1 > 0 && m2 > 0) || (m3 > 0 && m4 > 0);
        }
    }

    /** Structured outcome of a single parse run. */
    static final class Analysis {
        public int linkType = -1;
        /** Global header magic recognised as classic pcap. */
        public boolean headerValid;
        /** pcapng container; unsupported by the pure analyzer, reported as such. */
        public boolean isPcapng;
        /** Record link type is not a monitor-mode 802.11 capture. */
        public boolean unsupported;
        /** Stream ended mid-record (file mid-write or cut); see {@link #corruptTail}. */
        public boolean corruptTail;

        public int totalFrames, mgmtFrames, dataFrames, controlFrames, unknownFrames;
        public int eapolFrames;
        /** EAPOL-Key frames of the group (GTK rekey) exchange. */
        public int groupKeyFrames;

        public final java.util.List<AccessPoint> accessPoints = new java.util.ArrayList<>();
        public final java.util.List<Handshake> handshakes = new java.util.ArrayList<>();
    }

    private CaptureAnalyzer() { }

    /**
     * Parses the whole stream. Everything present before a truncation is
     * reported; {@code corruptTail} marks the unusable end, which is
     * expected while tcpdump is still writing the capture.
     */
    static Analysis parse(InputStream input) throws IOException {
        Analysis analysis = new Analysis();
        byte[] global = readExact(input, 24);
        if (global == null) return analysis;

        if (isPcapngMagic(global)) {
            analysis.isPcapng = true;
            return analysis;
        }
        Boolean littleEndian = classicPcapEndianness(global);
        if (littleEndian == null) return analysis;
        analysis.headerValid = true;
        analysis.linkType = (int) u32(global, 20, littleEndian);
        if (analysis.linkType != LINK_IEEE802_11 && analysis.linkType != LINK_IEEE802_11_RADIO) {
            analysis.unsupported = true;
        }

        Map<String, AccessPoint> aps = new LinkedHashMap<>();
        Map<String, Handshake> handshakes = new LinkedHashMap<>();
        byte[] header = new byte[16];
        while (true) {
            int headerRead = fullRead(input, header);
            if (headerRead == 0) break; // clean EOF at a record boundary
            if (headerRead < header.length) { analysis.corruptTail = true; break; }
            int caplen = (int) (u32(header, 8, littleEndian) & 0xffffffffL);
            if (caplen < 0 || caplen > MAX_RECORD_BYTES) { analysis.corruptTail = true; break; }
            byte[] frame = new byte[caplen];
            if (fullRead(input, frame) < caplen) { analysis.corruptTail = true; break; }
            analysis.totalFrames++;
            processFrame(analysis, frame, aps, handshakes);
        }

        analysis.accessPoints.addAll(aps.values());
        analysis.handshakes.addAll(handshakes.values());
        return analysis;
    }

    private static void processFrame(Analysis analysis, byte[] frame,
                                     Map<String, AccessPoint> aps, Map<String, Handshake> handshakes) {
        int off = 0;
        if (analysis.linkType == LINK_IEEE802_11_RADIO) {
            if (frame.length < 4) { analysis.unknownFrames++; return; }
            // Radiotap is little-endian even inside a big-endian pcap.
            int radiotapLength = u16(frame, 2, true);
            if (radiotapLength < 4 || radiotapLength > frame.length) {
                analysis.unknownFrames++;
                return;
            }
            off = radiotapLength;
        }
        if (frame.length - off < 24) { analysis.unknownFrames++; return; }
        byte fc0 = frame[off];
        byte fc1 = frame[off + 1];
        if ((fc0 & 0x03) != 0) { analysis.unknownFrames++; return; } // version != 0

        int type = (fc0 >> 2) & 0x03;
        int subtype = (fc0 >> 4) & 0x0f;
        if (type == 0) {
            analysis.mgmtFrames++;
            parseManagement(analysis, frame, off, subtype, aps);
        } else if (type == 1) {
            analysis.controlFrames++;
        } else if (type == 2) {
            analysis.dataFrames++;
            parseData(analysis, frame, off, fc0, fc1, aps, handshakes);
        } else {
            analysis.unknownFrames++;
        }
    }

    // ---- management ----

    private static void parseManagement(Analysis analysis, byte[] frame, int off,
                                        int subtype, Map<String, AccessPoint> aps) {
        boolean beacon = subtype == 8;
        boolean probeResponse = subtype == 5;
        if (!beacon && !probeResponse) return;

        AccessPoint ap = accessPoint(aps, mac(frame, off + 10));
        if (ap == null) return; // registry full
        if (beacon) {
            ap.beaconFrames++;
            // Privacy bit of the capabilities field (24 header + 8 timestamp
            // + 2 beacon interval). Only valid when the frame is long enough.
            if (frame.length >= off + 36) {
                int capability = u16(frame, off + 24 + 8 + 2, true);
                if ((capability & 0x0010) != 0) ap.security.add(Security.WEP);
                if (!ap.security.contains(Security.WPA1)
                        && !ap.security.contains(Security.WPA2)
                        && !ap.security.contains(Security.WPA3)) {
                    if (ap.security.contains(Security.WEP)) ap.security.remove(Security.OPEN);
                    else ap.security.add(Security.OPEN);
                }
            }
        } else {
            ap.probeResponses++;
        }
        if (frame.length >= off + 36) parseInformationElements(frame, off + 36, ap);
    }

    /** Walks length-tagged IEs once, feeding every interesting tag to the AP. */
    private static void parseInformationElements(byte[] frame, int pos, AccessPoint ap) {
        while (pos + 2 <= frame.length) {
            int tag = frame[pos] & 0xff;
            int len = frame[pos + 1] & 0xff;
            if (pos + 2 + len > frame.length) break; // truncated tail, keep what we have
            if (tag == 0) {                          // SSID
                ap.ssid = len == 0 ? "" : sanitize(byteSlice(frame, pos + 2, len));
            } else if (tag == 3) {                   // DS parameter set
                ap.channel = frame[pos + 2] & 0xff;
            } else if (tag == 48) {                  // RSN (WPA2/WPA3)
                boolean sawSae = containsSequence(frame, pos + 2, len,
                        new byte[]{0x00, 0x0f, (byte) 0xac, 0x08});
                if (sawSae) {
                    ap.security.add(Security.WPA3);
                    ap.security.remove(Security.WEP);
                    ap.security.remove(Security.OPEN);
                } else {
                    ap.security.add(Security.WPA2);
                    ap.security.remove(Security.WEP);
                    ap.security.remove(Security.OPEN);
                }
            } else if (tag == 221) {                 // vendor: Microsoft WPA (OUI 00:50:f2, type 1)
                if (len >= 8 && (frame[pos + 2] & 0xff) == 0x00
                        && (frame[pos + 3] & 0xff) == 0x50
                        && (frame[pos + 4] & 0xff) == 0xf2
                        && (frame[pos + 5] & 0xff) == 0x01) {
                    ap.security.add(Security.WPA1);
                    ap.security.remove(Security.WEP);
                    ap.security.remove(Security.OPEN);
                }
            }
            pos += 2 + len;
        }
    }

    // ---- data ----

    private static void parseData(Analysis analysis, byte[] frame, int off, byte fc0, byte fc1,
                                  Map<String, AccessPoint> aps, Map<String, Handshake> handshakes) {
        boolean toDs = (fc1 & 0x01) != 0;
        boolean fromDs = (fc1 & 0x02) != 0;
        boolean fourAddress = (fc1 & 0x03) == 0x03;
        // The QoS indicator is the highest subtype bit (data subtypes 8-15).
        // The old mask 0x08 is a Type bit that every data frame carries, which
        // made the parser add the 2-byte QoS header to plain data frames too
        // and miss every EAPOL handshake transported in them.
        boolean qos = (fc0 & 0x80) != 0;
        // A QoS data frame carrying the Order bit (fc1 bit 7) also carries a
        // 4-byte HT Control field after the QoS Control field (802.11n).
        int htControl = (qos && (fc1 & 0x80) != 0) ? 4 : 0;
        int headerEnd = off + 24 + (fourAddress ? 6 : 0) + (qos ? 2 : 0) + htControl;

        String addr1 = mac(frame, off + 4);
        String addr2 = mac(frame, off + 10);
        if (fourAddress) return; // WDS/mesh: handshake attribution is ambiguous
        if (!toDs && !fromDs) return;
        // Station to AP: BSSID received in addr1; AP to station: sent from addr2.
        String bssid = toDs ? addr1 : addr2;
        String station = toDs ? addr2 : addr1;

        if (headerEnd + 8 > frame.length) {
            // Too short to carry an LLC/SNAP header, but still a data frame
            // from this station: keep the station visible in the report.
            rememberStation(aps, bssid, station);
            return;
        }
        boolean llcSnap = (frame[headerEnd] & 0xff) == 0xaa
                && (frame[headerEnd + 1] & 0xff) == 0xaa
                && (frame[headerEnd + 2] & 0xff) == 0x03
                && (frame[headerEnd + 3] & 0xff) == 0x00
                && (frame[headerEnd + 4] & 0xff) == 0x00
                && (frame[headerEnd + 5] & 0xff) == 0x00;
        // EtherType is transmitted in network byte order (unlike the 802.11
        // header fields, which are little-endian).
        if (!llcSnap || u16(frame, headerEnd + 6, false) != 0x888e) {
            rememberStation(aps, bssid, station);
            return;
        }
        parseEapolKey(analysis, frame, headerEnd + 8, bssid, station, handshakes);
    }

    /** IEEE 802.1X: EAPOL-Key frames carry the pairwise handshake state. */
    private static void parseEapolKey(Analysis analysis, byte[] frame, int off,
                                      String bssid, String station, Map<String, Handshake> handshakes) {
        analysis.eapolFrames++;
        int remaining = frame.length - off;
        // EAPOL header: version(1) type(1) length(2).
        if (remaining < 4) return;
        if ((frame[off + 1] & 0xff) != 3) return;   // not an EAPOL-Key frame
        // body: descriptor type(1) key information(2) key length(2) replay counter(8)...
        if (remaining < 4 + 3) return;              // key information unreadable
        int body = off + 4;
        int keyInfo = u16(frame, body + 1, false);  // network byte order
        if ((keyInfo & 0x0008) == 0) {              // group key (GTK rekey) exchange
            analysis.groupKeyFrames++;
            return;
        }
        boolean ack = (keyInfo & 0x0040) != 0;
        boolean mic = (keyInfo & 0x0080) != 0;
        boolean secure = (keyInfo & 0x0100) != 0;

        Handshake hs = handshake(handshakes, bssid, station);
        if (hs == null) return;                     // registry full
        hs.frames++;
        if (ack && !mic) hs.m1++;
        else if (ack && mic && secure) hs.m3++;
        else if (!ack && mic) hs.m2++;              // M2 and M4 signature: default M2
        // ACK+MIC without SECURE (rare 802.11r variants) is not classified.
    }

    // ---- helpers ----

    private static void rememberStation(Map<String, AccessPoint> aps, String bssid, String station) {
        AccessPoint ap = accessPoint(aps, bssid);
        if (ap != null && ap.stations.size() < MAX_STATIONS_PER_AP) ap.stations.add(station);
    }

    /** Registry lookup with the hostile-input cap. */
    private static AccessPoint accessPoint(Map<String, AccessPoint> map, String bssid) {
        AccessPoint ap = map.get(bssid);
        if (ap != null) return ap;
        if (map.size() >= MAX_ACCESS_POINTS) return null;
        ap = new AccessPoint(bssid);
        map.put(bssid, ap);
        return ap;
    }

    private static Handshake handshake(Map<String, Handshake> map, String bssid, String station) {
        String key = bssid + "|" + station;
        Handshake hs = map.get(key);
        if (hs != null) return hs;
        if (map.size() >= MAX_HANDSHAKES) return null;
        hs = new Handshake(bssid, station);
        map.put(key, hs);
        return hs;
    }

    private static final String HEX = "0123456789ABCDEF";

    private static String mac(byte[] frame, int off) {
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            int value = frame[off + i] & 0xff;
            if (i > 0) out.append(':');
            out.append(HEX.charAt(value >>> 4));
            out.append(HEX.charAt(value & 0x0f));
        }
        return out.toString();
    }

    /** Control-character and length sanitizing so a capture cannot forge log lines. */
    private static String sanitize(byte[] raw) {
        int max = Math.min(raw.length, 32);
        StringBuilder out = new StringBuilder(max);
        for (int i = 0; i < max; i++) {
            char c = (char) (raw[i] & 0xff);
            out.append(c < 0x20 ? '?' : c);
        }
        return out.toString();
    }

    private static byte[] byteSlice(byte[] frame, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(frame, off, out, 0, len);
        return out;
    }

    private static boolean containsSequence(byte[] frame, int off, int len, byte[] needle) {
        int end = off + len - needle.length;
        for (int i = off; i <= end; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length && match; j++) match = frame[i + j] == needle[j];
            if (match) return true;
        }
        return false;
    }

    private static boolean isPcapngMagic(byte[] global) {
        return (global[0] & 0xff) == 0x0a && (global[1] & 0xff) == 0x0d
                && (global[2] & 0xff) == 0x0d && (global[3] & 0xff) == 0x0a;
    }

    /** null when the magic is none of the four classic pcap variants. */
    private static Boolean classicPcapEndianness(byte[] global) {
        if ((global[0] & 0xff) == 0xa1 && (global[1] & 0xff) == 0xb2
                && (global[2] & 0xff) == 0xc3 && (global[3] & 0xff) == 0xd4) return false;
        if ((global[0] & 0xff) == 0xd4 && (global[1] & 0xff) == 0xc3
                && (global[2] & 0xff) == 0xb2 && (global[3] & 0xff) == 0xa1) return true;
        if ((global[0] & 0xff) == 0xa1 && (global[1] & 0xff) == 0xb2
                && (global[2] & 0xff) == 0x3c && (global[3] & 0xff) == 0x4d) return false;
        if ((global[0] & 0xff) == 0x4d && (global[1] & 0xff) == 0x3c
                && (global[2] & 0xff) == 0xb2 && (global[3] & 0xff) == 0xa1) return true;
        return null;
    }

    private static int u16(byte[] data, int off, boolean little) {
        return little
                ? (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8)
                : ((data[off] & 0xff) << 8) | (data[off + 1] & 0xff);
    }

    private static long u32(byte[] data, int off, boolean little) {
        if (little) {
            return (data[off] & 0xffL) | ((data[off + 1] & 0xffL) << 8)
                    | ((data[off + 2] & 0xffL) << 16) | ((data[off + 3] & 0xffL) << 24);
        }
        return ((data[off] & 0xffL) << 24) | ((data[off + 1] & 0xffL) << 16)
                | ((data[off + 2] & 0xffL) << 8) | (data[off + 3] & 0xffL);
    }

    /** Full read of exactly {@code len} bytes; null when EOF is reached first. */
    private static byte[] readExact(InputStream input, int len) throws IOException {
        byte[] out = new byte[len];
        int pos = 0;
        while (pos < len) {
            int n = input.read(out, pos, len - pos);
            if (n < 0) return null;
            if (n == 0) return null;
            pos += n;
        }
        return out;
    }

    /** Read up to {@code data.length} bytes; returns how much was actually read. */
    private static int fullRead(InputStream input, byte[] data) throws IOException {
        int pos = 0;
        while (pos < data.length) {
            int n = input.read(data, pos, data.length - pos);
            if (n < 0) break;
            pos += n;
        }
        return pos;
    }
}
