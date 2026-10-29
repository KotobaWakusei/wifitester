package me.paranoid.wifikill;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Parser and selection rules behind the attack workbench target autofill. */
public class AttackTargetResolverTest {

    private static final String ROUTE = "default via 192.168.1.1 dev wlan0 proto static\n"
            + "default via 10.0.0.1 dev rmnet_data0\n";

    private static final String ADDRESS = ""
            + "34: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n"
            + "    link/ether 06:bd:59:8a:c9:76 brd ff:ff:ff:ff:ff:ff\n"
            + "    inet 192.168.1.104/24 brd 192.168.1.255 scope global wlan0\n"
            + "       valid_lft forever preferred_lft forever\n"
            + "20: rmnet_data0: <UP> mtu 9216\n"
            + "    inet 10.179.34.228/30 scope global rmnet_data0\n";

    private static final String NEIGHBOURS = ""
            + "192.168.1.1 dev wlan0 lladdr 3c:cd:57:f5:e8:15 REACHABLE\n"
            + "192.168.1.104 dev wlan0 lladdr 06:bd:59:8a:c9:76 STALE\n"
            + "192.168.1.200 dev wlan0  FAILED\n"
            + "192.168.1.77 dev wlan0 lladdr a1:b2:c3:d4:e5:f6 DELAY\n";

    private static final String SCAN = ""
            + "BSS 3c:cd:57:f5:e8:16(on wlan0) -- associated\n"
            + "\tlast seen: 12 ms ago\n"
            + "\tfreq: 2437\n"
            + "\tSSID: ChinaNet-403\n"
            + "\tsignal: -40.00 dBm\n"
            + "\tDS Parameter set: channel 6\n"
            + "BSS 74:05:a5:15:11:31(on wlan0)\n"
            + "\tfreq: 2412\n"
            + "\tSSID: \n"
            + "\tsignal: -60.00 dBm\n"
            + "\tDS Parameter set: channel 1\n"
            + "BSS 3c:cd:57:f5:e8:15(on wlan0)\n"
            + "\tfreq: 5240\n"
            + "\tSSID: ChinaNet-403-5G\n"
            + "\tsignal: -46.00 dBm\n"
            + "\t* channel 48\n"
            + "\t* channel width: 3 (80 MHz)\n";

    @Test public void decodesIwEscapedUtf8Ssids() {
        // Real output of "iw dev wlan0 scan" on Android: 勇 == E5 8B 87.
        assertEquals("勇", AttackTargetResolver.decodeIwdEscapes("\\xe5\\x8b\\x87"));
        assertEquals("TP-LINK_79D3", AttackTargetResolver.decodeIwdEscapes("TP-LINK_79D3"));
        assertEquals("", AttackTargetResolver.decodeIwdEscapes(null));
        // A stray escape must not turn into a replacement character.
        assertEquals("\\xzz", AttackTargetResolver.decodeIwdEscapes("\\xzz"));
        String scan = "BSS e2:8d:4e:10:f3:26(on wlan0)\n"
                + "\tsignal: -75.00 dBm\n"
                + "\tSSID: \\xe5\\x8b\\x87\n"
                + "\tDS Parameter set: channel 1\n";
        List<AttackTargetResolver.AccessPoint> points = AttackTargetResolver.parseScan(scan);
        assertEquals(1, points.size());
        assertEquals("勇", points.get(0).ssid);
    }

    @Test public void picksTheWifiAddressEvenWhenMobileIsListedFirst() {
        // Real Android 16 layout: vgate0 and rmnet come before wlan0.
        String android = ""
                + "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536\n"
                + "    inet 127.0.0.1/8 scope host lo\n"
                + "31: vgate0: <POINTOPOINT,MULTICAST> mtu 65500\n"
                + "    inet 172.30.228.212/32 scope global vgate0\n"
                + "32: rmnet_data4@rmnet_ipa0: <UP,LOWER_UP> mtu 1410\n"
                + "    inet 10.83.146.62/30 scope global rmnet_data4\n"
                + "74: wlan0: <BROADCAST,MULTICAST,UP> mtu 1500\n"
                + "    inet 192.168.1.104/24 brd 192.168.1.255 scope global wlan0\n";
        AttackTargetResolver.LocalNetwork network = AttackTargetResolver.parseLocalNetwork(
                "default via 192.168.1.1 dev wlan0 table wlan0 proto static\n", android);
        assertEquals("192.168.1.104", network.localAddress);
        assertEquals(24, network.prefixLength);
        // Without a matching interface the first non-loopback address is used.
        AttackTargetResolver.LocalNetwork noRoute = AttackTargetResolver.parseLocalNetwork("", android);
        assertEquals("172.30.228.212", noRoute.localAddress);
    }

    @Test public void parsesGatewayInterfaceAddressAndPrefix() {
        AttackTargetResolver.LocalNetwork network =
                AttackTargetResolver.parseLocalNetwork(ROUTE, ADDRESS);
        assertEquals("192.168.1.1", network.gateway);
        assertEquals("wlan0", network.routeInterface);
        assertEquals("192.168.1.104", network.localAddress);
        assertEquals(24, network.prefixLength);
        assertTrue(network.hasGateway());
    }

    @Test public void prefersTheWifiDefaultRouteOverTheMobileOne() {
        // Real Android 16 output: one table per network, no plain default.
        String android = ""
                + "default via 10.83.146.61 dev rmnet_data4 table rmnet_data4 proto static mtu 1410\n"
                + "default via 192.168.1.1 dev wlan0 table wlan0 proto static\n";
        AttackTargetResolver.LocalNetwork network =
                AttackTargetResolver.parseLocalNetwork(android, ADDRESS);
        assertEquals("192.168.1.1", network.gateway);
        assertEquals("wlan0", network.routeInterface);

        // Only a mobile default exists: it is still better than nothing.
        AttackTargetResolver.LocalNetwork mobile = AttackTargetResolver.parseLocalNetwork(
                "default via 10.83.146.61 dev rmnet_data4 table rmnet_data4 proto static\n", ADDRESS);
        assertEquals("10.83.146.61", mobile.gateway);
        assertEquals("rmnet_data4", mobile.routeInterface);
    }

    @Test public void missingRouteLeavesEverythingEmpty() {
        AttackTargetResolver.LocalNetwork network =
                AttackTargetResolver.parseLocalNetwork("", ADDRESS);
        assertFalse(network.hasGateway());
        assertEquals("", network.gateway);
        // The address is still usable even without a route.
        assertEquals("192.168.1.104", network.localAddress);
    }

    @Test public void parsesNeighboursAndSkipsUnresolvedEntries() {
        List<String> neighbours = AttackTargetResolver.parseNeighbours(NEIGHBOURS, "192.168.1.104");
        assertEquals(Arrays.asList("192.168.1.1", "192.168.1.77"), neighbours);
        assertTrue(AttackTargetResolver.parseNeighbours(null, null).isEmpty());
    }

    @Test public void parsesScanIntoAccessPoints() {
        List<AttackTargetResolver.AccessPoint> points = AttackTargetResolver.parseScan(SCAN);
        assertEquals(2, points.size());
        AttackTargetResolver.AccessPoint first = points.get(0);
        assertEquals("3c:cd:57:f5:e8:16", first.bssid);
        assertEquals("ChinaNet-403", first.ssid);
        assertEquals(6, first.channel);
        assertEquals(-40, first.rssi);
        // The 5 GHz entry has no DS parameter set; its primary channel is used.
        assertEquals("ChinaNet-403-5G", points.get(1).ssid);
        assertEquals(48, points.get(1).channel);
        assertTrue(AttackTargetResolver.parseScan("").isEmpty());
        assertTrue(AttackTargetResolver.parseScan(null).isEmpty());
    }

    @Test public void choosesTheRequestedTargetThenTheStrongest() {
        List<AttackTargetResolver.AccessPoint> points = AttackTargetResolver.parseScan(SCAN);
        // An exact BSSID wins even when a stronger network is visible.
        assertEquals("ChinaNet-403-5G",
                AttackTargetResolver.chooseTarget(points, "3C:CD:57:F5:E8:15", "").ssid);
        // Without a preference the strongest signal wins: -40 over -46.
        assertEquals("ChinaNet-403", AttackTargetResolver.chooseTarget(points, "", "").ssid);
        // A stale SSID/BSSID from another screen must not select a wrong AP.
        assertEquals("ChinaNet-403-5G",
                AttackTargetResolver.chooseTarget(points, "aa:bb:cc:dd:ee:ff", "ChinaNet-403-5G").ssid);
        assertEquals("ChinaNet-403",
                AttackTargetResolver.chooseTarget(points, "aa:bb:cc:dd:ee:ff", "").ssid);
        assertNull(AttackTargetResolver.chooseTarget(Collections.emptyList(), "", ""));
        assertNull(AttackTargetResolver.chooseTarget(null, "", ""));
    }

    @Test public void expandsTheSubnetWithoutNetworkBroadcastOrSelf() {
        AttackTargetResolver.LocalNetwork network =
                AttackTargetResolver.parseLocalNetwork(ROUTE, ADDRESS);
        List<String> hosts = AttackTargetResolver.subnetHosts(network);
        assertEquals(253, hosts.size());
        assertEquals("192.168.1.1", hosts.get(0));
        assertEquals("192.168.1.254", hosts.get(hosts.size() - 1));
        assertFalse(hosts.contains("192.168.1.104"));
        assertFalse(hosts.contains("192.168.1.0"));
        assertFalse(hosts.contains("192.168.1.255"));
    }

    @Test public void prefersARealNeighbourAndSkipsGatewayAndSelf() {
        AttackTargetResolver.LocalNetwork network =
                AttackTargetResolver.parseLocalNetwork(ROUTE, ADDRESS);
        assertEquals("192.168.1.77",
                AttackTargetResolver.chooseVictim(network,
                        AttackTargetResolver.parseNeighbours(NEIGHBOURS, "192.168.1.104")));
        // No neighbours: fall back to the subnet, still never the gateway.
        assertEquals("192.168.1.2", AttackTargetResolver.chooseVictim(network, new ArrayList<>()));
        assertEquals("", AttackTargetResolver.chooseVictim(null, null));
        // A /30 has a single usable host; when it is only the self address the
        // result stays empty instead of pointing at the device itself.
        AttackTargetResolver.LocalNetwork empty = new AttackTargetResolver.LocalNetwork();
        assertEquals("", AttackTargetResolver.chooseVictim(empty, null));
    }

    @Test public void ignoresAddressesThatAreNotIpv4() {
        assertEquals(-1, AttackTargetResolver.ipv4ToLong("192.168.1"));
        assertEquals(-1, AttackTargetResolver.ipv4ToLong("192.168.1.256"));
        assertEquals(-1, AttackTargetResolver.ipv4ToLong(null));
        assertEquals(3232235777L, AttackTargetResolver.ipv4ToLong("192.168.1.1"));
        assertEquals("192.168.1.1", AttackTargetResolver.longToIpv4(3232235777L));
    }

    @Test public void unusablePrefixNeverExpandsToAFullSweep() {
        AttackTargetResolver.LocalNetwork network = new AttackTargetResolver.LocalNetwork();
        network.localAddress = "10.0.0.1";
        network.prefixLength = 8;
        List<String> hosts = AttackTargetResolver.subnetHosts(network);
        assertTrue("a /8 must be capped", hosts.size() <= 254);
        network.prefixLength = 4;
        assertTrue(AttackTargetResolver.subnetHosts(network).isEmpty());
    }
}
