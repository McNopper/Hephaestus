package com.opencode.ide.fleet;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.git.StoreGitStatus;
import com.opencode.ide.git.StoreSync;
import com.opencode.ide.tools.McpTool;
import com.opencode.ide.tools.McpToolResult;
import com.opencode.ide.tools.ParamError;
import com.opencode.ide.tools.ToolProvider;

/**
 * The {@code fleet_*} tool pack: chat-first control of the task fleet, so a
 * chat agent can do everything the Board's buttons do. Served over stdio via
 * {@link FleetStdioMain} ({@code eclipse/fleet-tools.ps1}, configured as the
 * {@code fleet} MCP server in {@code opencode.json}); tool names surface
 * prefixed as {@code fleet_fleet_*} in opencode sessions, like the
 * {@code tasks} server's {@code tasks_task_*}.
 *
 * <p>Tools: {@code fleet_dispatch} (async launch for one ticket - the engine
 * spawns its own {@code opencode serve} in the repo, isolates the work in a
 * git worktree and merges back), {@code fleet_jobs} (live job snapshot),
 * {@code fleet_job_details} (live progress probe for one job: busy, message
 * count, completion flag - the "are we moving?" answer),
 * {@code fleet_permissions}/{@code fleet_permissions_answer} (the chat path
 * of unattended sessions' permission asks - list and answer them without a
 * Board), {@code fleet_sync_store}/{@code fleet_status_store}/{@code fleet_recover_store}
 * (the distributed-fleet store discipline, path-scoped to the store subtree).</p>
 *
 * <p>Parameter names keep the {@code ticket_id}/{@code project} spellings of
 * the {@code task_*} pack. Error channel: {@link ParamError} for structural
 * argument problems (JSON-RPC -32602), {@link McpToolResult#error} text for
 * domain problems.</p>
 */
public final class FleetToolProvider implements ToolProvider {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final List<McpTool> TOOLS = buildTools();

    private final Path root;
    private final FleetControl control;
    /** B-004 test seam: the worktree manager {@code fleet_reset} removes residue through. */
    private final com.opencode.ide.git.WorktreeManager worktrees;

    /** @param root the task store root, as for {@code TaskToolProvider}. */
    public FleetToolProvider(Path root) {
        this(root, FleetControl.spawn(root));
    }

    /** Test seam: inject a prepared control (e.g. a fake engine). */
    public FleetToolProvider(Path root, FleetControl control) {
        this(root, control, com.opencode.ide.git.FleetGit.defaultManager());
    }

    /**
     * Test seam: inject the {@link com.opencode.ide.git.WorktreeManager} the
     * {@code fleet_reset} recovery path uses (failure injection without
     * processes). Public because the tests live in a separate OSGi bundle —
     * package-private access fails across Equinox class loaders (the same
     * lesson as {@code ToolInvocation#tail}, hit live 2026-09-18 on the
     * B-004 merge).
     */
    public FleetToolProvider(Path root, FleetControl control, com.opencode.ide.git.WorktreeManager worktrees) {
        this.root = root;
        this.control = control;
        this.worktrees = worktrees;
    }

    /** Releases the engine (and in real mode the spawned opencode server). */
    public void close() {
        control.close();
    }

    @Override
    public String language() {
        return "fleet";
    }

    @Override
    public List<McpTool> tools() {
        return TOOLS;
    }

    @Override
    public McpToolResult call(String toolName, JsonObject arguments) {
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        try {
            return dispatch(toolName, args);
        } catch (com.opencode.ide.tasks.TaskStore.NotFound
                | com.opencode.ide.tasks.TaskStore.Invalid e) {
            return McpToolResult.error(e.getMessage());
        } catch (IllegalStateException e) {
            return McpToolResult.error(e.getMessage());
        }
    }

    private McpToolResult dispatch(String name, JsonObject a) {
        switch (name) {
            case "fleet_dispatch":
                return dispatchTicket(a);
            case "fleet_jobs":
                return jobs();
            case "fleet_job_details":
                return jobDetails(a);
            case "fleet_permissions":
                return permissions();
            case "fleet_permissions_answer":
                return answerPermission(a);
            case "fleet_sync_store":
            {
                String message = a.has("message") && !a.get("message").isJsonNull()
                        ? reqStr(a, "message")
                        : "opencode fleet: store sync";
                StoreSync.Outcome outcome = StoreSync.sync(root, message);
                String hint = outcome == StoreSync.Outcome.PULL_CONFLICT
                        ? " - run fleet_recover_store to abort the rebase and keep local commits"
                        : "";
                return text("store sync: " + outcome + hint);
            }
            case "fleet_status_store":
            {
                StoreGitStatus status = StoreGitStatus.load(root);
                return text(status.exists()
                        ? "store " + status.summary()
                        : "store is not inside a git repository");
            }
            case "fleet_recover_store":
                return text("store recover: " + StoreSync.recover(root));
            case "fleet_reset":
                return resetTicket(a);
            case "fleet_auto_start":
                try {
                    control.startAuto(reqStr(a, "project"), reqStr(a, "sprint"),
                            a.has("max_concurrent") ? reqInt(a, "max_concurrent") : 4,
                            a.has("cost_budget_usd") ? a.get("cost_budget_usd").getAsDouble() : 5,
                            a.has("include_stale") && a.get("include_stale").getAsBoolean());
                } catch (IllegalArgumentException e) {
                    throw new ParamError(e.getMessage());
                }
                return json(control.autoStatus());
            case "fleet_auto_stop":
                control.stopAuto();
                return json(control.autoStatus());
            case "fleet_auto_status":
                return json(control.autoStatus());
            default:
                throw new IllegalArgumentException("unknown tool: " + name);
        }
    }

    private McpToolResult dispatchTicket(JsonObject a) {
        String project = reqStr(a, "project");
        String ticketId = reqStr(a, "ticket_id");
        com.opencode.ide.tasks.Task task =
                new com.opencode.ide.tasks.TaskStore(root).get(project, ticketId);
        if (task.blocked) {
            return McpToolResult.error("ticket " + ticketId + " is blocked: " + task.blocker
                    + " - clear the blocker (task_clear_blocked) before dispatching");
        }
        if ("done".equals(task.status)) {
            return McpToolResult.error("ticket " + ticketId + " is already done");
        }
        FleetJob tracked = control.jobs().get(ticketId);
        if (tracked != null && tracked.state() == FleetJob.State.RUNNING) {
            return McpToolResult.error("ticket " + ticketId + " is already in flight"
                    + " - see fleet_jobs");
        }
        Duration timeout = Duration.ofMinutes(
                Math.max(1, (int) Math.min(FleetTuning.MAX_TICKET_BUDGET.toMinutes(), a.has("timeout_minutes")
                        ? reqInt(a, "timeout_minutes")
                        : FleetControl.DEFAULT_TIMEOUT.toMinutes())));
        control.dispatch(project, ticketId, timeout);
        JsonObject out = new JsonObject();
        out.addProperty("ticket_id", ticketId);
        out.addProperty("state", FleetJob.State.RUNNING.name());
        out.addProperty("poll", "fleet_jobs");
        return json(out);
    }

    private McpToolResult jobs() {
        JsonArray arr = new JsonArray();
        control.jobs().forEach((id, job) -> {
            JsonObject o = new JsonObject();
            o.addProperty("ticket_id", id);
            o.addProperty("state", job.state().name());
            if (job.sessionId() != null) {
                o.addProperty("session_id", job.sessionId());
            }
            if (job.worktree() != null) {
                o.addProperty("worktree", job.worktree().toString());
            }
            if (job.detail() != null) {
                o.addProperty("detail", job.detail());
            }
            arr.add(o);
        });
        return json(arr);
    }

    private McpToolResult permissions() {
        JsonArray arr = new JsonArray();
        for (PermissionRequest request : control.permissions().pending()) {
            JsonObject o = new JsonObject();
            o.addProperty("permission_id", request.permissionId());
            o.addProperty("session_id", request.sessionId());
            if (request.permission() != null) {
                o.addProperty("permission", request.permission());
            }
            if (request.title() != null) {
                o.addProperty("title", request.title());
            }
            JsonArray patterns = new JsonArray();
            for (String pattern : request.patterns()) {
                patterns.add(pattern);
            }
            o.add("patterns", patterns);
            arr.add(o);
        }
        return json(arr);
    }

    private McpToolResult answerPermission(JsonObject a) {
        String permissionId = reqStr(a, "permission_id");
        PermissionQueue.Response response = responseOf(reqStr(a, "response"));
        if (response == null) {
            throw new ParamError("response must be one of once|always|reject");
        }
        boolean remember = a.has("remember") && a.get("remember").isJsonPrimitive()
                && a.get("remember").getAsBoolean();
        PermissionQueue.AnswerResult result =
                control.permissions().answer(permissionId, response, remember);
        return text(result.message());
    }

    /** Maps a wire response word to its enum, case-insensitively; {@code null} when unknown. */
    private static PermissionQueue.Response responseOf(String value) {
        for (PermissionQueue.Response response : PermissionQueue.Response.values()) {
            if (response.wire().equalsIgnoreCase(value)) {
                return response;
            }
        }
        return null;
    }

    private McpToolResult jobDetails(JsonObject a) {
        String ticketId = reqStr(a, "ticket_id");
        FleetJob job = control.jobs().get(ticketId);
        JsonObject o = new JsonObject();
        o.addProperty("ticket_id", ticketId);
        if (job == null) {
            o.addProperty("state", "unknown");
            o.addProperty("hint", "no job in this engine - never dispatched here, or settled;"
                    + " the ticket itself is the source of truth");
            return json(o);
        }
        o.addProperty("state", job.state().name());
        if (job.sessionId() != null) {
            o.addProperty("session_id", job.sessionId());
        }
        if (job.worktree() != null) {
            o.addProperty("worktree", job.worktree().toString());
        }
        if (job.detail() != null) {
            o.addProperty("detail", job.detail());
        }
        FleetRunner.Activity activity = control.jobActivity(ticketId);
        if (activity == null) {
            o.addProperty("probe", "unreachable");
        } else {
            o.addProperty("busy", activity.busy());
            o.addProperty("messages", activity.messages());
            o.addProperty("complete", activity.complete());
            if (activity.lastAssistant() != null) {
                o.addProperty("last_assistant_text", activity.lastAssistant());
            }
        }
        return json(o);
    }

    /**
     * F-002 {@code fleet_reset}: consume a settled run's residue - remove the
     * worktree + branch, release the ticket to sprint-backlog (claim cleared,
     * blocked flag cleared) so a plain re-dispatch works without manual git
     * surgery. Refused while the job is RUNNING.
     */
    private McpToolResult resetTicket(JsonObject a) {
        String project = reqStr(a, "project");
        String ticketId = reqStr(a, "ticket_id");
        com.opencode.ide.tasks.TaskStore taskStore = new com.opencode.ide.tasks.TaskStore(root);
        com.opencode.ide.tasks.Task task;
        try {
            task = taskStore.get(project, ticketId);
        } catch (com.opencode.ide.tasks.TaskStore.NotFound | com.opencode.ide.tasks.TaskStore.Invalid e) {
            return McpToolResult.error(e.getMessage());
        }
        FleetJob tracked = control.jobs().get(ticketId);
        if (tracked != null && tracked.state() == FleetJob.State.RUNNING) {
            return McpToolResult.error("ticket " + ticketId + " is RUNNING - abort it first (see fleet_job_details)");
        }
        Path repoRoot = FleetControl.repoRootOf(root);
        FleetControl.sweepStaleMarkers(repoRoot);
        try (DispatchGuard guard = DispatchGuard.acquire(repoRoot, project, ticketId)) {
            // Re-read after acquiring ownership; the preflight snapshot may
            // precede a completed peer launch. Reset intentionally reopens it.
            taskStore.get(project, ticketId);
            return resetReserved(taskStore, project, ticketId, repoRoot);
        }
    }

    private McpToolResult resetReserved(com.opencode.ide.tasks.TaskStore taskStore,
            String project, String ticketId, Path repoRoot) {
        StringBuilder report = new StringBuilder();
        try {
            worktrees.remove(repoRoot, ticketId, true);
            report.append("worktree+branch removed; ");
        } catch (RuntimeException e) {
            // B-004 live-found 2026-09-17: a leaked process holding files in
            // the worktree (typically this engine's own spawned opencode
            // serve - its watchers and bash-tool children keep handles
            // inside) makes `git worktree remove --force` fail with
            // "Permission denied", which used to abort the WHOLE reset
            // before the branch cleanup - so the next dispatch hit "Branch
            // opencode/<id> already exists". Kill the engine's serve (when
            // idle), let the OS release the dead process's handles, then
            // retry the removal once.
            Long killedPid = control.recycleEngineIfIdle("fleet_reset " + ticketId);
            if (killedPid != null) {
                settleAfterServeKill();
            }
            try {
                worktrees.remove(repoRoot, ticketId, true);
                report.append(killedPid != null
                        ? "worktree+branch removed (killed the leaked engine serve pid "
                                + killedPid + " first); "
                        : "worktree+branch removed on retry; ");
            } catch (RuntimeException retry) {
                return McpToolResult.error(worktreeRemovalFailed(ticketId, retry));
            }
        }
        try {
            java.util.Map<String, Object> release = new java.util.HashMap<>();
            release.put("status", "sprint-backlog");
            release.put("assignee", null);
            taskStore.update(project, ticketId, release);
            report.append("ticket released to sprint-backlog; ");
        } catch (RuntimeException e) {
            return McpToolResult.error("reset " + ticketId + " failed releasing ticket: " + e.getMessage());
        }
        // P1-1: the update() switch silently drops blocked/blocker — use the
        // dedicated clearBlocked (which also writes the history marker)
        try {
            if (taskStore.get(project, ticketId).blocked) {
                taskStore.clearBlocked(project, ticketId, "fleet");
                report.append("blocked flag cleared");
            } else {
                report.append("(was not blocked)");
            }
        } catch (RuntimeException e) {
            return McpToolResult.error("reset " + ticketId + " failed clearing blocker: " + e.getMessage());
        }
        return text("reset " + ticketId + ": " + report);
    }

    /**
     * B-004: the retry also failed - name the likely file holder. When this
     * engine's serve is still alive it could not be killed (launches are in
     * flight), so it is the prime suspect and is named by pid; otherwise an
     * unrelated process holds the worktree and the error says how to find
     * it (pure Java cannot enumerate handle holders).
     */
    private String worktreeRemovalFailed(String ticketId, RuntimeException retry) {
        String base = "reset " + ticketId + " failed removing worktree: " + retry.getMessage();
        Long servePid = control.engineServePid();
        if (servePid != null) {
            return base + " - this engine's opencode serve (pid " + servePid + ") is still running"
                    + " with launches in flight and is the likely file holder; retry once they settle"
                    + " (fleet_jobs)";
        }
        return base + " - a process outside this engine holds files in the worktree; find and stop it"
                + " (Windows: Resource Monitor > CPU > Associated Handles, or Sysinternals handle.exe),"
                + " then retry fleet_reset";
    }

    /** B-004: brief pause so the OS finishes releasing the killed serve's handles. */
    private static void settleAfterServeKill() {
        try {
            Thread.sleep(FleetTuning.SERVE_KILL_SETTLE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static McpToolResult json(Object element) {
        return new McpToolResult(PRETTY.toJson(element), false);
    }

    private static McpToolResult text(String s) {
        return new McpToolResult(s, false);
    }

    private static String reqStr(JsonObject a, String key) {
        if (!a.has(key) || !a.get(key).isJsonPrimitive()) {
            throw new ParamError("missing string parameter: " + key);
        }
        String v = a.get(key).getAsString();
        if (v.isBlank()) {
            throw new ParamError("blank string parameter: " + key);
        }
        return v;
    }

    private static int reqInt(JsonObject a, String key) {
        try {
            return a.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw new ParamError("not an integer parameter: " + key);
        }
    }

    private static List<McpTool> buildTools() {
        List<McpTool> out = new ArrayList<>();
        out.add(new McpTool("fleet_auto_start",
                "Start opt-in automatic dispatch for one explicit project/sprint. Uses the Board readiness "
                        + "policy and cross-engine reservations. Failed tickets stay blocked. Budget is an "
                        + "admission estimate, not a hard billing limit. Replaces this engine's prior loop.",
                schema(new String[]{"project", "sprint"}, obj -> {
                    obj.add("project", strP("task store project"));
                    obj.add("sprint", strP("sprint id to dispatch"));
                    obj.add("max_concurrent", intP("concurrency cap, default 4"));
                    JsonObject budget = new JsonObject();
                    budget.addProperty("type", "number");
                    budget.addProperty("description", "project cost admission budget USD, default 5; 0 unlimited");
                    obj.add("cost_budget_usd", budget);
                    obj.add("include_stale", boolP("rerun stale tickets, default false"));
                })));
        out.add(new McpTool("fleet_auto_stop", "Stop this engine's auto-dispatch loop; running jobs settle normally.",
                schema(new String[0], obj -> { })));
        out.add(new McpTool("fleet_auto_status", "Report this engine's auto-dispatch state and sprint scope.",
                schema(new String[0], obj -> { })));
        out.add(new McpTool("fleet_dispatch",
                "Launch the task fleet for one ticket (chat-first control of what the Board's "
                        + "Launch task button does): spawns a dedicated opencode server in the repo, "
                        + "runs the ticket's stage/role prompt in an isolated git worktree, merges "
                        + "back on completion and records artifacts/actuals on the ticket. Async - "
                        + "returns immediately; poll fleet_jobs for the outcome. The ticket must "
                        + "exist, be unblocked, not done and not already in flight.",
                schema(new String[]{"project", "ticket_id"}, obj -> {
                    obj.add("project", strP("task store project (subdirectory of the store root)"));
                    obj.add("ticket_id", strP("the ticket to launch, e.g. T-042"));
                    obj.add("timeout_minutes", intP("per-ticket run budget, default 30, max 1440"));
                })));
        out.add(new McpTool("fleet_jobs",
                "Live snapshot of the fleet's jobs (keyed by ticket id): state "
                        + "RUNNING/COMPLETED/MERGED/FAILED plus session id, worktree and failure "
                        + "detail when present. Empty before the first dispatch.",
                schema(new String[0], obj -> { })));
        out.add(new McpTool("fleet_job_details",
                "In-flight PROGRESS for one job - are we moving or hung? Reports the job's state"
                        + " plus a live probe of its session: busy flag, message count (grows while"
                        + " the worker streams) and the completion flag. The engine's watchdog"
                        + " aborts a session after ~5 minutes without new messages, so a hung"
                        + " worker fails fast while slow-but-working ones are never killed.",
                schema(new String[]{"ticket_id"},
                        obj -> obj.add("ticket_id", strP("the ticket to inspect, e.g. W-004")))));
        out.add(new McpTool("fleet_permissions",
                "Pending permission asks of unattended fleet sessions (a launched "
                        + "session waiting mid-run for human approval): each with "
                        + "permission_id, session_id, permission, title and patterns. "
                        + "Empty when no session is waiting; before the first dispatch "
                        + "there is nothing to answer. Answer via "
                        + "fleet_permissions_answer.",
                schema(new String[0], obj -> { })));
        out.add(new McpTool("fleet_permissions_answer",
                "Answer one pending permission ask listed by fleet_permissions: "
                        + "once approves this occurrence, always approves every "
                        + "matching ask, reject denies it. remember (default false) "
                        + "persists the decision as a rule. Unknown or already-answered "
                        + "ids return the failure explanation as plain text.",
                schema(new String[]{"permission_id", "response"}, obj -> {
                    obj.add("permission_id", strP("the ask to answer, e.g. per_1 (from fleet_permissions)"));
                    obj.add("response", strP("once | always | reject"));
                    obj.add("remember", boolP("persist the decision as a rule, default false"));
                })));
        out.add(new McpTool("fleet_sync_store",
                "Sync the task store's git repo (the distributed-fleet discipline): "
                        + "add -A (scoped to the store subtree - never touches the rest of the repo), "
                        + "commit, pull --rebase, push. On PULL_CONFLICT run "
                        + "fleet_recover_store next.",
                schema(new String[0], obj ->
                        obj.add("message", strP("commit message, default 'opencode fleet: store sync'")))));
        out.add(new McpTool("fleet_status_store",
                "One-line git status of the task store repo (branch, ahead/behind, changed).",
                schema(new String[0], obj -> { })));
        out.add(new McpTool("fleet_recover_store",
                "Abort a wedged store rebase after a PULL_CONFLICT (local commits are kept; "
                        + "re-claim means re-dispatch).",
                schema(new String[0], obj -> { })));
        out.add(new McpTool("fleet_reset",
                "Consume a settled run's residue: remove the worktree + branch and release the "
                        + "ticket to sprint-backlog (claim and blocker cleared) - a plain "
                        + "re-dispatch then works with no manual git surgery. Refused while "
                        + "the job is RUNNING. When a leaked process holds the worktree's files, "
                        + "this engine's spawned opencode serve is killed first (when idle) and "
                        + "the removal retried; a still-failing removal names the locking pid.",
                schema(new String[]{"project", "ticket_id"}, obj -> {
                    obj.add("project", strP("task store project (subdirectory of the store root)"));
                    obj.add("ticket_id", strP("the ticket whose residue to consume"));
                })));
        return List.copyOf(out);
    }

    private interface Props {
        void accept(JsonObject props);
    }

    private static JsonObject schema(String[] required, Props fill) {
        JsonObject props = new JsonObject();
        fill.accept(props);
        JsonArray req = new JsonArray();
        for (String r : required) {
            req.add(r);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", req);
        return schema;
    }

    private static JsonObject strP(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject intP(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "integer");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject boolP(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        o.addProperty("description", description);
        return o;
    }
}
