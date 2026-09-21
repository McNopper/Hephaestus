package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.model.ShellResult;

/**
 * In-memory fake of {@link OpencodeClient} for the fleet tests (no HTTP).
 * Shared by {@link FleetRunnerTest} and {@link TaskFleetTest}; set
 * {@link #onSessionCreated} to observe the world at submit time.
 */
final class FakeClient implements OpencodeClient {

    /**
     * The {@code time.completed} stamp every served message carries (v2's
     * 14th {@link ChatMessageInfo} component). Any non-zero value marks the
     * message complete; a fixed one keeps fixtures deterministic.
     */
    private static final long COMPLETED_AT = 1_700_000_000_000L;

    final List<String> createdTitles = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<Path> sessionDirectories = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<ChatRequest> sentRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** {@code sessionId|agent|command} of every {@link #runShell} attempt, failing ones included. */
    final List<String> shellCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Cross-call order log ({@code shell <sid>} / {@code message <sid>}) - pins the bootstrap before the prompt. */
    final List<String> callLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Map<String, List<ChatEntry>> messagesBySession = new java.util.concurrent.ConcurrentHashMap<>();

    /** Thread-safe since the 2026-08-28 watchdog redesign: the prompt runs on its own thread while the watchdog probes. */
    volatile String sessionType = "busy";
    volatile String replyOnSend;
    /** When set, the sendMessage assistant reply carries this agent (the review-actuals path, U-021). */
    volatile String replyAgent;
    /** When set, the sendMessage assistant reply carries this cost (the review-actuals path, U-021). */
    volatile Double replyCost;
    volatile boolean failSessionCreation;
    /** When set, {@link #getMessages(String)} fails - used to prove telemetry is best-effort. */
    volatile boolean failGetMessages;
    /** Served by {@link #runShell} (settable). */
    volatile ShellResult shellResult = new ShellResult("msg_shell", null, null, "completed", "bootstrap output");
    /** When set, {@link #runShell} throws {@link OpencodeException} - proves the bootstrap is best-effort. */
    volatile boolean failRunShell;
    /** When set, {@link #runShell} throws {@link IllegalStateException} - a runtime failure must not gate either. */
    volatile boolean failRunShellRuntime;
    /** Optional hook, invoked after each successful session creation. */
    volatile Runnable onSessionCreated;
    /** Optional hook, invoked inside sendMessage (blocks the send while it runs). */
    volatile Runnable blockOnSend;

    private int sessionCounter;
    private int messageCounter;

    void addEntry(String sessionId, String role, String text) {
        messagesBySession.get(sessionId).add(entry(sessionId, role, text));
    }

    void completeSession(String sessionId, String reply) {
        sessionType = "idle";
        addEntry(sessionId, "assistant", reply);
    }

    /** Sessions aborted via POST /session/:id/interrupt (the watchdog's stall kill). */
    final java.util.List<String> aborted = new java.util.ArrayList<>();

    @Override
    public void abortSession(String sessionId) {
        aborted.add(sessionId);
        sessionType = "idle";
    }

    private ChatEntry entry(String sessionId, String role, String text) {
        // every entry the fake serves is a FINISHED message: v2's trailing
        // time.completed stamp (14th component) is what isComplete() polls on
        ChatMessageInfo info = new ChatMessageInfo(
                "msg_" + (++messageCounter), sessionId, role,
                null, null, null, null, null, null, null, null, null, null, COMPLETED_AT);
        List<ChatPart> parts = (text == null) ? List.of() : List.of(new ChatPart("text", text, null, null));
        return new ChatEntry(info, parts);
    }

    @Override
    public Session createSession(String title, Path directory) throws OpencodeException {
        if (failSessionCreation) {
            throw new OpencodeException("session create failed");
        }
        String id = "ses_" + (++sessionCounter);
        createdTitles.add(title);
        sessionDirectories.add(directory);
        messagesBySession.put(id, java.util.Collections.synchronizedList(new ArrayList<>()));
        if (onSessionCreated != null) {
            onSessionCreated.run();
        }
        return new Session(id, null, title, null, null, null, null, null, null, null, null);
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() {
        Map<String, SessionStatus> status = new HashMap<>();
        for (String id : messagesBySession.keySet()) {
            status.put(id, new SessionStatus(sessionType));
        }
        return status;
    }

    @Override
    public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
        if (failGetMessages) {
            throw new OpencodeException("messages fetch failed");
        }
        return messagesBySession.getOrDefault(sessionId, List.of());
    }

    @Override
    public ChatEntry sendMessage(ChatRequest request) {
        if (blockOnSend != null) {
            blockOnSend.run();
        }
        callLog.add("message " + request.sessionId());
        sentRequests.add(request);
        List<ChatEntry> entries = messagesBySession.get(request.sessionId());
        entries.add(entry(request.sessionId(), "user", request.text()));
        if (replyOnSend != null) {
            entries.add(replyEntry(request.sessionId(), replyOnSend));
        }
        return entries.get(entries.size() - 1);
    }

    /** The sendMessage assistant reply, optionally carrying agent/cost actuals (U-021 review tests). */
    private ChatEntry replyEntry(String sessionId, String text) {
        ChatMessageInfo info = new ChatMessageInfo(
                "msg_" + (++messageCounter), sessionId, "assistant",
                null, replyAgent, null, null, replyCost, null, null, null, null, null, COMPLETED_AT);
        return new ChatEntry(info, List.of(new ChatPart("text", text, null, null)));
    }

    /** The prompt timeout of the last 2-arg send, for budget-wiring assertions. */
    volatile java.time.Duration lastPromptTimeout;

    @Override
    public ChatEntry sendMessage(ChatRequest request, java.time.Duration promptTimeout) {
        lastPromptTimeout = promptTimeout;
        return sendMessage(request);
    }

    @Override
    public ShellResult runShell(String sessionId, String agent, String command) throws OpencodeException {
        shellCalls.add(sessionId + "|" + agent + "|" + command);
        callLog.add("shell " + sessionId);
        if (failRunShell) {
            throw new OpencodeException("shell failed");
        }
        if (failRunShellRuntime) {
            throw new IllegalStateException("shell boom");
        }
        return shellResult;
    }

    @Override
    public HealthStatus getHealth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Agent> getAgents() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProviderList getProviders() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConfigInfo getConfig() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Session> getSessions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra) {
        throw new UnsupportedOperationException();
    }
}
