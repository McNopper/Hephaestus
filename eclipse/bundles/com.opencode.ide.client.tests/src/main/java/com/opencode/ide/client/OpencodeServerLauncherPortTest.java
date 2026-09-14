package com.opencode.ide.client;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.Test;

/**
 * Regression tests for {@link OpencodeServerLauncher}'s fixed-port handling:
 * a configured port already occupied by a stranger (typically a stale orphaned
 * {@code opencode serve} from a crashed session) must fail fast with a message
 * naming the cause - never silently authenticate against a foreign server and
 * surface endless HTTP 401s instead.
 */
public class OpencodeServerLauncherPortTest {

    @Test
    public void occupiedFixedPortFailsFastWithAClearMessage() throws Exception {
        try (ServerSocket stranger = new ServerSocket()) {
            stranger.bind(new InetSocketAddress("127.0.0.1", 0));
            int occupied = stranger.getLocalPort();

            OpencodeServerLauncher launcher = new OpencodeServerLauncher(
                    "opencode-does-not-need-to-exist-for-this-test", "127.0.0.1", occupied,
                    null, "secret");
            try {
                launcher.start(java.time.Duration.ofMillis(200));
                fail("start() must refuse a configured port that is already occupied");
            } catch (OpencodeConnectionException e) {
                String message = String.valueOf(e.getMessage());
                assertNotNull(message);
                assertTrue("message should name the port: " + message,
                        message.contains(String.valueOf(occupied)));
                assertTrue("message should point at a stale server: " + message,
                        message.contains("opencode serve"));
                assertTrue("no server process may have been spawned", launcher.getProcessId() == null);
            }
        }
    }
}
