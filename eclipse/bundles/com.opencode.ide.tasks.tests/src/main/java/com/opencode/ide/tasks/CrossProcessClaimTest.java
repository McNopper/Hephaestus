package com.opencode.ide.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Cross-process claim: a spawned {@code TasksStdioMain} JVM and this JVM
 * (different processes, so only the OS file lock coordinates them) drain the
 * same sprint without double-claiming. This is the only automated guard of
 * the store's headline design claim: the Eclipse endpoint and the stdio
 * launcher serialize on one store.
 */
public class CrossProcessClaimTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Path jarOf(Class<?> c) {
        try {
            URI uri = c.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Path.of(uri);
            return Files.isDirectory(p) || Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    public void subprocessAndInJvmClaimsAreDistinctAndComplete() throws Exception {
        Path tasksJar = jarOf(TaskStore.class);
        Path toolsJar = jarOf(com.opencode.ide.tools.McpDispatcher.class);
        Path gsonJar = jarOf(com.google.gson.Gson.class);
        Path javaBin = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java");
        assertNotNull("tasks classpath location must resolve", tasksJar);
        assertNotNull("tools classpath location must resolve", toolsJar);
        assertNotNull("gson classpath location must resolve", gsonJar);
        assertTrue("java executable present", Files.isRegularFile(javaBin));

        Path root = tmp.getRoot().toPath().resolve("tasks");
        TaskStore setup = new TaskStore(root);
        final int total = 6;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String id = setup.create("p", TaskStore.CreateSpec.of("t" + i)).id;
            setup.planSprint("p", "S-01", List.of(id), "g");
            ids.add(id);
        }

        // subprocess claims exactly half
        StringBuilder script = new StringBuilder();
        for (int i = 1; i <= total / 2; i++) {
            script.append("{\"jsonrpc\":\"2.0\",\"id\":").append(i)
                    .append(",\"method\":\"tools/call\",\"params\":{\"name\":\"task_claim\",")
                    .append("\"arguments\":{\"project\":\"p\",\"role\":\"developer\",\"by\":\"subproc\"}}}\n");
        }
        List<String> subprocessIds = new ArrayList<>();
        Path stdout = tmp.getRoot().toPath().resolve("stdout.log");
        Path stderr = tmp.getRoot().toPath().resolve("stderr.log");
        ProcessBuilder pb = new ProcessBuilder(javaBin.toString(), "-cp",
                String.join(File.pathSeparator, tasksJar.toString(), toolsJar.toString(), gsonJar.toString()),
                "-Dfile.encoding=UTF-8",
                "com.opencode.ide.tasks.TasksStdioMain", "--root", root.toString())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Process proc = pb.start();
        String output;
        try {
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write(script.toString().getBytes(StandardCharsets.UTF_8));
            }
            // File redirection avoids blocking on a full pipe before the timeout can run.
            boolean exited = proc.waitFor(60, TimeUnit.SECONDS);
            assertTrue("subprocess timed out; stderr: " + Files.readString(stderr), exited);
            assertEquals("subprocess failed; stderr: " + Files.readString(stderr), 0, proc.exitValue());
            output = Files.readString(stdout);
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
                assertTrue("subprocess must stop after timeout", proc.waitFor(10, TimeUnit.SECONDS));
            }
        }
        for (String line : output.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject resp = JsonParser.parseString(line).getAsJsonObject();
            assertTrue("claim call must succeed: " + line, !resp.has("error"));
            String text = resp.getAsJsonObject("result").getAsJsonArray("content").get(0)
                    .getAsJsonObject().get("text").getAsString();
            JsonObject claimed = JsonParser.parseString(text).getAsJsonObject();
            assertTrue(claimed.has("id"));
            subprocessIds.add(claimed.get("id").getAsString());
        }
        assertEquals(total / 2, subprocessIds.size());

        // the in-JVM half drains the rest (null = stop signal)
        TaskStore local = new TaskStore(root);
        List<String> localIds = new ArrayList<>();
        while (true) {
            Task t = local.claim("p", "developer", null, "injvm");
            if (t == null) {
                break;
            }
            localIds.add(t.id);
        }

        Set<String> all = new HashSet<>(subprocessIds);
        for (String id : localIds) {
            assertTrue("ticket claimed by both processes: " + id, all.add(id));
        }
        assertEquals("every ticket claimed exactly once across the two processes", total, all.size());
        assertEquals(new HashSet<>(ids), all);
    }
}
