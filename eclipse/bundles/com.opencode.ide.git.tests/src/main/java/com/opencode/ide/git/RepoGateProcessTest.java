package com.opencode.ide.git;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RepoGateProcessTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test(timeout = 30000)
    public void differentJvmsAndLinkedRootsSerializeReadModifyWrite() throws Exception {
        Path repo = tmp.newFolder("repo").toPath();
        Path linked = tmp.newFolder("linked").toPath();
        Path metadata = repo.resolve(".git/worktrees/linked");
        Files.createDirectories(metadata);
        Files.writeString(linked.resolve(".git"), "gitdir: " + metadata);
        Files.writeString(metadata.resolve("commondir"), "../..");
        Path counter = repo.resolve("counter");
        Files.writeString(counter, "0");
        Process first = peer(repo, counter);
        Process second = peer(linked, counter);
        try {
            assertEquals('R', first.getInputStream().read());
            assertEquals('R', second.getInputStream().read());
            first.getOutputStream().write('!');
            first.getOutputStream().flush();
            second.getOutputStream().write('!');
            second.getOutputStream().flush();
            assertTrue(first.waitFor(15, TimeUnit.SECONDS));
            assertTrue(second.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue());
            assertEquals(0, second.exitValue());
            assertEquals("100", Files.readString(counter));
        } finally {
            first.destroyForcibly().waitFor();
            second.destroyForcibly().waitFor();
        }
    }

    private static Process peer(Path repo, Path counter) throws Exception {
        String classpath = Path.of(RepoGateProcessTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()) + java.io.File.pathSeparator
                + Path.of(RepoGate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CounterPeer.class.getName(), repo.toString(), counter.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    public static final class CounterPeer {
        public static void main(String[] args) throws Exception {
            Path repo = Path.of(args[0]);
            Path counter = Path.of(args[1]);
            System.out.print('R');
            System.out.flush();
            System.in.read();
            for (int i = 0; i < 50; i++) {
                RepoGate.with(repo, () -> RepoGate.with(repo, () -> {
                    try {
                        int value = Integer.parseInt(Files.readString(counter));
                        // Widen the unprotected read/write window; correctness
                        // is the final count, not a timing assertion.
                        Thread.sleep(1);
                        Files.writeString(counter, Integer.toString(value + 1));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
        }
    }
}
