package me.paranoid.wifikill;

import org.junit.Test;
import static org.junit.Assert.*;

public class RootProcessTest {

    @Test public void simpleCommandsMayBeExecutedWithExec() {
        assertTrue(RootProcess.isSimpleCommand("aireplay-ng --deauth 10 -a aa:bb:cc:dd:ee:ff -i wfkmon0"));
        assertTrue(RootProcess.isSimpleCommand("hostapd /data/user/0/app/files/hostapd.conf"));
        assertTrue(RootProcess.isSimpleCommand("aircrack-ng -w /data/local/tmp/w.txt /data/local/tmp/c.pcap"));
    }

    @Test public void compoundCommandsMustNotBeExecutedWithExec() {
        assertFalse(RootProcess.isSimpleCommand("aireplay-ng --deauth 10 | tee log"));
        assertFalse(RootProcess.isSimpleCommand("cmd1; cmd2"));
        assertFalse(RootProcess.isSimpleCommand("cmd > /dev/null"));
        assertFalse(RootProcess.isSimpleCommand("cmd `id`"));
        assertFalse(RootProcess.isSimpleCommand("cmd $(id)"));
        assertFalse(RootProcess.isSimpleCommand(""));
        assertFalse(RootProcess.isSimpleCommand(null));
    }
}
