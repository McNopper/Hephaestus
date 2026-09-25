package com.opencode.ide.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.ShellResult;
import com.opencode.ide.client.model.SkillInfo;
import com.opencode.ide.client.model.VcsInfo;

/**
 * Client for an opencode v2 server. Request methods perform
 * synchronous HTTP and throw {@link OpencodeException} on failure.
 *
 * <p>The wire contract is the server's OpenAPI spec at {@code /openapi.json}.
 * Session prompt and shell operations queue work and poll for completion.</p>
 */
public interface OpencodeClient {

    /** {@code GET /api/info} - server health and version. */
    HealthStatus getHealth() throws OpencodeException;

    /** {@code GET /api/agent} - all available agent definitions. */
    List<Agent> getAgents() throws OpencodeException;

    /**
     * {@code GET /api/agent?location[directory]=…} - agent definitions scoped to a
     * project directory. v2 resolves these auxiliary lists per location: an
     * unscoped call on the shared background service answers for the user's
     * home directory, i.e. the wrong project's agents/skills/MCP servers.
     *
     * @param directory project/worktree path, or {@code null} for the server default
     */
    default List<Agent> getAgents(String directory) throws OpencodeException {
        return getAgents();
    }

    /** {@code GET /api/provider} and {@code /api/model} - providers and their models. */
    ProviderList getProviders() throws OpencodeException;

    /** Scoped variant (see {@link #getAgents(String)}): the catalog can differ per project config. */
    default ProviderList getProviders(String directory) throws OpencodeException {
        return getProviders();
    }

    /** {@code GET /api/config} - server config (default model etc.). */
    ConfigInfo getConfig() throws OpencodeException;

    /** {@code GET /api/config?location[directory]=…} - scoped variant (see {@link #getAgents(String)}). */
    default ConfigInfo getConfig(String directory) throws OpencodeException {
        return getConfig();
    }

    /** {@code GET /api/session} - sessions, including subagent children. */
    List<Session> getSessions() throws OpencodeException;

    /**
     * {@code GET /api/session?directory=…} - sessions scoped to one project/worktree
     * directory. Matters in v2: session state is global per user (every server
     * lists every session of every project), so project views must filter.
     *
     * @param directory project/worktree path, or {@code null} for all sessions
     */
    default List<Session> getSessions(String directory) throws OpencodeException {
        return getSessions();
    }

    /** {@code GET /api/session/active} - active sessions mapped to client status; absent means idle. */
    Map<String, SessionStatus> getSessionStatus() throws OpencodeException;

    /**
     * {@code POST /api/session} - create a new session.
     *
     * @param title optional title (may be {@code null})
     */
    default Session createSession(String title) throws OpencodeException {
        return createSession(title, null);
    }

    /**
     * {@code POST /api/session} with {@code location.directory} in the JSON
     * body - create a session in a project directory or git worktree.
     *
     * @param title     optional title (may be {@code null})
     * @param directory working directory the session operates in
     *                  (may be {@code null} = server default)
     */
    Session createSession(String title, Path directory) throws OpencodeException;

    /**
     * Renames a session (v2 {@code PATCH /api/session/{id}} with a
     * {@code title} body). Adoption 2026-09-25: U-002 was skipped for "no
     * title-update endpoint" - the v2 contract has one. Default throws so
     * test fakes stay minimal.
     */
    default void renameSession(String sessionId, String title) throws OpencodeException {
        throw new UnsupportedOperationException("renameSession");
    }

    /**
     * v2 adoption wave A (2026-09-25): move a session to another directory
     * ({@code POST /api/session/{id}/move}, body {@code {directory}}) - scope
     * moves without a re-chat. Default throws so test fakes stay minimal.
     */
    default void moveSession(String sessionId, String directory) throws OpencodeException {
        throw new UnsupportedOperationException("moveSession");
    }

    /**
     * {@code POST /api/session/{id}/agent} - switch the session's agent
     * mid-run (body {@code {agent}}).
     */
    default void switchSessionAgent(String sessionId, String agent) throws OpencodeException {
        throw new UnsupportedOperationException("switchSessionAgent");
    }

    /**
     * {@code POST /api/session/{id}/model} - switch the session's model
     * mid-run (body {@code {model}}) - the cost lever on a live session.
     */
    default void switchSessionModel(String sessionId, String model) throws OpencodeException {
        throw new UnsupportedOperationException("switchSessionModel");
    }

    /**
     * {@code POST /api/session/{id}/compact} - compact a long session's
     * context (long-run control for fleet workers).
     */
    default void compactSession(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("compactSession");
    }

    /**
     * {@code GET /api/experimental/session/{id}/export} - the session export
     * document as RAW text. Raw on purpose: the export format is the
     * service's, we never re-encode it.
     */
    default String exportSession(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("exportSession");
    }

    /**
     * {@code GET /api/experimental/session/{id}/log} - the session log, raw.
     */
    default String sessionLog(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("sessionLog");
    }

    /**
     * {@code GET /api/experimental/session/stats} - session statistics
     * straight from the service (no message-list math on our side).
     */
    default java.util.Map<String, Object> sessionStats() throws OpencodeException {
        throw new UnsupportedOperationException("sessionStats");
    }

    /**
     * v2 adoption wave A (2026-09-25): the service's location (config root) -
     * {@code GET /api/location} -> {@code Location.PublicInfo}.
     */
    default java.util.Map<String, Object> getLocation() throws OpencodeException {
        throw new UnsupportedOperationException("getLocation");
    }

    /** {@code POST /api/location/reload} - reload the service's configuration. */
    default void reloadLocation() throws OpencodeException {
        throw new UnsupportedOperationException("reloadLocation");
    }

    /** {@code PATCH /api/project/{id}} - update a project (body {@code {name}}). */
    default void updateProject(String projectId, String name) throws OpencodeException {
        throw new UnsupportedOperationException("updateProject");
    }

    /** {@code GET /api/reference} - the reference catalog the service exposes. */
    default java.util.List<java.util.Map<String, Object>> listReferences() throws OpencodeException {
        throw new UnsupportedOperationException("listReferences");
    }

    /** {@code GET /api/plugin} - installed plugins and their state. */
    default java.util.List<java.util.Map<String, Object>> listPlugins() throws OpencodeException {
        throw new UnsupportedOperationException("listPlugins");
    }

    /** {@code PATCH /api/credential/{id}} - relabel a credential (body {@code {label}}). */
    default void renameCredential(String credentialId, String label) throws OpencodeException {
        throw new UnsupportedOperationException("renameCredential");
    }

    /** {@code POST /api/credential/{id}/activate} - make a credential the active one. */
    default void activateCredential(String credentialId) throws OpencodeException {
        throw new UnsupportedOperationException("activateCredential");
    }

    /** {@code DELETE /api/credential/{id}} - remove a credential. */
    default void removeCredential(String credentialId) throws OpencodeException {
        throw new UnsupportedOperationException("removeCredential");
    }

    /** {@code GET /api/websearch/provider} - available web-search providers. */
    default java.util.List<java.util.Map<String, Object>> listWebsearchProviders() throws OpencodeException {
        throw new UnsupportedOperationException("listWebsearchProviders");
    }

    /** {@code POST /api/websearch} - search the web (body {@code {query, providerID}}). */
    default java.util.Map<String, Object> websearch(String query, String providerId) throws OpencodeException {
        throw new UnsupportedOperationException("websearch");
    }

    /** {@code GET /api/pty} - PTY sessions (interactive terminals). */
    default java.util.List<java.util.Map<String, Object>> listPtys() throws OpencodeException {
        throw new UnsupportedOperationException("listPtys");
    }

    /** {@code POST /api/pty} - create a PTY session (body {@code {command, args, cwd, title}}). */
    default java.util.Map<String, Object> createPty(String command, java.util.List<String> args, String cwd,
            String title) throws OpencodeException {
        throw new UnsupportedOperationException("createPty");
    }

    /** {@code DELETE /api/pty/{id}} - remove a PTY session. */
    default void removePty(String ptyId) throws OpencodeException {
        throw new UnsupportedOperationException("removePty");
    }

    /**
     * {@code GET /api/experimental/session/{id}/terminal/read} - the session's
     * controlled terminal as {@code PersistentPty.ReadResult} (rendered
     * {@code screen} + {@code foregroundProcess}). The read-only terminal pane
     * rides this - no emulator on our side.
     */
    default java.util.Map<String, Object> readSessionTerminal(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("readSessionTerminal");
    }

    /**
     * {@code GET /api/experimental/persistent-pty/{id}/snapshot} - terminal
     * snapshot (text + cursor + checkpoint) for scrollback capture.
     */
    default java.util.Map<String, Object> persistentPtySnapshot(String ptyId) throws OpencodeException {
        throw new UnsupportedOperationException("persistentPtySnapshot");
    }

    /**
     * {@code POST /api/plugin/check} - check installed plugins for updates.
     * Empty body on purpose (live-probed 2026-09-25: the check takes no
     * arguments; a {@code target} argument is rejected with 400).
     */
    default java.util.List<java.util.Map<String, Object>> checkPlugins() throws OpencodeException {
        throw new UnsupportedOperationException("checkPlugins");
    }

    /** {@code POST /api/plugin/update} - update the named plugins (body {@code {targets}}). */
    default java.util.List<java.util.Map<String, Object>> updatePlugins(java.util.List<String> targets)
            throws OpencodeException {
        throw new UnsupportedOperationException("updatePlugins");
    }

    /**
     * {@code GET /api/experimental/session/{id}/terminal} - the session's
     * controlled terminal info ({@code PersistentPty.Info}).
     */
    default java.util.Map<String, Object> sessionTerminal(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("sessionTerminal");
    }

    /**
     * {@code POST /api/experimental/session/{id}/terminal} - create the
     * session's controlled terminal ({@code PersistentPty.CreateInput}:
     * command, args, cwd, title - the service's DECLARED schema; the route
     * and its body validation were live-probed 2026-09-25).
     */
    default java.util.Map<String, Object> createSessionTerminal(String sessionId, String command,
            java.util.List<String> args, String cwd, String title) throws OpencodeException {
        throw new UnsupportedOperationException("createSessionTerminal");
    }

    /**
     * Per-session context usage (v2 {@code GET /api/session/{id}/context}).
     * Adoption 2026-09-25: token/context telemetry straight from the
     * service, no message-list math. Default throws so test fakes stay
     * minimal.
     */
    default java.util.Map<String, Object> getSessionContext(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("getSessionContext");
    }

    /** v2 snapshots: restore the staged snapshot ({@code revert/commit}) - the one verb the H5 surface lacked. */
    default void commitSessionRevert(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("commitSessionRevert");
    }

    /**
     * v2 worktrees: list a project's worktrees (slice 2 - 2026-09-25). The
     * service REQUIRES a {@code projectID} (the service-side project id from
     * {@link #getProjects()}, not a path - a path 404s); callers resolve the
     * id from the project list first.
     */
    default java.util.List<java.util.Map<String, Object>> listWorktrees(String projectID)
            throws OpencodeException {
        throw new UnsupportedOperationException("listWorktrees");
    }

    /** v2 worktrees: create one (from/branch/directory/name are optional). */
    default java.util.Map<String, Object> createWorktree(String projectID, String from, String branch,
            String directory, String name) throws OpencodeException {
        throw new UnsupportedOperationException("createWorktree");
    }

    /**
     * v2 worktrees: rescan after external git activity. The body REQUIRES
     * {@code projectID} (live probe 2026-09-25: an empty body answers 400
     * "Missing key at [projectID]"); after the refresh the service lists
     * git-CLI-created worktrees with {@code strategy:"git"}.
     */
    default void refreshWorktrees(String projectID) throws OpencodeException {
        throw new UnsupportedOperationException("refreshWorktrees");
    }

    /**
     * v2 forms: the open forms of one session (slice 3 - 2026-09-25). The
     * service owns the form SCHEMA - fields come back verbatim and replies
     * echo values keyed by those names; we never guess the shape.
     */
    default java.util.List<java.util.Map<String, Object>> listForms(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("listForms");
    }

    /** v2 forms: answer one ({@code POST .../form/{formID}/reply}). */
    default void replyForm(String sessionId, String formID, java.util.Map<String, Object> values)
            throws OpencodeException {
        throw new UnsupportedOperationException("replyForm");
    }

    /** v2 forms: cancel one ({@code DELETE .../form/{formID}}). */
    default void cancelForm(String sessionId, String formID) throws OpencodeException {
        throw new UnsupportedOperationException("cancelForm");
    }

    /** v2 MCP management: remove a configured server (experimental.mcp.remove). */
    default void removeMcp(String name) throws OpencodeException {
        throw new UnsupportedOperationException("removeMcp");
    }

    /** v2 MCP management: reconnect a server (experimental.mcp.connect). */
    default void connectMcp(String name) throws OpencodeException {
        throw new UnsupportedOperationException("connectMcp");
    }

    /** v2 MCP management: disconnect a server (experimental.mcp.disconnect). */
    default void disconnectMcp(String name) throws OpencodeException {
        throw new UnsupportedOperationException("disconnectMcp");
    }

    /**
     * {@code PUT /api/experimental/mcp/:name} - register an MCP server so its
     * tools become available to agents.
     *
     * @param name   the MCP server name (e.g. {@code "eclipse-build"})
     * @param config the endpoint to register
     */
    void registerMcp(String name, McpServerConfig config) throws OpencodeException;

    /**
     * {@code GET /api/mcp} - the MCP servers registered with the opencode server
     * (their ids and transport types). Default returns empty so test fakes and
     * partial implementations stay compiling.
     */
    default List<McpServerInfo> getMcpServers() throws OpencodeException {
        return List.of();
    }

    /** {@code GET /api/mcp?location[directory]=…} - scoped variant (see {@link #getAgents(String)}). */
    default List<McpServerInfo> getMcpServers(String directory) throws OpencodeException {
        return getMcpServers();
    }

    /**
     * {@code GET /api/skill} - the skills loaded from the working directory's
     * {@code .opencode/skills/}. Default returns empty so test fakes and
     * partial implementations stay compiling.
     */
    default List<SkillInfo> getSkills() throws OpencodeException {
        return List.of();
    }

    /** {@code GET /api/skill?location[directory]=…} - scoped variant (see {@link #getAgents(String)}). */
    default List<SkillInfo> getSkills(String directory) throws OpencodeException {
        return getSkills();
    }

    /** {@code GET /api/session/:id/message} - the message history of a session. */
    List<ChatEntry> getMessages(String sessionId) throws OpencodeException;

    /**
     * {@code GET /api/session/:id/message} - the RAW message array (the
     * unwrapped {@code data} list, untouched by the chat-shaped parsing).
     * {@link com.opencode.ide.client.activity.SessionObserver} needs this full
     * fidelity: tool input and shell detail are deliberately dropped by the
     * {@link ChatEntry} mapping. Default returns an empty array so test fakes
     * stay compiling.
     */
    default com.google.gson.JsonArray getMessagesJson(String sessionId) throws OpencodeException {
        return new com.google.gson.JsonArray();
    }

    /**
     * {@code POST /api/session/:id/prompt} - queue a user prompt, then poll the
     * message history for its completed assistant reply. Streaming display uses
     * the {@code /api/event} SSE stream.
     *
     * @param request model/agent/variant/system + the prompt (see {@link ChatRequest})
     */
    ChatEntry sendMessage(ChatRequest request) throws OpencodeException;

    /**
     * {@link #sendMessage(ChatRequest)} with an explicit reply-wait budget.
     * The default delegates to the single-argument method; unattended callers
     * pass their run budget so a long, active turn is allowed to complete.
     *
     * @param promptTimeout how long to wait for the queued turn's final reply
     */
    /**
     * {@code POST /api/session/:id/prompt} - send and wait for the reply
     * (v2: the POST is async, the reply is polled).
     */
    default ChatEntry sendMessage(ChatRequest request, java.time.Duration promptTimeout)
            throws OpencodeException {
        return sendMessage(request);
    }

    /**
     * The full send form with explicit delivery (T-005 send-time parity):
     * {@code "queue"} parks the prompt in the session inbox instead of
     * steering the active run (v2 Alt+Enter). Default delegates without the
     * delivery so simple fakes stay source-compatible.
     */
    default ChatEntry sendMessage(ChatRequest request, java.time.Duration promptTimeout, String delivery)
            throws OpencodeException {
        return sendMessage(request, promptTimeout);
    }

    /**
     * {@code POST /api/session/:id/interrupt} - interrupt the running agent.
     * A 404 is treated as already idle; other HTTP and transport errors raise
     * {@link OpencodeException}.
     *
     * <p>Default implementation throws {@link UnsupportedOperationException} so
     * in-repo test fakes that never abort stay source-compatible;
     * {@code HttpOpencodeClient} overrides it.</p>
     */
    default void abortSession(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("abortSession");
    }

    /** Deletes a session and its stored messages. */
    default void deleteSession(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("deleteSession");
    }

    /**
     * Write an entry to the harness's client-side log.
     *
     * @param service free-form service identifier (e.g. {@code "opencode-eclipse"})
     * @param level   one of {@code DEBUG}, {@code INFO}, {@code WARN}, {@code ERROR}
     * @param message the log message
     * @param extra   optional structured payload (may be {@code null})
     */
    void log(String service, String level, String message, Map<String, Object> extra) throws OpencodeException;

    // ---------- session lifecycle and workspace operations ----------

    /**
     * {@code GET /api/session/:id/diff} - the session's file diffs (authoritative,
     * server-side; works for taken-over and external sessions too).
     *
     * @param sessionId the session
     * @param messageId optional message id to diff up to (may be {@code null})
     */
    default List<FileDiff> getSessionDiff(String sessionId, String messageId) throws OpencodeException {
        return List.of();
    }

    /** {@code POST /api/session/:id/fork} - fork a session at a message (explore a variant). */
    default Session forkSession(String sessionId, String messageId) throws OpencodeException {
        throw new UnsupportedOperationException("forkSession");
    }

    /**
     * {@code POST /api/session/:id/revert/stage} - stage a conversation revert
     * to before a whole message ({@code messageID}).
     */
    default boolean revertMessage(String sessionId, String messageId) throws OpencodeException {
        throw new UnsupportedOperationException("revertMessage");
    }

    /** {@code DELETE /api/session/:id/revert} - clear the staged revert. */
    default boolean unrevertSession(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("unrevertSession");
    }

    /**
     * {@code POST /api/session/:id/compact} - compact a long session (provider/
     * model pick; the server then maintains the summary itself).
     */
    default boolean summarizeSession(String sessionId, String providerId, String modelId)
            throws OpencodeException {
        throw new UnsupportedOperationException("summarizeSession");
    }

    /**
     * {@code POST /api/session/:id/permission/:requestID/reply} - answer a permission
     * request an unattended session raised.
     *
     * @param response     {@code "once"}, {@code "always"} or {@code "reject"}
     * @param remember     send an {@code always} decision for an approval
     */
    default boolean respondToPermission(String sessionId, String permissionId, String response, boolean remember)
            throws OpencodeException {
        throw new UnsupportedOperationException("respondToPermission");
    }

    /**
     * The full answer form (T-004): same single answer path, plus optional
     * REJECT FEEDBACK that travels to the agent in the request's
     * {@code message} field (v2 reply body). Default delegates without the
     * feedback so simple fakes stay source-compatible.
     */
    default boolean respondToPermission(String sessionId, String permissionId, String response, boolean remember,
            String feedback) throws OpencodeException {
        return respondToPermission(sessionId, permissionId, response, remember);
    }

    /**
     * {@code GET /api/permission/request} - every PENDING permission ask of
     * unattended sessions (U-045/v2 parity). The poll-based read path: SSE
     * events are not replayed after a reconnect, this list is the recovery
     * surface for asks raised while no listener was attached.
     */
    default List<com.opencode.ide.client.activity.PermissionRequest> listPermissionRequests(String directory)
            throws OpencodeException {
        throw new UnsupportedOperationException("listPermissionRequests");
    }

    /** Unscoped variant (see {@link #getAgents(String)}). */
    default List<com.opencode.ide.client.activity.PermissionRequest> listPermissionRequests()
            throws OpencodeException {
        return listPermissionRequests(null);
    }

    /**
     * {@code POST /api/session/:id/background} - background the session's
     * blocking tools so the session continues while long tools run (v2's
     * Ctrl+B). U-045/v2 parity.
     */
    default void backgroundSession(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("backgroundSession");
    }

    /** {@code GET /api/shell} - the live shell tasks (v2's Shell tab). U-045/v2 parity. */
    default List<com.opencode.ide.client.model.ShellTask> listShellTasks() throws OpencodeException {
        throw new UnsupportedOperationException("listShellTasks");
    }

    /** {@code GET /api/shell/:id/output} - a shell task's captured output tail. */
    default String shellTaskOutput(String id) throws OpencodeException {
        throw new UnsupportedOperationException("shellTaskOutput");
    }

    /** {@code DELETE /api/shell/:id} - reap a finished shell task. */
    default void removeShellTask(String id) throws OpencodeException {
        throw new UnsupportedOperationException("removeShellTask");
    }

    /** {@code GET /api/session/:id/inbox} - queued/steered pending prompts (v2's Alt+Enter queue). */
    default List<com.google.gson.JsonObject> listInbox(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("listInbox");
    }

    /** {@code PATCH /api/session/:id/inbox/:msgID} - deliver a pending prompt as {@code steer} or {@code queue}. */
    default void updateInboxItem(String sessionId, String messageId, String delivery) throws OpencodeException {
        throw new UnsupportedOperationException("updateInboxItem");
    }

    /** {@code DELETE /api/session/:id/inbox/:msgID} - cancel a pending prompt. */
    default void cancelInboxItem(String sessionId, String messageId) throws OpencodeException {
        throw new UnsupportedOperationException("cancelInboxItem");
    }

    /** {@code GET /api/command} - the project's custom slash commands. */
    default List<CommandInfo> getCommands() throws OpencodeException {
        return List.of();
    }

    /** Scoped variant (see {@link #getAgents(String)}): commands live in the project's config. */
    default List<CommandInfo> getCommands(String directory) throws OpencodeException {
        return getCommands();
    }

    /**
     * {@code POST /api/session/:id/command} - execute a custom slash command in a
     * session and wait for the reply.
     */
    default ChatEntry runCommand(String sessionId, String command, List<String> arguments)
            throws OpencodeException {
        throw new UnsupportedOperationException("runCommand");
    }

    /**
     * {@code POST /api/session/:id/shell} - execute a shell command in the
     * session context. The client sends {@code {command}} and polls
     * {@code GET /api/session/:id/message} for its completion. The reply is the shell message,
     * mapped leniently into {@link ShellResult}.
     */
    default ShellResult runShell(String sessionId, String agent, String command) throws OpencodeException {
        throw new UnsupportedOperationException("runShell");
    }

    /** {@code GET /api/project} - all projects the server knows. */
    default List<ProjectSummary> getProjects() throws OpencodeException {
        return List.of();
    }

    /** {@code GET /api/vcs} - VCS state of the current project. */
    default VcsInfo getVcsInfo() throws OpencodeException {
        return new VcsInfo(null, null);
    }

    /**
     * {@code GET /api/vcs?location[directory]=…} - VCS state scoped to one project directory.
     * Matters on the shared v2 service, whose own cwd is the user's home.
     */
    default VcsInfo getVcsInfo(String directory) throws OpencodeException {
        return getVcsInfo();
    }

    /**
     * {@code GET /api/fs/list?path=…} - one level of the workspace file tree
     * (empty path or {@code "."} = the project root).
     */
    default List<FileNode> listFiles(String path) throws OpencodeException {
        return List.of();
    }

    /** {@code GET /api/fs/list?path=…&location[directory]=…} - listing scoped to a project. */
    default List<FileNode> listFiles(String path, String directory) throws OpencodeException {
        return listFiles(path);
    }

    /** {@code GET /api/fs/find?query=…&location[directory]=…} - file-name search scoped to a project. */
    default List<String> findFiles(String query, String directory) throws OpencodeException {
        return findFiles(query);
    }

    /** {@code GET /api/fs/find?query=…&type=file} - fuzzy file-name search (paths). */
    default List<String> findFiles(String query) throws OpencodeException {
        return List.of();
    }

    /**
     * {@code PATCH /api/experimental/config} - apply partial config changes (e.g. switch the
     * default model) and get the updated config back.
     *
     * @param changes JSON-serializable partial config (e.g. {@code {"model":"x/y"}})
     */
    default ConfigInfo patchConfig(Map<String, Object> changes) throws OpencodeException {
        throw new UnsupportedOperationException("patchConfig");
    }

    /**
     * Report whether a TUI action was driven. The v2 HTTP implementation
     * returns {@code false} without a request because there is no TUI-control
     * endpoint; callers use that result to display unsupported-operation feedback.
     *
     * @param action the TUI action name
     * @param body   JSON-serializable payload (may be {@code null} for no-arg actions)
     */
    default boolean tuiAction(String action, Map<String, Object> body) throws OpencodeException {
        throw new UnsupportedOperationException("tuiAction");
    }

    // ---------- file status/content, provider auth, global events ----------

    /**
     * {@code GET /api/vcs/status} - the git status of all changed files in the
     * project. Default returns empty so test fakes and partial
     * implementations stay compiling.
     */
    default List<FileStatus> getFileStatus() throws OpencodeException {
        return List.of();
    }

    /** Scoped variant (see {@link #getAgents(String)}): v2 resolves {@code /vcs/status} per location. */
    default List<FileStatus> getFileStatus(String directory) throws OpencodeException {
        return getFileStatus();
    }

    /**
     * {@code GET /api/fs/read/<path>} - a file's raw content ({@code null} when
     * the file does not exist, HTTP 404).
     */
    default String getFileContent(String path) throws OpencodeException {
        return null;
    }

    /** {@code GET /api/fs/read/<path>?location[directory]=…} - content scoped to a project. */
    default String getFileContent(String path, String directory) throws OpencodeException {
        return getFileContent(path);
    }

    /**
     * {@code GET /api/integration} - the available auth methods, flattened into
     * one {@link ProviderAuth} per method. Default returns empty so test fakes
     * and partial implementations stay compiling.
     */
    default List<ProviderAuth> getProviderAuths() throws OpencodeException {
        return List.of();
    }

    /** Scoped integration catalog (see {@link #getAgents(String)}). */
    default List<ProviderAuth> getProviderAuths(String directory) throws OpencodeException {
        return getProviderAuths();
    }

    /**
     * Read {@code /api/integration}, select the first OAuth method, and call
     * {@code POST /api/integration/:id/connect/oauth} with its {@code methodID}.
     * Return the authorization URL and instructions so the caller can open it.
     * Parsed leniently: an HTTP error, an empty or
     * malformed body, or a missing url yields an {@link OauthStart} with a
     * {@code null} {@code url} ("not started"); transport errors still
     * throw.
     */
    default OauthStart beginProviderOauth(String providerId) throws OpencodeException {
        throw new UnsupportedOperationException("beginProviderOauth");
    }

    /**
     * Boolean convenience for {@link #beginProviderOauth(String)}:
     * {@code true} iff the server answered with a non-blank authorization
     * URL.
     */
    default boolean startProviderOauth(String providerId) throws OpencodeException {
        OauthStart started = beginProviderOauth(providerId);
        return started != null && started.url() != null && !started.url().isBlank();
    }

    /**
     * {@code GET /api/event} - the SSE stream of events across all projects.
     * The parser maps each frame's {@code data} payload and
     * {@code location.directory} into {@link OpencodeEvent}. Returns a
     * not-yet-started {@link OpencodeEventStream}; the caller owns
     * {@code start()} and {@code stop()}.
     */
    default OpencodeEventStream getGlobalEvents(Consumer<OpencodeEvent> sink, Consumer<Boolean> connectionListener) {
        throw new UnsupportedOperationException("getGlobalEvents");
    }
}
