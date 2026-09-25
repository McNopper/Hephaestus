package com.opencode.ide.board.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Tests for {@link PeerJobReconstructor#worktreesVia} - the service-backed
 * peer-worktree source (slice 2, 2026-09-25). The live contract this encodes:
 * {@code GET /api/worktree} requires a {@code projectID} query key,
 * {@code POST /api/worktree/refresh} requires it in the body, and entries
 * carry a {@code directory} whose file name is the task id.
 */
public class PeerJobReconstructorServiceTest {

    /** A minimal client: only the two worktree verbs are real. */
    private static class WorktreeClient implements OpencodeClient {
        private final List<Map<String, Object>> entries;
        int refreshes;
        String refreshedProject;

        WorktreeClient(List<Map<String, Object>> entries) {
            this.entries = entries;
        }

        @Override
        public void refreshWorktrees(String projectID) {
            refreshes++;
            refreshedProject = projectID;
        }

        @Override
        public List<Map<String, Object>> listWorktrees(String projectID) throws OpencodeException {
            assertEquals("the project id reaches the list call", "p1", projectID);
            return entries;
        }

        @Override
        public HealthStatus getHealth() {
            return null;
        }

        @Override
        public List<Agent> getAgents() {
            return List.of();
        }

        @Override
        public ProviderList getProviders() {
            return null;
        }

        @Override
        public ConfigInfo getConfig() {
            return null;
        }

        @Override
        public List<Session> getSessions() {
            return List.of();
        }

        @Override
        public Map<String, SessionStatus> getSessionStatus() {
            return Map.of();
        }

        @Override
        public Session createSession(String title, Path directory) {
            return null;
        }

        @Override
        public void registerMcp(String name, McpServerConfig config) {
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
        public void log(String service, String level, String message, Map<String, Object> extra) {
        }
    }

    @Test
    public void serviceWorktreesBecomeTaskKeyedEntries() {
        WorktreeClient client = new WorktreeClient(List.of(
                Map.of("directory", "C:/repo/.git/opencode-fleet/H1-001", "strategy", "git"),
                Map.of("directory", "C:/repo")));

        Map<String, Path> worktrees = PeerJobReconstructor.worktreesVia(client, "p1");

        assertEquals("the refresh runs first so the service rescans git worktrees",
                1, client.refreshes);
        assertEquals("p1", client.refreshedProject);
        assertEquals("the directory's file name is the task id",
                Set.of("H1-001", "repo"), worktrees.keySet());
        assertEquals(Path.of("C:/repo/.git/opencode-fleet/H1-001"), worktrees.get("H1-001"));
    }

    @Test
    public void serviceFailureYieldsAnEmptyMapAndNeverThrows() {
        WorktreeClient failing = new WorktreeClient(List.of()) {
            @Override
            public List<Map<String, Object>> listWorktrees(String projectID) throws OpencodeException {
                throw new OpencodeException("connection refused");
            }
        };

        assertTrue("a dead service degrades to no worktrees (the caller falls back)",
                PeerJobReconstructor.worktreesVia(failing, "p1").isEmpty());
    }

    @Test
    public void entriesWithoutADirectoryAreSkipped() {
        WorktreeClient client = new WorktreeClient(List.of(Map.of("strategy", "git")));

        assertTrue("an entry without a directory is not a worktree row",
                PeerJobReconstructor.worktreesVia(client, "p1").isEmpty());
    }
}
