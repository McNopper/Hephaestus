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
    default ChatEntry sendMessage(ChatRequest request, java.time.Duration promptTimeout)
            throws OpencodeException {
        return sendMessage(request);
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
