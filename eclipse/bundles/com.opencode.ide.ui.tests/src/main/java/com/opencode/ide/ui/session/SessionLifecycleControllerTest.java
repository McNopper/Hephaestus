package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.ui.session.SessionDetailsController.LifecycleResult;

/**
 * Unit tests for the session lifecycle actions of
 * {@link SessionDetailsController} (fork / summarize) with a fake
 * {@link OpencodeClient}: no HTTP, no SWT. Like {@code load()}, the actions
 * never throw — failures come back as {@link LifecycleResult#error()}.
 *
 * <p>Share/unshare is not covered because it no longer exists: opencode v2
 * serves no session share endpoint.</p>
 */
public class SessionLifecycleControllerTest {

    private static final Session.Time TIME = new Session.Time(1L, 1L, 0L);

    private final FakeClient client = new FakeClient();

    // ---------- fixtures ----------

    private static ChatMessageInfo assistant(String providerId, String modelId) {
        return new ChatMessageInfo("a1", "ses_1", "assistant", TIME, "build", "primary", "stop",
                null, null, providerId, modelId, "high", null, 0L);
    }

    private static ProviderList providers() {
        Model model = new Model("glm-5.2", null, "zai", null, "GLM 5.2", null, null, null, null,
                null, null, null, null, null);
        Provider provider = new Provider("zai", "ZAI", "api", List.of(), null, Map.of(),
                Map.of("glm-5.2", model));
        return new ProviderList(List.of(provider), null);
    }

    // ---------- fork ----------

    @Test
    public void forkReturnsNewSessionId() {
        LifecycleResult result = new SessionDetailsController("ses_1", () -> client).fork(null);

        assertTrue(result.success());
        assertEquals("ses_fork", result.detail());
        assertNull(result.error());
        assertNull("fork at latest message passes no messageID", client.lastForkMessageId);
    }

    @Test
    public void forkPassesMessageIdThrough() {
        new SessionDetailsController("ses_1", () -> client).fork("msg_2");

        assertEquals("msg_2", client.lastForkMessageId);
    }

    @Test
    public void forkFailureIsReportedNotThrown() {
        client.throwOn = "fork";

        LifecycleResult result = new SessionDetailsController("ses_1", () -> client).fork(null);

        assertFalse(result.success());
        assertEquals("fork boom", result.error());
    }

    // ---------- summarize ----------

    @Test
    public void summarizeUsesTheTrackedAssistantModel() {
        client.messages = List.of(new ChatEntry(assistant("zai", "glm-5.3"), List.of()));
        client.throwOnConfigOrProviders = true; // fallback must not be consulted
        SessionDetailsController controller = new SessionDetailsController("ses_1", () -> client);
        controller.load(); // tracks the assistant model

        LifecycleResult result = controller.summarize();

        assertTrue(result.success());
        assertEquals("zai/glm-5.3", result.detail());
        assertEquals("zai", client.lastSummarizeProvider);
        assertEquals("glm-5.3", client.lastSummarizeModel);
    }

    @Test
    public void summarizeFallsBackToTheConnectionDefault() {
        client.config = new ConfigInfo("zai/glm-5.2", null);
        client.providerList = providers();
        SessionDetailsController controller = new SessionDetailsController("ses_1", () -> client);
        controller.load(); // no assistant message -> nothing tracked

        LifecycleResult result = controller.summarize();

        assertTrue(result.success());
        assertEquals("zai/glm-5.2", result.detail());
        assertEquals("zai", client.lastSummarizeProvider);
        assertEquals("glm-5.2", client.lastSummarizeModel);
    }

    @Test
    public void summarizeDeclinedByServerIsAFailure() {
        client.config = new ConfigInfo("zai/glm-5.2", null);
        client.providerList = providers();
        client.summarizeReturns = false;

        LifecycleResult result = new SessionDetailsController("ses_1", () -> client).summarize();

        assertFalse(result.success());
        assertEquals("server declined to summarize", result.error());
    }

    @Test
    public void summarizeWithoutAnyResolvableModelIsAFailure() {
        client.config = null;
        client.providerList = null;

        LifecycleResult result = new SessionDetailsController("ses_1", () -> client).summarize();

        assertFalse(result.success());
        assertEquals("no provider/model available", result.error());
    }

    @Test
    public void summarizeFailureIsReportedNotThrown() {
        client.config = new ConfigInfo("zai/glm-5.2", null);
        client.providerList = providers();
        client.throwOn = "summarize";

        LifecycleResult result = new SessionDetailsController("ses_1", () -> client).summarize();

        assertFalse(result.success());
        assertEquals("summarize boom", result.error());
    }

    // ---------- pure helpers ----------

    @Test
    public void pickSummarizeModelPrefersACompleteTrackedModel() {
        assertEquals("the session's model wins over the configured default",
                new String[] { "zai", "glm-5.3" },
                SessionDetailsController.pickSummarizeModel(new String[] { "zai", "glm-5.3" },
                        new ConfigInfo("anthropic/claude", null), providers()));
    }

    @Test
    public void pickSummarizeModelFallsBackWhenTrackedIsMissing() {
        ConfigInfo config = new ConfigInfo("zai/glm-5.2", null);
        ProviderList providers = providers();

        assertEquals("null tracked", new String[] { "zai", "glm-5.2" },
                SessionDetailsController.pickSummarizeModel(null, config, providers));
        assertEquals("blank provider", new String[] { "zai", "glm-5.2" },
                SessionDetailsController.pickSummarizeModel(new String[] { " ", "glm-5.3" }, config,
                        providers));
        assertEquals("null model", new String[] { "zai", "glm-5.2" },
                SessionDetailsController.pickSummarizeModel(new String[] { "zai", null }, config,
                        providers));
        assertNull("nothing resolvable", SessionDetailsController.pickSummarizeModel(null, null, null));
    }

    // ---------- fake client (lifecycle surface works; the rest throws) ----------

    private static final class FakeClient extends com.opencode.ide.ui.testsupport.StubClient {
        List<ChatEntry> messages = List.of();
        ConfigInfo config;
        ProviderList providerList;
        boolean summarizeReturns = true;
        /** When set to "fork"/"summarize", that call throws. */
        String throwOn;
        boolean throwOnConfigOrProviders;

        String lastForkMessageId;
        String lastSummarizeProvider;
        String lastSummarizeModel;

        @Override
        public Session forkSession(String sessionId, String messageId) throws OpencodeException {
            lastForkMessageId = messageId;
            if ("fork".equals(throwOn)) {
                throw new OpencodeException("fork boom");
            }
            return new Session("ses_fork", null, "Fork of ses_1", null, null, null, null, null,
                    null, null, null);
        }

        @Override
        public boolean summarizeSession(String sessionId, String providerId, String modelId)
                throws OpencodeException {
            lastSummarizeProvider = providerId;
            lastSummarizeModel = modelId;
            if ("summarize".equals(throwOn)) {
                throw new OpencodeException("summarize boom");
            }
            return summarizeReturns;
        }

        @Override
        public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
            return messages;
        }

        @Override
        public List<Session> getSessions() throws OpencodeException {
            return List.of();
        }

        @Override
        public ConfigInfo getConfig() throws OpencodeException {
            if (throwOnConfigOrProviders) {
                throw new UnsupportedOperationException("config must not be consulted");
            }
            return config;
        }

        @Override
        public ProviderList getProviders() throws OpencodeException {
            if (throwOnConfigOrProviders) {
                throw new UnsupportedOperationException("providers must not be consulted");
            }
            return providerList;
        }

    }
}
