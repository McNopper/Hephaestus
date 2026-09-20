package com.opencode.ide.client.internal;

import com.opencode.ide.client.ClientTuning;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.ChatRequests;
import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.ConnectionConfig;
import com.opencode.ide.client.McpRequests;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeConnectionException;
import com.opencode.ide.client.OpencodeEventStream;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatEntryDeserializer;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.CommandInfo;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.FileDiff;
import com.opencode.ide.client.model.FileNode;
import com.opencode.ide.client.model.FileStatus;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.McpServerInfo;
import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.OauthStart;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.client.model.ProjectSummary;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderAuth;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.SearchMatch;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;
import com.opencode.ide.client.model.SessionTodo;
import com.opencode.ide.client.model.ShellResult;
import com.opencode.ide.client.model.SkillInfo;
import com.opencode.ide.client.model.SymbolResult;
import com.opencode.ide.client.model.VcsInfo;

/**
 * {@link OpencodeClient} backed by {@code java.net.http.HttpClient} + Gson.
 *
 * <p>Internal implementation detail: the {@code internal} package is not
 * exported (visible only to the client's own tests), so
 * {@code OpencodeClients.http(...)} is the only way other layers obtain an
 * {@link OpencodeClient}.</p>
 */
public final class HttpOpencodeClient implements OpencodeClient {

    private static final Gson GSON = new com.google.gson.GsonBuilder()
            .registerTypeAdapter(ChatEntry.class, new ChatEntryDeserializer())
            .create();

    private final HttpClient http;
    private final URI baseUri;
    private final String authHeader;
    private final ConnectionConfig config;

    public HttpOpencodeClient(ConnectionConfig config) {
        this.config = config;
        this.baseUri = config.baseUrl();
        this.authHeader = Auth.basicHeader(config.username(), config.password());
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // the opencode server hangs on h2c-upgrade POSTs
                .connectTimeout(ClientTuning.CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public HealthStatus getHealth() throws OpencodeException {
        // v2 removed GET /global/health; GET /api/info is the equivalent probe
        // ({version, pid, urls, paths}). Reaching it at all means the server is
        // up, and it still carries the version ServerVersionPin checks.
        HttpResponse<String> response = send("GET", "/info", null, ClientTuning.REQUEST_TIMEOUT);
        boolean healthy = response.statusCode() < 300;
        if (!healthy) {
            return new HealthStatus(false, null);
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new OpencodeException("opencode GET /api/info failed: HTTP " + response.statusCode()
                    + " - empty response body where JSON was expected");
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            // Gson's lenient parser accepts bare junk as a string primitive -
            // the info endpoint must answer an OBJECT, anything else is malformed
            if (!element.isJsonObject()) {
                throw new JsonParseException("expected a JSON object");
            }
            JsonObject info = element.getAsJsonObject();
            String version = (info.has("version") && info.get("version").isJsonPrimitive())
                    ? info.get("version").getAsString()
                    : null;
            return new HealthStatus(true, version);
        } catch (JsonParseException e) {
            throw new OpencodeException("opencode GET /api/info failed: HTTP " + response.statusCode()
                    + " - malformed response body: " + truncate(body, 300), e);
        }
    }

    @Override
    public List<Agent> getAgents() throws OpencodeException {
        return getList("/agent", Agent.class);
    }

    @Override
    public ProviderList getProviders() throws OpencodeException {
        // v2: /config/providers is gone. Rebuild from /api/provider (provider
        // entries) + /api/model (model entries grouped by providerID).
        List<Model> allModels = getList("/model", Model.class);
        HttpResponse<String> providerResponse = send("GET", "/provider", null, ClientTuning.REQUEST_TIMEOUT);
        List<Provider> providers = new ArrayList<>();
        try {
            JsonElement element = JsonParser.parseString(providerResponse.body());
            if (element.isJsonObject() && element.getAsJsonObject().has("data")
                    && element.getAsJsonObject().get("data").isJsonArray()) {
                for (JsonElement entry : element.getAsJsonObject().getAsJsonArray("data")) {
                    if (!entry.isJsonObject()) {
                        continue;
                    }
                    JsonObject p = entry.getAsJsonObject();
                    String id = p.has("id") ? p.get("id").getAsString() : null;
                    String name = p.has("name") ? p.get("name").getAsString() : id;
                    Map<String, Model> models = new java.util.LinkedHashMap<>();
                    for (Model m : allModels) {
                        if (id != null && id.equals(m.providerID())) {
                            models.put(m.id(), m);
                        }
                    }
                    providers.add(new Provider(id, name, null, List.of(), null, Map.of(), models));
                }
            }
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /provider: malformed body: " + truncate(providerResponse.body(), 200));
        }
        return new ProviderList(providers, Map.of());
    }

    @Override
    public List<Session> getSessions() throws OpencodeException {
        return getSessions(null);
    }

    @Override
    public List<Session> getSessions(String directory) throws OpencodeException {
        String path = "/session";
        if (directory != null && !directory.isBlank()) {
            path += "?directory=" + URLEncoder.encode(directory, StandardCharsets.UTF_8).replace("+", "%20");
        }
        return getList(path, Session.class);
    }


    @Override
    public Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
        // v2 replaced GET /session/status with GET /session/active, which lists
        // ONLY the running sessions ({"data": {"ses_x": {"type": "running"}}}) -
        // absence means idle, the same busy-only contract v1 had.
        HttpResponse<String> response = send("GET", "/session/active", null, ClientTuning.REQUEST_TIMEOUT);
        Map<String, SessionStatus> statuses = new java.util.LinkedHashMap<>();
        if (response.statusCode() == 404) {
            return statuses;
        }
        try {
            JsonElement element = JsonParser.parseString(response.body());
            if (element.isJsonObject() && element.getAsJsonObject().has("data")
                    && element.getAsJsonObject().get("data").isJsonObject()) {
                for (String sessionId : element.getAsJsonObject().getAsJsonObject("data").keySet()) {
                    statuses.put(sessionId, new SessionStatus("busy"));
                }
            }
        } catch (JsonParseException e) {
            throw new OpencodeException("opencode GET /api/session/active failed: malformed response body: "
                    + truncate(response.body(), 300), e);
        }
        return statuses;
    }

    @Override
    public ConfigInfo getConfig() throws OpencodeException {
        // v2 returns an ARRAY of config sources: [{type:"document", path, info:{...}}].
        // Find the one with a model (usually the project-level document).
        HttpResponse<String> response = send("GET", "/config", null, ClientTuning.REQUEST_TIMEOUT);
        String body = response.body();
        if (body == null || body.isBlank()) {
            return new ConfigInfo(null, null);
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonArray()) {
                return new ConfigInfo(null, null);
            }
            for (JsonElement source : element.getAsJsonArray()) {
                if (!source.isJsonObject()) {
                    continue;
                }
                JsonObject src = source.getAsJsonObject();
                if (!src.has("info") || !src.get("info").isJsonObject()) {
                    continue;
                }
                JsonObject info = src.getAsJsonObject("info");
                if (info.has("model") && info.get("model").isJsonObject()) {
                    JsonObject model = info.getAsJsonObject("model");
                    String provider = model.has("providerID") ? model.get("providerID").getAsString() : null;
                    String modelId = model.has("model") ? model.get("model").getAsString() : null;
                    if (provider != null && modelId != null) {
                        return new ConfigInfo(provider + "/" + modelId, null);
                    }
                }
            }
            return new ConfigInfo(null, null);
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /config: malformed body: " + truncate(body, 200));
            return new ConfigInfo(null, null);
        }
    }

    @Override
    public Session createSession(String title) throws OpencodeException {
        return createSession(title, null);
    }

    @Override
    public Session createSession(String title, Path directory) throws OpencodeException {
        JsonObject body = new JsonObject();
        if (title != null && !title.isBlank()) {
            body.addProperty("title", title);
        }
        // v2 scopes the session via a location object in the BODY (v1 used a
        // ?directory= query parameter that v2 no longer reads)
        if (directory != null) {
            JsonObject location = new JsonObject();
            location.addProperty("directory", directory.toString());
            body.add("location", location);
        }
        return parseBody("POST", "/session", request("POST", "/session", body.toString()), Session.class);
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) throws OpencodeException {
        // v2: PUT /api/experimental/mcp/:server (v1 POSTed the name in the body)
        request("PUT", "/experimental/mcp/" + URLEncoder.encode(name, StandardCharsets.UTF_8),
                McpRequests.registerBody(name, config));
    }

    @Override
    public List<McpServerInfo> getMcpServers() throws OpencodeException {
        HttpResponse<String> response = send("GET", "/mcp", null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() == 404) {
            return List.of();
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            // v2 shape: {"location": {...}, "data": [{"name": "...", "status": "..."}, ...]}
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject() || !element.getAsJsonObject().has("data")
                    || !element.getAsJsonObject().get("data").isJsonArray()) {
                ClientLog.warning("opencode GET /mcp: unexpected shape (no data array); treating as empty");
                return List.of();
            }
            List<McpServerInfo> out = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonObject().getAsJsonArray("data")) {
                if (!entry.isJsonObject()) {
                    continue;
                }
                JsonObject server = entry.getAsJsonObject();
                String name = stringOf(server, "name");
                out.add(new McpServerInfo(name != null ? name : stringOf(server, "id"),
                        stringOf(server, "status")));
            }
            return out;
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /mcp: malformed body; treating as empty: " + truncate(body, ClientTuning.SNIPPET_MIN));
            return List.of();
        }
    }

    @Override
    public List<SkillInfo> getSkills() throws OpencodeException {
        return getListOrEmptyOn404("/skill", SkillInfo.class);
    }

    /**
     * List GET that treats HTTP 404 as empty: these endpoints are absent on
     * older opencode builds and an empty section beats a broken view.
     */
    private <T> List<T> getListOrEmptyOn404(String path, Class<T> elementType) throws OpencodeException {
        HttpResponse<String> response = send("GET", path, null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() == 404) {
            return List.of();
        }
        try {
            return parseBody("GET", path, response,
                    TypeToken.getParameterized(List.class, elementType).getType(), true);
        } catch (OpencodeException e) {
            // an empty section beats a broken view: these auxiliary endpoints
            // (file tree, search) tolerate a wrong-shaped 200 the same as a 404
            ClientLog.warning("opencode GET " + path + ": " + e.getMessage() + " (treating as empty)");
            return List.of();
        }
    }

    @Override
    public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
        return getList("/session/" + sessionId + "/message", ChatEntry.class);
    }

    @Override
    public List<SessionTodo> getSessionTodos(String sessionId) throws OpencodeException {
        return getList("/session/" + sessionId + "/todo", SessionTodo.class);
    }

    @Override
    public ChatEntry sendMessage(ChatRequest chatRequest) throws OpencodeException {
        return sendMessage(chatRequest, ClientTuning.PROMPT_TIMEOUT);
    }

    @Override
    public ChatEntry sendMessage(ChatRequest chatRequest, Duration promptTimeout) throws OpencodeException {
        // v2 split v1's single blocking POST /message. The agent and the model
        // are session state set before the turn, the per-request system prompt
        // becomes a synthetic message, and POST /prompt is ASYNCHRONOUS: it
        // returns the queued user message, so the reply has to be polled.
        String sessionId = chatRequest.sessionId();
        postIfPresent("/session/" + sessionId + "/agent", ChatRequests.agentBody(chatRequest));
        postIfPresent("/session/" + sessionId + "/model", ChatRequests.modelBody(chatRequest));
        postIfPresent("/session/" + sessionId + "/synthetic", ChatRequests.syntheticBody(chatRequest));

        String path = "/session/" + sessionId + "/prompt";
        request("POST", path, ChatRequests.promptBody(chatRequest), ClientTuning.REQUEST_TIMEOUT);

        // an agent may stream for many minutes, so unattended callers pass their
        // whole run budget; the fixed default is only for interactive use
        Duration budget = promptTimeout == null || promptTimeout.isNegative() || promptTimeout.isZero()
                ? ClientTuning.PROMPT_TIMEOUT
                : promptTimeout;
        return awaitReply(sessionId, budget);
    }

    /** POSTs a prepared body when the request produced one; a {@code null} body is a no-op. */
    private void postIfPresent(String path, String body) throws OpencodeException {
        if (body != null) {
            request("POST", path, body, ClientTuning.REQUEST_TIMEOUT);
        }
    }

    /**
     * Polls {@code GET /session/:id/message} until the newest assistant message
     * is complete, the turn ends with an {@code idle} marker, or the budget runs
     * out. v2 has no blocking send, so this restores the synchronous contract
     * the chat view and the fleet are built on.
     */
    private ChatEntry awaitReply(String sessionId, Duration budget) throws OpencodeException {
        long deadline = System.nanoTime() + budget.toNanos();
        ChatEntry newest = null;
        while (System.nanoTime() < deadline) {
            List<ChatEntry> messages = getMessages(sessionId);
            newest = newestAssistant(messages);
            if (endedTurn(messages, newest)) {
                return newest;
            }
            try {
                Thread.sleep(ClientTuning.REPLY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpencodeException("opencode " + sessionId + ": interrupted while awaiting the reply", e);
            }
        }
        throw new OpencodeException("opencode " + sessionId + ": no completed reply within "
                + budget.toSeconds() + "s (the server may still be working - raise the budget"
                + " or check whether the session is stuck busy)");
    }

    /** True once the turn is over: a completed assistant message or an {@code idle} marker. */
    private static boolean endedTurn(List<ChatEntry> messages, ChatEntry newestAssistant) {
        if (newestAssistant != null && newestAssistant.info() != null && newestAssistant.info().isComplete()) {
            return true;
        }
        for (ChatEntry entry : messages) {
            if (entry != null && entry.info() != null && "idle".equals(entry.info().role())) {
                return true;
            }
        }
        return false;
    }

    /** The most recent assistant message; v2 returns the list newest-first. */
    private static ChatEntry newestAssistant(List<ChatEntry> messages) {
        for (ChatEntry entry : messages) {
            if (entry != null && entry.info() != null && "assistant".equals(entry.info().role())) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public void abortSession(String sessionId) throws OpencodeException {
        // v2 renamed POST /session/:id/abort to /interrupt
        String path = "/session/" + sessionId + "/interrupt";
        HttpResponse<String> response = send("POST", path, null, ClientTuning.REQUEST_TIMEOUT);
        int status = response.statusCode();
        if (status >= 400 && status != 404) {
            throw new OpencodeException("opencode POST /api" + path + " failed: HTTP " + status
                    + " - " + truncate(response.body(), ClientTuning.SNIPPET_MAX));
        }
        if (status >= 400) {
            // usually "session is already idle" - the outcome the caller wanted
            ClientLog.warning("opencode POST /api" + path + " returned HTTP " + status
                    + " (treated as already idle): " + truncate(response.body(), 200));
        }
    }

    @Override
    public void deleteSession(String sessionId) throws OpencodeException {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        request("DELETE", "/session/" + java.net.URLEncoder.encode(sessionId,
                java.nio.charset.StandardCharsets.UTF_8), null);
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra) throws OpencodeException {
        // v2 removed POST /log (404). Logging stays client-side in v2.
        ClientLog.info("[" + service + "] " + level + ": " + message);
    }

    // ---------- H5 surface ----------

    @Override
    public List<FileDiff> getSessionDiff(String sessionId, String messageId) throws OpencodeException {
        String path = "/session/" + sessionId + "/diff";
        if (messageId != null && !messageId.isBlank()) {
            path += "?messageID=" + URLEncoder.encode(messageId, StandardCharsets.UTF_8).replace("+", "%20");
        }
        return getList(path, FileDiff.class);
    }

    @Override
    public Session forkSession(String sessionId, String messageId) throws OpencodeException {
        JsonObject body = new JsonObject();
        if (messageId != null && !messageId.isBlank()) {
            body.addProperty("messageID", messageId);
        }
        return parseBody("POST", "/session/" + sessionId + "/fork",
                request("POST", "/session/" + sessionId + "/fork", body.toString()), Session.class);
    }

    @Override
    public boolean revertMessage(String sessionId, String messageId, String partId) throws OpencodeException {
        JsonObject body = new JsonObject();
        if (messageId != null) {
            body.addProperty("messageID", messageId);
        }
        if (partId != null) {
            body.addProperty("partID", partId);
        }
        return parseBody("POST", "/session/" + sessionId + "/revert/stage",
                request("POST", "/session/" + sessionId + "/revert/stage", body.toString()), Boolean.class);
    }

    @Override
    public boolean unrevertSession(String sessionId) throws OpencodeException {
        // v2: clearing the revert is DELETE /session/:id/revert (v1 POST /unrevert)
        String path = "/session/" + sessionId + "/revert";
        HttpResponse<String> response = send("DELETE", path, null, ClientTuning.REQUEST_TIMEOUT);
        return response.statusCode() < 300;
    }

    @Override
    public boolean summarizeSession(String sessionId, String providerId, String modelId) throws OpencodeException {
        // v2 renamed /summarize to /compact and takes the model as a Model.Ref
        JsonObject body = new JsonObject();
        if (providerId != null && modelId != null) {
            JsonObject model = new JsonObject();
            model.addProperty("id", modelId);
            model.addProperty("providerID", providerId);
            body.add("model", model);
        }
        String path = "/session/" + sessionId + "/compact";
        HttpResponse<String> response = send("POST", path, body.toString(), ClientTuning.REQUEST_TIMEOUT);
        return response.statusCode() < 300;
    }

    @Override
    public boolean respondToPermission(String sessionId, String permissionId, String response, boolean remember)
            throws OpencodeException {
        // v2: POST /session/:id/permission/:requestID/reply with a decision of
        // once|always|reject (v1 posted {response, remember} to /permissions/:id)
        JsonObject body = new JsonObject();
        body.addProperty("decision", decisionOf(response, remember));
        String path = "/session/" + sessionId + "/permission/" + permissionId + "/reply";
        HttpResponse<String> reply = send("POST", path, body.toString(), ClientTuning.REQUEST_TIMEOUT);
        return reply.statusCode() < 300;
    }

    /** Maps the client's {@code (response, remember)} pair onto v2's single decision. */
    private static String decisionOf(String response, boolean remember) {
        if (response != null && response.toLowerCase(java.util.Locale.ROOT).startsWith("r")) {
            return "reject";
        }
        return remember ? "always" : "once";
    }

    @Override
    public List<CommandInfo> getCommands() throws OpencodeException {
        return getListOrEmptyOn404("/command", CommandInfo.class);
    }

    @Override
    public ChatEntry runCommand(String sessionId, String command, List<String> arguments) throws OpencodeException {
        JsonObject body = new JsonObject();
        body.addProperty("command", command);
        if (arguments != null && !arguments.isEmpty()) {
            body.add("arguments", GSON.toJsonTree(arguments));
        }
        String path = "/session/" + sessionId + "/command";
        return parseBody("POST", path, request("POST", path, body.toString(), ClientTuning.PROMPT_TIMEOUT),
                ChatEntry.class);
    }

    @Override
    public ShellResult runShell(String sessionId, String agent, String command) throws OpencodeException {
        // v2's shell body is just the command - the agent moved to session state
        if (agent != null && !agent.isBlank()) {
            JsonObject agentBody = new JsonObject();
            agentBody.addProperty("agent", agent);
            request("POST", "/session/" + sessionId + "/agent", agentBody.toString(),
                    ClientTuning.REQUEST_TIMEOUT);
        }
        JsonObject body = new JsonObject();
        body.addProperty("command", command);
        String path = "/session/" + sessionId + "/shell";
        request("POST", path, body.toString(), ClientTuning.REQUEST_TIMEOUT);
        return awaitShell(sessionId);
    }

    /**
     * Polls the message list for the newest shell message (v2's
     * {@code Session.Message.Shell}) until it leaves the {@code running} state.
     * v2's POST /shell returns no shell result body - the command's lifecycle
     * is reported as messages, same async split as the prompt path.
     */
    private ShellResult awaitShell(String sessionId) throws OpencodeException {
        long deadline = System.nanoTime() + ClientTuning.PROMPT_TIMEOUT.toNanos();
        ShellResult latest = null;
        while (System.nanoTime() < deadline) {
            latest = newestShellMessage(sessionId);
            if (latest != null && !"running".equals(latest.status())) {
                return latest;
            }
            try {
                Thread.sleep(ClientTuning.REPLY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpencodeException("opencode " + sessionId
                        + ": interrupted while awaiting the shell command", e);
            }
        }
        if (latest != null) {
            return latest; // still running past the budget: report what we have
        }
        throw new OpencodeException("opencode " + sessionId + ": no shell message appeared within "
                + ClientTuning.PROMPT_TIMEOUT.toSeconds() + "s");
    }

    /** The newest {@code type:"shell"} message as a {@link ShellResult}, or {@code null}. */
    private ShellResult newestShellMessage(String sessionId) throws OpencodeException {
        String path = "/session/" + sessionId + "/message";
        HttpResponse<String> response = send("GET", path, null, ClientTuning.REQUEST_TIMEOUT);
        String body = response.body();
        // a dead endpoint must fail fast, not burn the whole poll budget
        if (response.statusCode() >= 400) {
            throw new OpencodeException("opencode GET /api" + path + " failed: HTTP " + response.statusCode()
                    + " - " + truncate(body, ClientTuning.SNIPPET_MAX));
        }
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            JsonElement data = element.isJsonObject() && element.getAsJsonObject().has("data")
                    ? element.getAsJsonObject().get("data")
                    : element;
            if (!data.isJsonArray()) {
                return null;
            }
            for (JsonElement item : data.getAsJsonArray()) {
                if (!item.isJsonObject() || !"shell".equals(stringOf(item.getAsJsonObject(), "type"))) {
                    continue;
                }
                JsonObject message = item.getAsJsonObject();
                JsonObject output = asObject(message, "output");
                return new ShellResult(stringOf(message, "id"), null, stringOf(message, "command"),
                        stringOf(message, "status"), output == null ? null : stringOf(output, "output"));
            }
            return null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    @Override
    public List<ProjectSummary> getProjects() throws OpencodeException {
        HttpResponse<String> response = send("GET", "/project", null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() == 404) {
            return List.of();
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            // v2 Project: {id, canonical, vcs: <type string>, name, time, sandboxes}
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonArray()) {
                return List.of();
            }
            List<ProjectSummary> out = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject project = item.getAsJsonObject();
                // canonical is the project directory; v2 carries no per-project
                // branch/remote here (just the vcs TYPE), so those stay null
                out.add(new ProjectSummary(stringOf(project, "canonical"), null, null));
            }
            return out;
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /project: malformed body; treating as empty: " + truncate(body, ClientTuning.SNIPPET_MIN));
            return List.of();
        }
    }

    @Override
    public VcsInfo getVcsInfo() throws OpencodeException {
        HttpResponse<String> response = send("GET", "/vcs", null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() == 404) {
            return new VcsInfo(null, null);
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return new VcsInfo(null, null);
        }
        try {
            // v2: {"location": {...}, "data": {"provider": ..., "branch": {"current", "default"}}}
            JsonObject envelope = JsonParser.parseString(body).getAsJsonObject();
            JsonObject data = asObject(envelope, "data");
            JsonObject branch = data == null ? null : asObject(data, "branch");
            String current = branch == null ? null
                    : (stringOf(branch, "current") != null ? stringOf(branch, "current")
                            : stringOf(branch, "default"));
            return new VcsInfo(current, null); // v2 no longer reports the remote URL here
        } catch (JsonParseException | IllegalStateException e) {
            ClientLog.warning("opencode GET /vcs: malformed body; treating as empty: " + truncate(body, ClientTuning.SNIPPET_MIN));
            return new VcsInfo(null, null);
        }
    }

    @Override
    public List<FileNode> listFiles(String path) throws OpencodeException {
        // v2 moved the file listing to /fs/list; the `path` query key is still
        // required - "." is the workspace root, both / and \ are accepted.
        String effective = (path == null || path.isBlank()) ? "." : path;
        String target = "/fs/list?path="
                + URLEncoder.encode(effective, StandardCharsets.UTF_8).replace("+", "%20");
        return getListOrEmptyOn404(target, FileNode.class);
    }

    @Override
    public List<SearchMatch> findText(String pattern) throws OpencodeException {
        // TODO(v2): /find* became /fs/find (query/type/limit params) - the type
        // enum is unverified, so this stays on the 404-tolerant path until the
        // exact v2 find contract is confirmed against a live server.
        String target = "/find?pattern=" + URLEncoder.encode(pattern, StandardCharsets.UTF_8).replace("+", "%20");
        return getListOrEmptyOn404(target, SearchMatch.class);
    }

    @Override
    public List<String> findFiles(String query) throws OpencodeException {
        // v2: GET /fs/find?query=...&type=file -> {location, data: [{path, type}]}
        String target = "/fs/find?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20")
                + "&type=file";
        HttpResponse<String> response = send("GET", target, null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() == 404) {
            return List.of();
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            JsonElement data = element.isJsonObject() && element.getAsJsonObject().has("data")
                    ? element.getAsJsonObject().get("data")
                    : element;
            if (!data.isJsonArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonElement item : data.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    String path = stringOf(item.getAsJsonObject(), "path");
                    if (path != null) {
                        out.add(path);
                    }
                }
            }
            return out;
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /fs/find: malformed body; treating as empty: "
                    + truncate(body, ClientTuning.SNIPPET_MIN));
            return List.of();
        }
    }

    @Override
    public List<SymbolResult> findSymbols(String query) throws OpencodeException {
        // TODO(v2): no v2 equivalent - /fs/find only matches file/directory
        // names (type: file|directory), there is no symbol search. Stays on the
        // 404-tolerant path (empty section) until opencode re-adds one.
        String target = "/find/symbol?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        return getListOrEmptyOn404(target, SymbolResult.class);
    }

    @Override
    public ConfigInfo patchConfig(Map<String, Object> changes) throws OpencodeException {
        // v2 moved the mutable config surface to /api/experimental/config
        String path = "/experimental/config";
        return parseBody("PATCH", path, request("PATCH", path, GSON.toJson(changes)), ConfigInfo.class);
    }

    @Override
    public boolean tuiAction(String action, Map<String, Object> body) throws OpencodeException {
        // v2 removed the POST /tui/:action control endpoint; driving an attached
        // TUI is now done by publishing tui.* events, which this client does not
        // do. Reported as "no TUI attached" - the same outcome callers already
        // handle - rather than failing the caller.
        // TODO(v2): re-implement over the tui.* event surface if the IDE ever
        // needs to steer an attached TUI again.
        ClientLog.warning("opencode TUI action '" + action + "' skipped: v2 has no /tui endpoint");
        return false;
    }

    // ---------- H5 remainder ----------

    @Override
    public List<FileStatus> getFileStatus() throws OpencodeException {
        return getListOrEmptyOn404("/vcs/status", FileStatus.class);
    }

    @Override
    public String getFileContent(String path) throws OpencodeException {
        String target = "/file/content?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> response = send("GET", target, null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() == 404) {
            return null;
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            // lenient envelope: {"type":"text","content":"…"} (content is base64 for binary)
            return stringOf(JsonParser.parseString(body).getAsJsonObject(), "content");
        } catch (JsonParseException | IllegalStateException e) {
            ClientLog.warning("opencode GET " + target + ": malformed body; treating as empty: " + truncate(body, ClientTuning.SNIPPET_MIN));
            return null;
        }
    }

    @Override
    public List<ProviderAuth> getProviderAuths() throws OpencodeException {
        HttpResponse<String> response = send("GET", "/provider/auth", null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() == 404) {
            return List.of();
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            // live v1.18 shape: {"<providerID>": [{"type":"oauth","label":"…"}, …], …} — a MAP of method lists
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                ClientLog.warning("opencode GET /provider/auth: unexpected shape (not a JSON object); "
                        + "treating as empty");
                return List.of();
            }
            List<ProviderAuth> out = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement method : entry.getValue().getAsJsonArray()) {
                    if (!method.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = method.getAsJsonObject();
                    out.add(new ProviderAuth(entry.getKey(), stringOf(object, "type"), stringOf(object, "label")));
                }
            }
            return out;
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /provider/auth: malformed body; treating as empty: " + truncate(body, ClientTuning.SNIPPET_MIN));
            return List.of();
        }
    }

    @Override
    public OauthStart beginProviderOauth(String providerId) throws OpencodeException {
        // v2 flow: the provider's integration lists its auth methods; the first
        // OAuth method is started via POST /api/integration/:id/connect/oauth
        // (v1 posted a method index to /provider/:id/oauth/authorize).
        String methodId = firstOauthMethodId(providerId);
        if (methodId == null) {
            return new OauthStart(null, null, null); // no OAuth method - nothing started
        }
        JsonObject body = new JsonObject();
        body.addProperty("methodID", methodId);
        String path = "/integration/" + URLEncoder.encode(providerId, StandardCharsets.UTF_8)
                + "/connect/oauth";
        HttpResponse<String> response = send("POST", path, body.toString(), ClientTuning.REQUEST_TIMEOUT);
        String responseBody = response.body();
        if (response.statusCode() >= 400) {
            ClientLog.warning("opencode POST " + path + " returned HTTP " + response.statusCode()
                    + ": " + truncate(responseBody, 200));
            return new OauthStart(null, null, null);
        }
        try {
            JsonObject envelope = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject data = asObject(envelope, "data");
            if (data == null) {
                return new OauthStart(null, null, null);
            }
            // Integration.AttemptEncoded: {attemptID, url, instructions, mode}
            return new OauthStart(stringOf(data, "url"), stringOf(data, "mode"),
                    stringOf(data, "instructions"));
        } catch (JsonParseException | IllegalStateException e) {
            ClientLog.warning("opencode POST " + path + ": malformed body; treating as not started: "
                    + truncate(responseBody, ClientTuning.SNIPPET_MIN));
            return new OauthStart(null, null, null);
        }
    }

    /**
     * The id of the provider integration's first {@code type:"oauth"} method
     * (e.g. {@code "device"}, {@code "browser"}), or {@code null} when the
     * provider has none / the integration list is unreadable.
     */
    private String firstOauthMethodId(String providerId) throws OpencodeException {
        HttpResponse<String> response = send("GET", "/integration", null, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() >= 400 || response.body() == null) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(response.body());
            JsonElement data = element.isJsonObject() && element.getAsJsonObject().has("data")
                    ? element.getAsJsonObject().get("data")
                    : element;
            if (!data.isJsonArray()) {
                return null;
            }
            for (JsonElement item : data.getAsJsonArray()) {
                if (!item.isJsonObject() || !providerId.equals(stringOf(item.getAsJsonObject(), "id"))) {
                    continue;
                }
                JsonElement methods = item.getAsJsonObject().get("methods");
                if (methods == null || !methods.isJsonArray()) {
                    return null;
                }
                for (JsonElement method : methods.getAsJsonArray()) {
                    if (method.isJsonObject()
                            && "oauth".equals(stringOf(method.getAsJsonObject(), "type"))) {
                        return stringOf(method.getAsJsonObject(), "id");
                    }
                }
                return null;
            }
            return null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    @Override
    public OpencodeEventStream getGlobalEvents(Consumer<OpencodeEvent> sink, Consumer<Boolean> connectionListener) {
        return OpencodeEventStream.global(config, sink, connectionListener);
    }

    private static String stringOf(JsonObject object, String member) {
        if (object.has(member) && object.get(member).isJsonPrimitive()) {
            return object.get(member).getAsString();
        }
        return null;
    }

    private static JsonObject asObject(JsonObject object, String member) {
        if (object.has(member) && object.get(member).isJsonObject()) {
            return object.getAsJsonObject(member);
        }
        return null;
    }

    private <T> T get(String path, Class<T> type) throws OpencodeException {
        return parseBody("GET", path, request("GET", path, null), type);
    }

    private <T> List<T> getList(String path, Class<T> elementType) throws OpencodeException {
        return parseBody("GET", path, request("GET", path, null),
                TypeToken.getParameterized(List.class, elementType).getType(), true);
    }

    /**
     * Parses a response body, unwrapping the v2 {@code {"data": [...]}} list
     * envelope when {@code unwrapData} is true. v2 list endpoints wrap their
     * results in {@code {data, cursor}}; scalar endpoints return their object
     * directly.
     */
    private static <T> T parseBody(String method, String path, HttpResponse<String> response, Type type,
            boolean unwrapData)
            throws OpencodeException {
        if (!unwrapData) {
            return parseBody(method, path, response, type);
        }
        int status = response.statusCode();
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new OpencodeException("opencode " + method + " " + path + " failed: HTTP " + status
                    + " - empty response body where JSON was expected");
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            String inner = body;
            if (element.isJsonObject() && element.getAsJsonObject().has("data")
                    && element.getAsJsonObject().get("data").isJsonArray()) {
                inner = element.getAsJsonObject().get("data").toString();
            }
            T value = GSON.fromJson(inner, type);
            if (value == null) {
                throw new OpencodeException("opencode " + method + " " + path + " failed: HTTP " + status
                        + " - JSON null response: " + truncate(body, 300));
            }
            return value;
        } catch (JsonParseException e) {
            throw new OpencodeException("opencode " + method + " " + path + " failed: HTTP " + status
                    + " - malformed response body: " + truncate(body, 300), e);
        }
    }

    private static <T> T parseBody(String method, String path, HttpResponse<String> response, Type type)
            throws OpencodeException {
        int status = response.statusCode();
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new OpencodeException("opencode " + method + " " + path + " failed: HTTP " + status
                    + " - empty response body where JSON was expected");
        }
        try {
            T value = GSON.fromJson(body, type);
            if (value == null) {
                throw new OpencodeException("opencode " + method + " " + path + " failed: HTTP " + status
                        + " - JSON null response: " + truncate(body, 300));
            }
            return value;
        } catch (JsonParseException e) {
            throw new OpencodeException("opencode " + method + " " + path + " failed: HTTP " + status
                    + " - malformed response body: " + truncate(body, 300), e);
        }
    }

    private HttpResponse<String> request(String method, String path, String body) throws OpencodeException {
        return request(method, path, body, ClientTuning.REQUEST_TIMEOUT);
    }

    private HttpResponse<String> request(String method, String path, String body, Duration timeout)
            throws OpencodeException {
        HttpResponse<String> response = send(method, path, body, timeout);
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response;
        }
        throw new OpencodeException("opencode " + method + " " + path + " failed: HTTP " + status
                + " - " + truncate(response.body(), ClientTuning.SNIPPET_MAX));
    }

    /** Sends the request and maps transport failures only; status handling is the caller's. */
    private HttpResponse<String> send(String method, String path, String body, Duration timeout)
            throws OpencodeException {
        // opencode v2 moved every API under /api/ (v1 served the root directly)
        String apiPath = path.startsWith("/api") ? path : "/api" + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(baseUri.resolve(apiPath))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpencodeConnectionException("Interrupted while calling " + path, e);
        } catch (java.net.http.HttpTimeoutException e) {
            // a timeout is NOT unreachability - the server may be perfectly up,
            // the call just exceeded its budget (e.g. a long-running agent
            // reply). Milestone V finding: conflating the two produced
            // misleading "Cannot reach opencode server" failure details.
            throw new OpencodeConnectionException(
                    "opencode " + method + " " + path + " timed out after " + timeout.toSeconds() + "s"
                            + " (the server itself may still be healthy - raise the budget or check"
                            + " whether the session is stuck busy)", e);
        } catch (IOException e) {
            throw new OpencodeConnectionException(
                    "Cannot reach opencode server at " + baseUri + " (" + method + " " + path + ")", e);
        }
    }

    /**
     * Caps a diagnostic snippet. The knob'd tiers are {@link ClientTuning#SNIPPET_MIN}
     * (warning logs) and {@link ClientTuning#SNIPPET_MAX} (error bodies); the 200/300
     * values in between are fixed diagnostic verbosity, deliberately not knobs - they
     * tune log detail, not operational behavior.
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
