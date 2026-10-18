package me.paranoid.wifikill;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.Assert.assertEquals;

public class CaptureFileInspectorTest {
    private static byte[] header(int length, int... initial) {
        byte[] value = new byte[length];
        for (int i = 0; i < initial.length && i < value.length; i++) value[i] = (byte) initial[i];
        return value;
    }

    @Test public void detectsClassicPcapEndiannessAndTimestampVariants() throws IOException {
        assertEquals(CaptureFileInspector.Format.PCAP, CaptureFileInspector.detect(
                new ByteArrayInputStream(header(24, 0xa1, 0xb2, 0xc3, 0xd4))));
        assertEquals(CaptureFileInspector.Format.PCAP, CaptureFileInspector.detect(
                new ByteArrayInputStream(header(24, 0xd4, 0xc3, 0xb2, 0xa1))));
        assertEquals(CaptureFileInspector.Format.PCAP, CaptureFileInspector.detect(
                new ByteArrayInputStream(header(24, 0xa1, 0xb2, 0x3c, 0x4d))));
        assertEquals(CaptureFileInspector.Format.PCAP, CaptureFileInspector.detect(
                new ByteArrayInputStream(header(24, 0x4d, 0x3c, 0xb2, 0xa1))));
    }

    @Test public void detectsPcapNgAndRejectsInvalidHeaders() throws IOException {
        assertEquals(CaptureFileInspector.Format.PCAPNG, CaptureFileInspector.detect(
                new ByteArrayInputStream(header(24, 0x0a, 0x0d, 0x0d, 0x0a))));
        assertEquals(CaptureFileInspector.Format.UNKNOWN, CaptureFileInspector.detect(
                new ByteArrayInputStream(header(24, 0x01, 0x02, 0x03, 0x04))));
        assertEquals(CaptureFileInspector.Format.UNKNOWN, CaptureFileInspector.detect(
                new ByteArrayInputStream(header(4, 0xd4, 0xc3, 0xb2, 0xa1))));
    }
}
