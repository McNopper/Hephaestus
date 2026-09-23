package com.opencode.ide.fleet;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.opencode.ide.git.FleetGit;
import com.opencode.ide.tasks.TaskStore;

public class DispatchProcessTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test(timeout = 30000)
    public void simultaneousProcessesShareCapacityAndDeadOwnerIsRecoverable() throws Exception {
        Path repo = tmp.getRoot().toPath();
        try (Peer first = new Peer(repo, "T-1"); Peer second = new Peer(repo, "T-2")) {
            first.signal();
            second.signal();
            String one = first.read();
            String two = second.read();
            assertEquals(Set.of("ACQUIRED", "REFUSED"), Set.of(one, two));
            String owner = "ACQUIRED".equals(one) ? "T-1" : "T-2";
            assertEquals(Set.of(owner), DispatchGuard.runningIds(repo));
            assertEquals(0, DispatchGuard.sweepStale(repo));
            Peer winner = "ACQUIRED".equals(one) ? first : second;
            winner.process.destroyForcibly();
            assertTrue(winner.process.waitFor(10, TimeUnit.SECONDS));
            try (DispatchGuard recovered = DispatchGuard.admit(repo, 1,
                    () -> DispatchGuard.acquire(repo, owner))) {
                assertEquals(Set.of(owner), DispatchGuard.runningIds(repo));
                assertEquals(0, DispatchGuard.sweepStale(repo));
            }
            assertTrue(DispatchGuard.runningIds(repo).isEmpty());
        }
    }

    @Test(timeout = 30000)
    public void resetCannotTouchTicketReservedByAnotherProcess() throws Exception {
        Path repo = tmp.getRoot().toPath();
        TaskStore store = new TaskStore(repo.resolve(".opencode/tasks"));
        var task = store.create("p", new TaskStore.CreateSpec("work", "", "task", "developer",
                "high", 1, List.of(), List.of(), null, null));
        store.planSprint("p", "S-1", List.of(task.id), "goal");
        try (Peer peer = new Peer(repo, task.id);
                FleetControl control = new FleetControl(store.root(), ignored -> {
                    throw new AssertionError("reset must not spawn an engine");
                })) {
            peer.signal();
            assertEquals("ACQUIRED", peer.read());
            var args = new com.google.gson.JsonObject();
            args.addProperty("project", "p");
            args.addProperty("ticket_id", task.id);
            var result = new FleetToolProvider(store.root(), control).call("fleet_reset", args);
            assertTrue(result.text(), result.isError());
            assertTrue(result.text(), result.text().contains("another engine"));
            assertEquals("sprint-backlog", store.get("p", task.id).status);
            assertEquals(Set.of(task.id), DispatchGuard.runningIds(repo));
        }
    }

    private static final class Peer implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;

        Peer(Path repo, String id) throws Exception {
            // Code sources work both in a standalone run and Tycho bundle classloaders.
            String classpath = java.util.stream.Stream.of(ReservationPeer.class, DispatchGuard.class,
                            FleetGit.class, com.opencode.ide.client.ClientLog.class)
                    .map(type -> {
                        try {
                            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, ReservationPeer.class.getName(), repo.toString(), id)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("READY", read());
        }

        String read() throws Exception { return output.readLine(); }

        void signal() throws Exception {
            process.getOutputStream().write('!');
            process.getOutputStream().flush();
        }

        @Override
        public void close() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            output.close();
        }
    }
}
