package com.opencode.ide.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/** Pure body-building tests for {@link McpRequests}. */
public class McpRequestsTest {

    @Test
    public void minimalRemoteConfigHasTypeUrlAndOauthOff() {
        String body = McpRequests.registerBody("eclipse-build", McpServerConfig.enabled("http://127.0.0.1:1/mcp"));
        assertFalse("v2 carries the name in the PUT path, not the body", body.contains("\"name\""));
        assertTrue(body.contains("\"config\":{"));
        assertTrue(body.contains("\"type\":\"remote\""));
        assertTrue(body.contains("\"url\":\"http://127.0.0.1:1/mcp\""));
        assertTrue(body.contains("\"enabled\":true"));
        assertTrue(body.contains("\"oauth\":false"));
    }

    @Test
    public void headersAreIncludedWhenPresent() {
        McpServerConfig config = new McpServerConfig("http://127.0.0.1:1/mcp",
                Map.of("Authorization", "Bearer x"), null);
        String body = McpRequests.registerBody("named", config);
        assertTrue(body.contains("\"Authorization\":\"Bearer x\""));
        assertFalse("enabled must be omitted when null", body.contains("\"enabled\""));
    }
}
