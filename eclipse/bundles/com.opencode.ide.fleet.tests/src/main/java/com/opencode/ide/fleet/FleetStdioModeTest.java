package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Truth table of {@link FleetStdioMain#decide(String, boolean)}: the pure
 * FLEET_DAEMON mode decision, no processes or pidfiles involved.
 */
public class FleetStdioModeTest {

    @Test
    public void offOwnsTheEngineEvenWhenADaemonIsLive() {
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("off", true));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("off", false));
    }

    @Test
    public void unsetOrBlankBehavesAsOff() {
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide(null, false));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide(null, true));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("", true));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("   ", true));
    }

    @Test
    public void autoProxiesOnlyWithALiveDaemon() {
        assertEquals(FleetStdioMain.StdioMode.PROXY, FleetStdioMain.decide("auto", true));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("auto", false));
    }

    @Test
    public void alwaysProxiesOrFailsFast() {
        assertEquals(FleetStdioMain.StdioMode.PROXY, FleetStdioMain.decide("always", true));
        assertEquals(FleetStdioMain.StdioMode.FAIL, FleetStdioMain.decide("always", false));
    }

    @Test
    public void valuesAreCaseInsensitiveAndSurroundingWhitespaceTolerated() {
        assertEquals(FleetStdioMain.StdioMode.PROXY, FleetStdioMain.decide("AUTO", true));
        assertEquals(FleetStdioMain.StdioMode.PROXY, FleetStdioMain.decide(" Always ", true));
        assertEquals(FleetStdioMain.StdioMode.FAIL, FleetStdioMain.decide("ALWAYS", false));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("OFF", true));
        assertEquals(FleetStdioMain.StdioMode.PROXY, FleetStdioMain.decide("auto\t", true));
    }

    @Test
    public void unrecognizedValuesBehaveAsOff() {
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("yes", true));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("1", true));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("daemon", false));
        assertEquals(FleetStdioMain.StdioMode.OWN_ENGINE, FleetStdioMain.decide("automatic", true));
    }
}
