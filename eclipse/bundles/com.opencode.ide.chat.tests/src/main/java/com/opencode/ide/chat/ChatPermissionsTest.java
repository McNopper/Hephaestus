package com.opencode.ide.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.chat.internal.ChatServerConnection;
import com.opencode.ide.chat.internal.ChatSessionController;

/**
 * Tests for the pluggable chat permission seam: {@link ChatPermissions}
 * (registry semantics — last set wins, clear stops delivery, exception
 * containment) and {@link ChatPermissionAdapter} (parse + dispatch, client
 * hand-off), plus the {@link ChatSessionController#subscribe()} wiring that
 * feeds a subscribed chat view's event stream into the sink. The registry is
 * static, so every test clears it before and after.
 */
public class ChatPermissionsTest {

    private FakeConnection connection;
    private NoopRenderer renderer;
    private ChatSessionController controller;
    private RecordingSink sink;

    @Before
    public void setUp() {
        ChatPermissions.clearSink();
        connection = new FakeConnection();
        renderer = new NoopRenderer();
        controller = new ChatSessionController(connection, renderer, new DirectHost());
        sink = new RecordingSink();
    }

    @After
    public void tearDown() {
        ChatPermissions.clearSink();
    }

    // ---------- registry + adapter (standalone) ----------

    @Test
    public void askedDeliversParsedRequestAndClient() {
        ChatPermissions.setSink(sink);
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        adapter.onEvent(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\","
                        + "\"patterns\":[\"git push\"],\"metadata\":{\"command\":\"git push\"},"
                        + "\"always\":[]}"));

        assertEquals(1, sink.asked.size());
        PermissionRequest request = sink.asked.get(0);
        assertEquals("ses_1", request.sessionId());
        assertEquals("per_1", request.permissionId());
        assertEquals("bash", request.permission());
        assertEquals(List.of("git push"), request.patterns());
        assertEquals("git push", request.title());
        assertEquals(PermissionRequest.Status.PENDING, request.status());
        assertSame(connection.client, sink.clients.get(0));
        assertTrue(sink.replied.isEmpty());
    }

    @Test
    public void repliedDeliversSessionAndRequestIds() {
        ChatPermissions.setSink(sink);
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        adapter.onEvent(event("permission.replied",
                "{\"sessionID\":\"ses_1\",\"requestID\":\"per_1\",\"reply\":\"once\"}"));

        assertTrue(sink.asked.isEmpty());
        assertEquals(1, sink.replied.size());
        assertEquals("ses_1", sink.replied.get(0)[0]);
        assertEquals("per_1", sink.replied.get(0)[1]);
    }

    @Test
    public void nonPermissionEventsAreIgnored() {
        ChatPermissions.setSink(sink);
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        adapter.onEvent(event("message.part.delta",
                "{\"sessionID\":\"ses_1\",\"messageID\":\"m\",\"field\":\"text\",\"delta\":\"x\"}"));
        adapter.onEvent(event("session.idle", "{\"sessionID\":\"ses_1\"}"));
        adapter.onEvent(new OpencodeEvent(null, null));
        adapter.onEvent(null);

        assertTrue(sink.asked.isEmpty());
        assertTrue(sink.replied.isEmpty());
    }

    @Test
    public void malformedPermissionEventIsIgnored() {
        ChatPermissions.setSink(sink);
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        // asked without a permission id: nothing actionable
        adapter.onEvent(event("permission.asked", "{\"sessionID\":\"ses_1\"}"));

        assertTrue(sink.asked.isEmpty());
    }

    @Test
    public void eventsWithoutSinkAreIgnoredWithoutErrors() {
        // no sink registered: current behavior (ignore) must hold unchanged
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        adapter.onEvent(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\"}"));
        adapter.onEvent(event("permission.replied",
                "{\"sessionID\":\"ses_1\",\"requestID\":\"per_1\",\"reply\":\"reject\"}"));
    }

    @Test
    public void throwingSinkIsContainedAndDeliveryContinues() {
        ChatPermissions.setSink(sink);
        sink.throwOnAsked = true;
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        adapter.onEvent(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\"}"));
        // the SSE event loop must survive a broken sink: the next event still delivers
        adapter.onEvent(event("permission.replied",
                "{\"sessionID\":\"ses_1\",\"requestID\":\"per_1\",\"reply\":\"once\"}"));

        assertEquals(1, sink.replied.size());
    }

    @Test
    public void clearSinkStopsDelivery() {
        ChatPermissions.setSink(sink);
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);
        adapter.onEvent(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\"}"));
        assertEquals(1, sink.asked.size());

        ChatPermissions.clearSink();
        adapter.onEvent(event("permission.asked",
                "{\"id\":\"per_2\",\"sessionID\":\"ses_1\",\"permission\":\"edit\"}"));

        assertEquals(1, sink.asked.size());
    }

    @Test
    public void lastSetSinkWins() {
        RecordingSink first = new RecordingSink();
        ChatPermissions.setSink(first);
        ChatPermissions.setSink(sink);
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        adapter.onEvent(event("permission.replied",
                "{\"sessionID\":\"ses_1\",\"requestID\":\"per_1\",\"reply\":\"once\"}"));

        assertTrue(first.replied.isEmpty());
        assertEquals(1, sink.replied.size());
    }

    @Test
    public void askedWithoutClientIsSkippedRepliedStillDelivered() {
        ChatPermissions.setSink(sink);
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> null);

        adapter.onEvent(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\"}"));
        assertTrue(sink.asked.isEmpty());

        adapter.onEvent(event("permission.replied",
                "{\"sessionID\":\"ses_1\",\"requestID\":\"per_1\",\"reply\":\"once\"}"));
        assertEquals(1, sink.replied.size());
    }

    @Test
    public void sinkCanAnswerThroughThePassedClient() {
        ChatPermissions.setSink(new ChatPermissionSink() {
            @Override
            public void asked(PermissionRequest request, OpencodeClient client) {
                try {
                    client.respondToPermission(request.sessionId(), request.permissionId(), "once",
                            false);
                } catch (OpencodeException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void replied(String sessionId, String requestId) {
                // not needed here
            }
        });
        ChatPermissionAdapter adapter = new ChatPermissionAdapter(() -> connection.client);

        adapter.onEvent(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\"}"));

        assertEquals(List.of("ses_1:per_1:once:false"), connection.client.permissionAnswers);
    }

    // ---------- controller wiring ----------

    @Test
    public void controllerSubscribesPermissionDispatchWithTheLiveClient() {
        controller.subscribe();
        ChatPermissions.setSink(sink);

        connection.fire(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\"}"));
        connection.fire(event("permission.replied",
                "{\"sessionID\":\"ses_1\",\"requestID\":\"per_1\",\"reply\":\"once\"}"));

        assertEquals(1, sink.asked.size());
        assertSame(connection.client, sink.clients.get(0));
        assertEquals(1, sink.replied.size());
        assertEquals(0, renderer.calls); // no behavioral change without a send

        controller.dispose();
        assertTrue(connection.listeners.isEmpty());
    }

    @Test
    public void controllerWiringDeliversOncePerSubscribedView() {
        // several open chat views each subscribe an adapter: the sink dedups
        // by permission id (documented contract)
        ChatSessionController secondView =
                new ChatSessionController(connection, new NoopRenderer(), new DirectHost());
        controller.subscribe();
        secondView.subscribe();
        ChatPermissions.setSink(sink);

        connection.fire(event("permission.asked",
                "{\"id\":\"per_1\",\"sessionID\":\"ses_1\",\"permission\":\"bash\"}"));

        assertEquals(2, sink.asked.size());

        controller.dispose();
        secondView.dispose();
    }

    // ---------- helpers / fakes ----------

    private static OpencodeEvent event(String type, String propertiesJson) {
        JsonObject properties = propertiesJson == null ? null
                : new Gson().fromJson(propertiesJson, JsonObject.class);
        return new OpencodeEvent(type, properties);
    }

    private static final class RecordingSink implements ChatPermissionSink {
        final List<PermissionRequest> asked = new ArrayList<>();
        final List<OpencodeClient> clients = new ArrayList<>();
        final List<String[]> replied = new ArrayList<>();
        boolean throwOnAsked;

        @Override
        public void asked(PermissionRequest request, OpencodeClient client) {
            if (throwOnAsked) {
                throw new IllegalStateException("boom");
            }
            asked.add(request);
            clients.add(client);
        }

        @Override
        public void replied(String sessionId, String requestId) {
            replied.add(new String[] { sessionId, requestId });
        }
    }

    private static final class NoopRenderer implements ChatSessionController.Renderer {
        int calls;

        @Override
        public void appendUser(String text) {
            calls++;
        }

        @Override
        public void startAssistant(String messageId) {
            calls++;
        }

        @Override
        public void appendDelta(String messageId, String text) {
            calls++;
        }

        @Override
        public void appendReasoningDelta(String messageId, String text) {
            calls++;
        }

        @Override
        public void setAssistantText(String messageId, String text, String reasoning, String meta,
                List<ChatSessionController.ToolLine> tools) {
            calls++;
        }

        @Override
        public void stopStream(String messageId) {
            calls++;
        }

        @Override
        public void setMessages(List<Map<String, Object>> rows) {
            calls++;
        }

        @Override
        public void notice(String text) {
            calls++;
        }

        @Override
        public void clear() {
            calls++;
        }
    }

    private static final class DirectHost implements ChatSessionController.Host {

        @Override
        public void runInBackground(String jobName, Runnable task) {
            task.run();
        }

        @Override
        public void runOnUi(Runnable task) {
            task.run();
        }

        @Override
        public void info(String message) {
            // not needed here
        }

        @Override
        public void error(String message, Throwable throwable) {
            // not needed here
        }

        @Override
        public void statusChanged(String description) {
            // not needed here
        }

        @Override
        public void sendingChanged(boolean sending) {
            // not needed here
        }
    }

    private static final class FakeConnection implements ChatServerConnection {
        final FakeClient client = new FakeClient();
        final List<OpencodeEventListener> listeners = new ArrayList<>();

        @Override
        public OpencodeClient getClient() {
            return client;
        }

        @Override
        public void addEventListener(OpencodeEventListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeEventListener(OpencodeEventListener listener) {
            listeners.remove(listener);
        }

        void fire(OpencodeEvent event) {
            for (OpencodeEventListener listener : List.copyOf(listeners)) {
                listener.onEvent(event);
            }
        }
    }

    private static final class FakeClient implements OpencodeClient {
        final List<String> permissionAnswers = new ArrayList<>();

        @Override
        public HealthStatus getHealth() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Agent> getAgents() {
            return List.of();
        }

        @Override
        public ProviderList getProviders() {
            return new ProviderList(List.of(), Map.of());
        }

        @Override
        public ConfigInfo getConfig() {
            return null;
        }

        @Override
        public List<Session> getSessions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, SessionStatus> getSessionStatus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Session createSession(String title, Path directory) {
            return new Session("ses_1", null, title, null, null, null, null, null, null);
        }

        @Override
        public void registerMcp(String name, McpServerConfig config) {
            // not needed here
        }

        @Override
        public List<ChatEntry> getMessages(String sessionId) {
            return List.of();
        }

        @Override
        public ChatEntry sendMessage(ChatRequest request) {
            return null;
        }

        @Override
        public void abortSession(String sessionId) {
            // not needed here
        }

        @Override
        public ChatEntry runCommand(String sessionId, String command, List<String> arguments) {
            return null;
        }

        @Override
        public void log(String service, String level, String message, Map<String, Object> extra) {
            // not needed here
        }

        @Override
        public boolean respondToPermission(String sessionId, String permissionId, String response,
                boolean remember) {
            permissionAnswers.add(sessionId + ":" + permissionId + ":" + response + ":" + remember);
            return true;
        }
    }
}
