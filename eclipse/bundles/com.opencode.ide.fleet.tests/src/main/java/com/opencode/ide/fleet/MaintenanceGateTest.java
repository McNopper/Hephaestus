package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * U-038 maintenance gate (the shutdown's first ordered step): one flag file
 * under the fleet root engages/disengages dispatch refusals, the reason
 * travels to the caller, and an absent flag reads as open for dispatch.
 */
public class MaintenanceGateTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path root;

    @Before
    public void setUp() throws Exception {
        root = folder.newFolder("opencode-fleet").toPath();
    }

    @Test
    public void anAbsentFlagReadsAsOpenForDispatch() {
        assertNull(MaintenanceGate.reason(root));
        assertFalse(MaintenanceGate.isActive(root));
    }

    @Test
    public void engageWritesTheReasonAndClearRemovesIt() throws Exception {
        MaintenanceGate.engage(root, "JDK upgrade on the host");

        assertTrue(MaintenanceGate.isActive(root));
        assertEquals("JDK upgrade on the host", MaintenanceGate.reason(root));

        MaintenanceGate.clear(root);

        assertFalse(MaintenanceGate.isActive(root));
        assertNull(MaintenanceGate.reason(root));
    }

    @Test
    public void clearIsIdempotentAndAFilelessRootIsTolerated() throws Exception {
        MaintenanceGate.clear(root);
        MaintenanceGate.clear(root);
        assertNull(MaintenanceGate.reason(null));
        assertTrue("engage creates the fleet root when missing",
                Files.isDirectory(root));
    }

    @Test
    public void aBlankReasonFallsBackToThePlainLabel() throws Exception {
        MaintenanceGate.engage(root, "   ");
        assertEquals("maintenance", MaintenanceGate.reason(root));
    }
}
