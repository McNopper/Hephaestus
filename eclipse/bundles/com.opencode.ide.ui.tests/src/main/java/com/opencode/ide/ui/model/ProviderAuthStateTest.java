package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.ProviderAuth;

/**
 * Unit tests for the SWT-free {@link ProviderAuthState} model behind the
 * Providers view's auth wiring: per-provider method aggregation from
 * {@code GET /api/integration} (labels with type fallback), the lenient
 * degradation to an empty map, and the authenticated flag/methods of a
 * state. No SWT, no JFace, no Display.
 */
public class ProviderAuthStateTest {

    // ---------- fixtures ----------

    private static ProviderAuth auth(String provider, String type, String label) {
        return new ProviderAuth(provider, type, label);
    }

    /** Only the provider-auth endpoint works; everything else is never touched. */
    private static final class FakeClient extends com.opencode.ide.ui.testsupport.StubClient {
        List<ProviderAuth> auths = List.of();
        boolean throwOnAuths;
        String authDirectory;

        @Override
        public List<ProviderAuth> getProviderAuths(String directory) throws OpencodeException {
            authDirectory = directory;
            return getProviderAuths();
        }

        @Override
        public List<ProviderAuth> getProviderAuths() throws OpencodeException {
            if (throwOnAuths) {
                throw new OpencodeException("auth endpoint gone");
            }
            return auths;
        }

    }

    // ---------- load ----------

    @Test
    public void loadAggregatesMethodsByProviderInWireOrder() {
        FakeClient client = new FakeClient();
        client.auths = List.of(
                auth("zai", "oauth", "Z.AI"),
                auth("zai", "key", "API key"),
                auth("github", "oauth", "GitHub"));

        Map<String, ProviderAuthState> states = ProviderAuthState.load(client);

        assertEquals(2, states.size());
        assertTrue(states.get("zai").authenticated());
        assertEquals(List.of("Z.AI", "API key"), states.get("zai").methods());
        assertTrue(states.get("github").authenticated());
        assertEquals(List.of("GitHub"), states.get("github").methods());
    }

    @Test
    public void methodsFallBackToTypeWhenLabelAbsent() {
        FakeClient client = new FakeClient();
        client.auths = List.of(auth("zai", "oauth", null));

        assertEquals(List.of("oauth"), ProviderAuthState.load(client).get("zai").methods());
    }

    @Test
    public void loadUsesTheViewsProjectScope() {
        FakeClient client = new FakeClient();
        client.auths = List.of(auth("zai", "key", null));

        Map<String, ProviderAuthState> states = ProviderAuthState.load(client, "C:/repo");

        assertEquals("C:/repo", client.authDirectory);
        assertEquals(List.of("key"), states.get("zai").methods());
    }

    @Test
    public void unusableEntriesAreSkipped() {
        // Arrays.asList (not List.of): the fixture intentionally contains null
        FakeClient client = new FakeClient();
        client.auths = Arrays.asList(
                null,
                auth(null, "oauth", "x"),
                auth("   ", "oauth", "y"),
                auth("zai", null, null));   // neither label nor type: no renderable method

        Map<String, ProviderAuthState> states = ProviderAuthState.load(client);

        assertTrue(states.isEmpty());
    }

    @Test
    public void endpointFailureDegradesToEmptyMap() {
        FakeClient client = new FakeClient();
        client.throwOnAuths = true;

        assertEquals(Map.of(), ProviderAuthState.load(client));
    }

    @Test
    public void nullClientNullListAndEmptyListDegradeToEmptyMap() {
        assertEquals(Map.of(), ProviderAuthState.load(null));
        FakeClient client = new FakeClient();
        client.auths = null;
        assertEquals(Map.of(), ProviderAuthState.load(client));
        client.auths = List.of();
        assertEquals(Map.of(), ProviderAuthState.load(client));
    }

    // ---------- state ----------

    @Test
    public void noMethodsMeansNotAuthenticated() {
        ProviderAuthState none = ProviderAuthState.of(List.of());

        assertFalse(none.authenticated());
        assertEquals(List.of(), none.methods());
    }

    @Test
    public void methodsAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> ProviderAuthState.of(List.of("oauth")).methods().add("key"));
    }
}
