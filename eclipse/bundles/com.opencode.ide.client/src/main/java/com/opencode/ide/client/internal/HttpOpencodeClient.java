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

    private static final Gson GSON = new Gson();

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
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public HealthStatus getHealth() throws OpencodeException {
        return get("/global/health", HealthStatus.class);
    }

    @Override
    public List<Agent> getAgents() throws OpencodeException {
        return getList("/agent", Agent.class);
    }

    @Override
    public ProviderList getProviders() throws OpencodeException {
        return get("/config/providers", ProviderList.class);
    }

    @Override
    public List<Session> getSessions() throws OpencodeException {
        return getList("/session", Session.class);
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
        return parseBody("GET", "/session/status", request("GET", "/session/status", null),
                TypeToken.getParameterized(Map.class, String.class, SessionStatus.class).getType());
    }

    @Override
    public ConfigInfo getConfig() throws OpencodeException {
        return get("/config", ConfigInfo.class);
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
        String path = "/session";
        if (directory != null) {
            String encoded = URLEncoder.encode(directory.toString(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            path = path + "?directory=" + encoded;
        }
        return parseBody("POST", path, request("POST", path, body.toString()), Session.class);
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) throws OpencodeException {
        request("POST", "/mcp", McpRequests.registerBody(name, config));
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
            // live v1.18 shape: {"<name>": {"status": "connected"}, ...} — a MAP, not an array
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                ClientLog.warning("opencode GET /mcp: unexpected shape (not a JSON object); treating as empty");
                return List.of();
            }
            List<McpServerInfo> out = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String status = null;
                if (entry.getValue().isJsonObject()) {
                    JsonObject value = entry.getValue().getAsJsonObject();
                    if (value.has("status") && value.get("status").isJsonPrimitive()) {
                        status = value.get("status").getAsString();
                    }
                }
                out.add(new McpServerInfo(entry.getKey(), status));
            }
            return out;
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /mcp: malformed body; treating as empty: " + truncate(body, 120));
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
        return parseBody("GET", path, response,
                TypeToken.getParameterized(List.class, elementType).getType());
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
        String body = ChatRequests.messageBody(chatRequest);
        String path = "/session/" + chatRequest.sessionId() + "/message";
        // the blocking prompt POST waits for the FINAL reply: an agent may
        // stream for many minutes, so unattended callers pass their whole run
        // budget; the fixed 5-minute default is only for interactive use
        Duration timeout = promptTimeout == null || promptTimeout.isNegative() || promptTimeout.isZero()
                ? ClientTuning.PROMPT_TIMEOUT
                : promptTimeout;
        return parseBody("POST", path, request("POST", path, body, timeout),
                ChatEntry.class);
    }

    @Override
    public void abortSession(String sessionId) throws OpencodeException {
        String path = "/session/" + sessionId + "/abort";
        HttpResponse<String> response = send("POST", path, null, ClientTuning.REQUEST_TIMEOUT);
        int status = response.statusCode();
        if (status >= 500) {
            throw new OpencodeException("opencode POST " + path + " failed: HTTP " + status
                    + " - " + truncate(response.body(), 500));
        }
        if (status >= 400) {
            // usually "session is already idle" - the outcome the caller wanted
            ClientLog.warning("opencode POST " + path + " returned HTTP " + status
                    + " (treated as already idle): " + truncate(response.body(), 200));
        }
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra) throws OpencodeException {
        JsonObject body = new JsonObject();
        body.addProperty("service", service);
        body.addProperty("level", level);
        body.addProperty("message", message);
        if (extra != null) {
            body.add("extra", GSON.toJsonTree(extra));
        }
        request("POST", "/log", GSON.toJson(body));
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
        return parseBody("POST", "/session/" + sessionId + "/revert",
                request("POST", "/session/" + sessionId + "/revert", body.toString()), Boolean.class);
    }

    @Override
    public boolean unrevertSession(String sessionId) throws OpencodeException {
        return parseBody("POST", "/session/" + sessionId + "/unrevert",
                request("POST", "/session/" + sessionId + "/unrevert", null), Boolean.class);
    }

    @Override
    public boolean summarizeSession(String sessionId, String providerId, String modelId) throws OpencodeException {
        JsonObject body = new JsonObject();
        body.addProperty("providerID", providerId);
        body.addProperty("modelID", modelId);
        return parseBody("POST", "/session/" + sessionId + "/summarize",
                request("POST", "/session/" + sessionId + "/summarize", body.toString()), Boolean.class);
    }

    @Override
    public Session shareSession(String sessionId) throws OpencodeException {
        return parseBody("POST", "/session/" + sessionId + "/share",
                request("POST", "/session/" + sessionId + "/share", null), Session.class);
    }

    @Override
    public Session unshareSession(String sessionId) throws OpencodeException {
        return parseBody("DELETE", "/session/" + sessionId + "/share",
                request("DELETE", "/session/" + sessionId + "/share", null), Session.class);
    }

    @Override
    public boolean respondToPermission(String sessionId, String permissionId, String response, boolean remember)
            throws OpencodeException {
        JsonObject body = new JsonObject();
        body.addProperty("response", response);
        body.addProperty("remember", remember);
        String path = "/session/" + sessionId + "/permissions/" + permissionId;
        return parseBody("POST", path, request("POST", path, body.toString()), Boolean.class);
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
        JsonObject body = new JsonObject();
        body.addProperty("agent", agent);
        body.addProperty("command", command);
        String path = "/session/" + sessionId + "/shell";
        // the server awaits the spawned process - allow as long as a chat reply
        HttpResponse<String> response = request("POST", path, body.toString(), ClientTuning.PROMPT_TIMEOUT);
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            throw new OpencodeException("opencode POST " + path + " failed: HTTP " + response.statusCode()
                    + " - empty response body where JSON was expected");
        }
        try {
            return shellResult(JsonParser.parseString(responseBody).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException e) {
            throw new OpencodeException("opencode POST " + path + " failed: HTTP " + response.statusCode()
                    + " - malformed response body: " + truncate(responseBody, 300), e);
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
            // lenient: {"worktree": "...", "vcs": {"branch": "...", "repository": "..."}} entries
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
                String worktree = stringOf(project, "worktree");
                String branch = null;
                String repository = null;
                if (project.has("vcs") && project.get("vcs").isJsonObject()) {
                    JsonObject vcs = project.getAsJsonObject("vcs");
                    branch = stringOf(vcs, "branch");
                    repository = stringOf(vcs, "repository");
                }
                out.add(new ProjectSummary(worktree, branch, repository));
            }
            return out;
        } catch (JsonParseException e) {
            ClientLog.warning("opencode GET /project: malformed body; treating as empty: " + truncate(body, 120));
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
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            return new VcsInfo(stringOf(object, "branch"), stringOf(object, "repository"));
        } catch (JsonParseException | IllegalStateException e) {
            ClientLog.warning("opencode GET /vcs: malformed body; treating as empty: " + truncate(body, 120));
            return new VcsInfo(null, null);
        }
    }

    @Override
    public List<FileNode> listFiles(String path) throws OpencodeException {
        // The server REQUIRES the `path` query key - omitting it for the root
        // answers HTTP 400 {"name":"BadRequest","message":"Missing key at [\"path\"]"}.
        // "." is the workspace root; the server accepts both / and \ separators.
        String effective = (path == null || path.isBlank()) ? "." : path;
        String target = "/file?path="
                + URLEncoder.encode(effective, StandardCharsets.UTF_8).replace("+", "%20");
        return getListOrEmptyOn404(target, FileNode.class);
    }

    @Override
    public List<SearchMatch> findText(String pattern) throws OpencodeException {
        String target = "/find?pattern=" + URLEncoder.encode(pattern, StandardCharsets.UTF_8).replace("+", "%20");
        return getListOrEmptyOn404(target, SearchMatch.class);
    }

    @Override
    public List<String> findFiles(String query) throws OpencodeException {
        String target = "/find/file?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> response = request("GET", target, null);
        return parseBody("GET", target, response,
                TypeToken.getParameterized(List.class, String.class).getType());
    }

    @Override
    public List<SymbolResult> findSymbols(String query) throws OpencodeException {
        String target = "/find/symbol?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        return getListOrEmptyOn404(target, SymbolResult.class);
    }

    @Override
    public ConfigInfo patchConfig(Map<String, Object> changes) throws OpencodeException {
        return parseBody("PATCH", "/config", request("PATCH", "/config", GSON.toJson(changes)), ConfigInfo.class);
    }

    @Override
    public boolean tuiAction(String action, Map<String, Object> body) throws OpencodeException {
        String path = "/tui/" + action;
        String payload = body == null ? null : GSON.toJson(body);
        HttpResponse<String> response = send("POST", path, payload, ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() >= 400) {
            ClientLog.warning("opencode POST " + path + " returned HTTP " + response.statusCode()
                    + " (TUI not attached?): " + truncate(response.body(), 200));
            return false; // no TUI client attached is an expected outcome, not an error
        }
        return true;
    }

    // ---------- H5 remainder ----------

    @Override
    public List<FileStatus> getFileStatus() throws OpencodeException {
        return getListOrEmptyOn404("/file/status", FileStatus.class);
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
            ClientLog.warning("opencode GET " + target + ": malformed body; treating as empty: " + truncate(body, 120));
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
            ClientLog.warning("opencode GET /provider/auth: malformed body; treating as empty: " + truncate(body, 120));
            return List.of();
        }
    }

    @Override
    public OauthStart beginProviderOauth(String providerId) throws OpencodeException {
        JsonObject body = new JsonObject();
        body.addProperty("method", 0);
        String path = "/provider/" + providerId + "/oauth/authorize";
        HttpResponse<String> response = send("POST", path, body.toString(), ClientTuning.REQUEST_TIMEOUT);
        if (response.statusCode() >= 400) {
            ClientLog.warning("opencode POST " + path + " returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body(), 200));
            return new OauthStart(null, null, null); // no OAuth method / validation error - nothing started
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return new OauthStart(null, null, null); // method index 0 was not an OAuth flow - nothing started
        }
        try {
            JsonObject object = JsonParser.parseString(responseBody).getAsJsonObject();
            return new OauthStart(stringOf(object, "url"), stringOf(object, "method"),
                    stringOf(object, "instructions"));
        } catch (JsonParseException | IllegalStateException e) {
            ClientLog.warning("opencode POST " + path + ": malformed body; treating as not started: "
                    + truncate(responseBody, 120));
            return new OauthStart(null, null, null);
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

    /** Leniently flattens a {@code {info, parts}} (WithParts) shell reply into {@link ShellResult}. */
    private static ShellResult shellResult(JsonObject envelope) {
        JsonObject info = asObject(envelope, "info");
        String messageId = info == null ? null : stringOf(info, "id");
        String agent = info == null ? null : stringOf(info, "agent");
        String command = null;
        String status = null;
        String output = null;
        if (envelope.has("parts") && envelope.get("parts").isJsonArray()) {
            for (JsonElement element : envelope.get("parts").getAsJsonArray()) {
                if (!element.isJsonObject() || !"tool".equals(stringOf(element.getAsJsonObject(), "type"))) {
                    continue;
                }
                JsonObject state = asObject(element.getAsJsonObject(), "state");
                if (state == null) {
                    continue;
                }
                status = stringOf(state, "status");
                JsonObject input = asObject(state, "input");
                command = input == null ? null : stringOf(input, "command");
                output = stringOf(state, "output");
                if (output == null) {
                    JsonObject metadata = asObject(state, "metadata");
                    output = metadata == null ? null : stringOf(metadata, "output");
                }
                break;
            }
        }
        return new ShellResult(messageId, agent, command, status, output);
    }

    private <T> T get(String path, Class<T> type) throws OpencodeException {
        return parseBody("GET", path, request("GET", path, null), type);
    }

    private <T> List<T> getList(String path, Class<T> elementType) throws OpencodeException {
        return parseBody("GET", path, request("GET", path, null),
                TypeToken.getParameterized(List.class, elementType).getType());
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
                + " - " + truncate(response.body(), 500));
    }

    /** Sends the request and maps transport failures only; status handling is the caller's. */
    private HttpResponse<String> send(String method, String path, String body, Duration timeout)
            throws OpencodeException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
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

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
