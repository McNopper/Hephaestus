package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeEventStream;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.activity.PermissionEvents;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.CommandInfo;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.FileDiff;
import com.opencode.ide.client.model.FileNode;
import com.opencode.ide.client.model.FileStatus;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.McpServerInfo;
import com.opencode.ide.client.model.OauthStart;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.ProjectSummary;
import com.opencode.ide.client.model.ProviderAuth;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.SearchMatch;
import com.opencode.ide.client.model.SkillInfo;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.SessionTodo;
import com.opencode.ide.client.model.ShellResult;
import com.opencode.ide.client.model.SymbolResult;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Event-to-queue bridge for the fleet's {@link PermissionQueue}: feed it the
 * opencode {@code /event} SSE stream ({@link #onEvent}, e.g. via
 * {@link #subscribe(SseSessionEvents.Subscriber)} on the owner-run stream),
 * and it enqueues {@code permission.asked} events of the fleet's own sessions
 * while dropping them again on {@code permission.replied} or
 * {@code session.deleted}.
 *
 * <p><b>Why the client wrapper ({@link #watching(OpencodeClient)}):</b> the
 * fleet's prompt call ({@code POST /session/:id/message}) blocks until the
 * agent's final reply — and an unattended session that asks for permission
 * waits, mid-run, inside that very call. The session must therefore be
 * watched from the moment it is created (before the prompt is sent), not
 * after {@code submit} returns. Wrap the runner's client with
 * {@link #watching(OpencodeClient)}; every {@code createSession} then
 * registers the session with this bridge. {@link TaskFleet} calls
 * {@link #sessionEnded(String)} when the job leaves the launch (completed,
 * aborted, failed) so pending entries are dropped.</p>
 *
 * <p>Never throws on any event; foreign sessions are ignored. Pure Java, no
 * Eclipse/OSGi.</p>
 */
public final class FleetPermissionBridge {

    private final PermissionQueue queue;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    /** @param queue where requests of watched sessions are enqueued */
    public FleetPermissionBridge(PermissionQueue queue) {
        this.queue = queue;
    }

    /**
     * The bridge is a live view of the engine's permission queue; the
     * watchdog pauses its stall clock while asks are pending (a session
     * waiting for a human answer is WAITING, not hung - review finding).
     */
    public int pendingCount() {
        return queue.pendingCount();
    }

    /**
     * Marks a session as fleet-watched: its permission requests are enqueued.
     * Idempotent; called by {@link #watching(OpencodeClient)} on session
     * creation.
     */
    public void sessionStarted(String sessionId) {
        if (sessionId != null) {
            sessions.add(sessionId);
        }
    }

    /**
     * The session ended (job completed/aborted/failed or the session was
     * deleted): stop watching and drop its pending requests.
     */
    public void sessionEnded(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.remove(sessionId);
        queue.remove(sessionId);
    }

    /**
     * Feeds one SSE event (any type). {@code session.deleted} ends a watched
     * session; {@code permission.asked}/{@code permission.replied} of watched
     * sessions are forwarded to the queue. Unknown sessions, foreign event
     * types and malformed payloads are ignored — this method never throws.
     */
    public void onEvent(OpencodeEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        if ("session.deleted".equals(event.type())) {
            String sessionId = event.string("sessionID");
            if (sessionId != null && sessions.contains(sessionId)) {
                sessionEnded(sessionId);
            }
            return;
        }
        PermissionRequest request = PermissionEvents.parse(event);
        if (request == null || !sessions.contains(request.sessionId())) {
            return;
        }
        queue.offer(request);
    }

    /**
     * Subscribes this bridge to an owner-run event stream (the same
     * {@link SseSessionEvents.Subscriber} seam the SSE completion detection
     * uses); the returned handle unsubscribes.
     */
    public Runnable subscribe(SseSessionEvents.Subscriber subscriber) {
        return subscriber.subscribe(this::onEvent);
    }

    /**
     * Wraps a client so every session it creates is watched by this bridge
     * before its first prompt is sent (see class javadoc). All calls,
     * including the H5 defaults, delegate unchanged.
     */
    public OpencodeClient watching(OpencodeClient delegate) {
        return new WatchingClient(delegate);
    }

    /**
     * Delegating client that registers created sessions with the bridge.
     * Overrides every {@link OpencodeClient} method mechanically (the
     * {@code WatchingClientDelegationTest} reflection guard enforces this) —
     * only {@code createSession} adds the registration.
     */
    private final class WatchingClient implements OpencodeClient {

        private final OpencodeClient delegate;

        WatchingClient(OpencodeClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public HealthStatus getHealth() throws OpencodeException {
            return delegate.getHealth();
        }

        @Override
        public List<Agent> getAgents() throws OpencodeException {
            return delegate.getAgents();
        }

        @Override
        public ProviderList getProviders() throws OpencodeException {
            return delegate.getProviders();
        }

        @Override
        public ConfigInfo getConfig() throws OpencodeException {
            return delegate.getConfig();
        }

        @Override
        public List<Session> getSessions() throws OpencodeException {
            return delegate.getSessions();
        }

        @Override
        public Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
            return delegate.getSessionStatus();
        }

        @Override
        public Session createSession(String title) throws OpencodeException {
            // through the two-arg override so the created session is registered
            return createSession(title, null);
        }

        @Override
        public Session createSession(String title, Path directory) throws OpencodeException {
            Session session = delegate.createSession(title, directory);
            if (session != null && session.id() != null) {
                sessionStarted(session.id());
            }
            return session;
        }

        @Override
        public void registerMcp(String name, McpServerConfig config) throws OpencodeException {
            delegate.registerMcp(name, config);
        }

        @Override
        public List<McpServerInfo> getMcpServers() throws OpencodeException {
            return delegate.getMcpServers();
        }

        @Override
        public List<SkillInfo> getSkills() throws OpencodeException {
            return delegate.getSkills();
        }

        @Override
        public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
            return delegate.getMessages(sessionId);
        }

        @Override
        public List<SessionTodo> getSessionTodos(String sessionId) throws OpencodeException {
            return delegate.getSessionTodos(sessionId);
        }

        @Override
        public ChatEntry sendMessage(ChatRequest request) throws OpencodeException {
            return delegate.sendMessage(request);
        }

        @Override
        public ChatEntry sendMessage(ChatRequest request, java.time.Duration promptTimeout)
                throws OpencodeException {
            return delegate.sendMessage(request, promptTimeout);
        }

        @Override
        public void abortSession(String sessionId) throws OpencodeException {
            delegate.abortSession(sessionId);
        }

        @Override
        public void log(String service, String level, String message, Map<String, Object> extra)
                throws OpencodeException {
            delegate.log(service, level, message, extra);
        }

        @Override
        public List<FileDiff> getSessionDiff(String sessionId, String messageId) throws OpencodeException {
            return delegate.getSessionDiff(sessionId, messageId);
        }

        @Override
        public Session forkSession(String sessionId, String messageId) throws OpencodeException {
            return delegate.forkSession(sessionId, messageId);
        }

        @Override
        public boolean revertMessage(String sessionId, String messageId, String partId) throws OpencodeException {
            return delegate.revertMessage(sessionId, messageId, partId);
        }

        @Override
        public boolean unrevertSession(String sessionId) throws OpencodeException {
            return delegate.unrevertSession(sessionId);
        }

        @Override
        public boolean summarizeSession(String sessionId, String providerId, String modelId)
                throws OpencodeException {
            return delegate.summarizeSession(sessionId, providerId, modelId);
        }

        @Override
        public Session shareSession(String sessionId) throws OpencodeException {
            return delegate.shareSession(sessionId);
        }

        @Override
        public Session unshareSession(String sessionId) throws OpencodeException {
            return delegate.unshareSession(sessionId);
        }

        @Override
        public boolean respondToPermission(String sessionId, String permissionId, String response, boolean remember)
                throws OpencodeException {
            return delegate.respondToPermission(sessionId, permissionId, response, remember);
        }

        @Override
        public List<CommandInfo> getCommands() throws OpencodeException {
            return delegate.getCommands();
        }

        @Override
        public ChatEntry runCommand(String sessionId, String command, List<String> arguments)
                throws OpencodeException {
            return delegate.runCommand(sessionId, command, arguments);
        }

        @Override
        public ShellResult runShell(String sessionId, String agent, String command) throws OpencodeException {
            return delegate.runShell(sessionId, agent, command);
        }

        @Override
        public List<ProjectSummary> getProjects() throws OpencodeException {
            return delegate.getProjects();
        }

        @Override
        public VcsInfo getVcsInfo() throws OpencodeException {
            return delegate.getVcsInfo();
        }

        @Override
        public List<FileNode> listFiles(String path) throws OpencodeException {
            return delegate.listFiles(path);
        }

        @Override
        public List<SearchMatch> findText(String pattern) throws OpencodeException {
            return delegate.findText(pattern);
        }

        @Override
        public List<String> findFiles(String query) throws OpencodeException {
            return delegate.findFiles(query);
        }

        @Override
        public List<SymbolResult> findSymbols(String query) throws OpencodeException {
            return delegate.findSymbols(query);
        }

        @Override
        public ConfigInfo patchConfig(Map<String, Object> changes) throws OpencodeException {
            return delegate.patchConfig(changes);
        }

        @Override
        public boolean tuiAction(String action, Map<String, Object> body) throws OpencodeException {
            return delegate.tuiAction(action, body);
        }

        @Override
        public List<FileStatus> getFileStatus() throws OpencodeException {
            return delegate.getFileStatus();
        }

        @Override
        public String getFileContent(String path) throws OpencodeException {
            return delegate.getFileContent(path);
        }

        @Override
        public List<ProviderAuth> getProviderAuths() throws OpencodeException {
            return delegate.getProviderAuths();
        }

        @Override
        public OauthStart beginProviderOauth(String providerId) throws OpencodeException {
            return delegate.beginProviderOauth(providerId);
        }

        @Override
        public boolean startProviderOauth(String providerId) throws OpencodeException {
            return delegate.startProviderOauth(providerId);
        }

        @Override
        public OpencodeEventStream getGlobalEvents(Consumer<OpencodeEvent> sink, Consumer<Boolean> connectionListener) {
            return delegate.getGlobalEvents(sink, connectionListener);
        }
    }
}
