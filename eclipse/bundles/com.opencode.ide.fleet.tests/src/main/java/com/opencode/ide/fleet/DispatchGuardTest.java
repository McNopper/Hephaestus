package com.opencode.ide.fleet;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DispatchGuardTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void linkedWorktreeSharesReservationsWithMainRepository() throws Exception {
        Path repo = tmp.newFolder("repo").toPath();
        Path linked = tmp.newFolder("linked").toPath();
        Path gitDir = repo.resolve(".git/worktrees/linked");
        Files.createDirectories(gitDir);
        Files.writeString(linked.resolve(".git"), "gitdir: " + gitDir);
        Files.writeString(gitDir.resolve("commondir"), "../..");
        try (DispatchGuard guard = DispatchGuard.acquire(repo, "T-1")) {
            assertEquals(Set.of("T-1"), DispatchGuard.runningIds(linked));
            assertThrows(IllegalStateException.class, () -> DispatchGuard.acquire(linked, "T-1"));
        }
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
        try (DispatchGuard retry = DispatchGuard.acquire(linked, "T-1")) {
            assertEquals(Set.of("T-1"), DispatchGuard.runningIds(repo));
        }
    }

    @Test
    public void ticketCannotEscapeTheReservationDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> DispatchGuard.acquire(tmp.getRoot().toPath(), "../outside"));
    }

    @Test
    public void projectBindingSurvivesReleaseAndRejectsCorruptMetadata() throws Exception {
        Path repo = tmp.getRoot().toPath();
        try (DispatchGuard first = DispatchGuard.acquire(repo, "a", "T-1")) {
            assertEquals(Set.of("T-1"), DispatchGuard.runningIds(repo));
        }
        assertThrows(IllegalStateException.class, () -> DispatchGuard.acquire(repo, "b", "T-1"));
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
        try (DispatchGuard sameProject = DispatchGuard.acquire(repo, "a", "T-1")) {
            assertEquals(Set.of("T-1"), DispatchGuard.runningIds(repo));
        }
        Files.writeString(com.opencode.ide.git.FleetGit.fleetRoot(repo).resolve("T-1.project"), "");
        assertThrows(IllegalStateException.class, () -> DispatchGuard.acquire(repo, "a", "T-1"));
        assertTrue(DispatchGuard.runningIds(repo).isEmpty());
    }

    @Test
    public void staleSweepRetainsUnknownOwnersAndDetectsPidReuse() throws Exception {
        Path repo = tmp.getRoot().toPath();
        Path root = com.opencode.ide.git.FleetGit.fleetRoot(repo);
        Files.createDirectories(root);
        Files.writeString(root.resolve("unknown.dispatch"), "");
        Files.writeString(root.resolve("reused.dispatch"), "pid=" + ProcessHandle.current().pid()
                + " start=1970-01-01T00:00:00Z at test\n");
        assertEquals(1, DispatchGuard.sweepStale(repo));
        assertEquals(Set.of("unknown"), DispatchGuard.runningIds(repo));
    }

    @Test
    public void oldHandleCannotDeleteAReplacedMarker() throws Exception {
        Path repo = tmp.getRoot().toPath();
        DispatchGuard old = DispatchGuard.acquire(repo, "T-1");
        Path marker = com.opencode.ide.git.FleetGit.fleetRoot(repo).resolve("T-1.dispatch");
        // Simulate explicit external repair; ownership tokens fence old handles.
        Files.delete(marker);
        try (DispatchGuard replacement = DispatchGuard.acquire(repo, "T-1")) {
            old.close();
            assertEquals(Set.of("T-1"), DispatchGuard.runningIds(repo));
        }
    }

    @Test
    public void admissionCountsPeerReservationsAndCloseCannotReleaseANewerOwner() {
        Path repo = tmp.getRoot().toPath();
        DispatchGuard old = DispatchGuard.acquire(repo, "T-1");
        assertThrows(IllegalStateException.class,
                () -> DispatchGuard.admit(repo, 1, () -> DispatchGuard.acquire(repo, "T-2")));
        old.close();
        try (DispatchGuard next = DispatchGuard.admit(repo, 1, () -> DispatchGuard.acquire(repo, "T-1"))) {
            old.close();
            assertEquals(Set.of("T-1"), DispatchGuard.runningIds(repo));
        }
    }
}
