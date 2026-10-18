package me.paranoid.wifikill;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class InterfaceStateParserTest {
    @Test
    public void recognizesExactUpFlagAlongsideLowerUp() {
        assertEquals("up", InterfaceStateParser.parseAdministrativeState(
                "3: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP"));
    }

    @Test
    public void doesNotMistakeLowerUpForAdministrativeUp() {
        assertEquals("down", InterfaceStateParser.parseAdministrativeState(
                "3: wlan0: <BROADCAST,MULTICAST,LOWER_UP> mtu 1500 state DOWN"));
    }

    @Test
    public void recognizesDownWhenUpFlagIsAbsent() {
        assertEquals("down", InterfaceStateParser.parseAdministrativeState(
                "3: wlan0: <NO-CARRIER,BROADCAST,MULTICAST> mtu 1500 state DOWN"));
    }

    @Test
    public void returnsNullWhenFlagsCannotBeParsed() {
        assertNull(InterfaceStateParser.parseAdministrativeState("unexpected output"));
        assertNull(InterfaceStateParser.parseAdministrativeState(null));
    }
}
