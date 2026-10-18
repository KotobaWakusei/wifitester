package me.paranoid.wifikill;

import java.io.IOException;
import java.io.InputStream;

/** Validates the magic and minimum header length of supported packet-capture containers. */
final class CaptureFileInspector {
    static final long MAX_FILE_BYTES = 200L * 1024L * 1024L;

    enum Format {
        PCAP("PCAP"),
        PCAPNG("PCAPNG"),
        UNKNOWN("未知或不支持的抓包格式");

        final String label;
        Format(String label) { this.label = label; }
    }

    private CaptureFileInspector() { }

    static Format detect(InputStream input) throws IOException {
        if (input == null) return Format.UNKNOWN;
        byte[] header = new byte[24];
        int count = 0;
        while (count < header.length) {
            int n = input.read(header, count, header.length - count);
            if (n < 0) break;
            if (n == 0) continue;
            count += n;
        }
        if (count >= 4 && isPcapNgMagic(header)) {
            return count >= 12 ? Format.PCAPNG : Format.UNKNOWN;
        }
        if (count >= 24 && isClassicPcapMagic(header)) return Format.PCAP;
        return Format.UNKNOWN;
    }

    private static boolean isPcapNgMagic(byte[] h) {
        return (h[0] & 255) == 0x0a && (h[1] & 255) == 0x0d
                && (h[2] & 255) == 0x0d && (h[3] & 255) == 0x0a;
    }

    private static boolean isClassicPcapMagic(byte[] h) {
        return ((h[0] & 255) == 0xa1 && (h[1] & 255) == 0xb2
                    && (h[2] & 255) == 0xc3 && (h[3] & 255) == 0xd4)
                || ((h[0] & 255) == 0xd4 && (h[1] & 255) == 0xc3
                    && (h[2] & 255) == 0xb2 && (h[3] & 255) == 0xa1)
                || ((h[0] & 255) == 0xa1 && (h[1] & 255) == 0xb2
                    && (h[2] & 255) == 0x3c && (h[3] & 255) == 0x4d)
                || ((h[0] & 255) == 0x4d && (h[1] & 255) == 0x3c
                    && (h[2] & 255) == 0xb2 && (h[3] & 255) == 0xa1);
    }
}
