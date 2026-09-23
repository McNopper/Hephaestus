package com.opencode.ide.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The stdio lifecycle shared by the standalone MCP entry points
 * ({@code TasksStdioMain}, {@code FleetStdioMain}) — B-012's zombie fix.
 *
 * <p><b>Root cause (service-side trigger, launcher-side zombie):</b> when the
 * MCP host abandons a server (service restart, config reload, jar swap under
 * the running JVM) the pipes die in one direction only —
 * {@link java.io.PrintStream} SWALLOWS the broken-pipe {@link IOException} so
 * writes keep looking fine, and the blocking {@code readLine} never returns
 * because the pipe's write end may still be held by a live process (process
 * lifetime and pipe-end ownership are different things). And when the pwsh
 * launcher itself is killed, Windows does not cascade the kill to the JVM.
 * The result: the host shows "Connection closed" while the JVM lingers.</p>
 *
 * <p>Three liveness signals end the process FAST (nonzero, so the host
 * respawns instead of keeping a zombie):</p>
 * <ol>
 *   <li>broken stdout — {@link PrintStream#checkError()} after every write
 *       (the {@code flush()} is load-bearing: checkError only latches at
 *       write/flush);</li>
 *   <li>a read failure on stdin (the host's end died mid-stream);</li>
 *   <li>orphan detection — the parent launcher died ({@link ParentLiveness},
 *       best-effort; see its javadoc).</li>
 * </ol>
 * Clean stdin EOF is the host closing on purpose and exits {@code 0}.
 *
 * <p><b>Design (rubberduck F-5):</b> the line READER runs on a daemon thread
 * and the control loop on the caller's thread — closing {@code System.in}
 * from a watcher does NOT reliably unblock a pending read on Windows, while a
 * control loop that simply returns can safely abandon a blocked reader (the
 * JVM exit kills it).</p>
 */
public final class StdioServer {

    /** Clean end: stdin EOF — the client closed the connection on purpose. */
    public static final int EXIT_OK = 0;
    /** Abnormal end: the pipe died (read failure / broken stdout). */
    public static final int EXIT_PIPE_DIED = 2;
    /** Abnormal end: the parent launcher died and orphaned this JVM. */
    public static final int EXIT_ORPHANED = 3;

    private static final long POLL_MILLIS = 1_000;
    private static final Object EOF = new Object();
    private static final Object READ_ERROR = new Object();

    private StdioServer() {
    }

    /**
     * Runs the line loop until stdin closes or liveness fails.
     *
     * @param parentAlive liveness of the launcher chain; {@code () -> true}
     *                    when there is nothing to watch
     * @return the exit code ({@link #EXIT_OK} on clean EOF); a non-OK code
     *         means the caller should exit with it (nonzero = abnormal end)
     */
    public static int serve(BufferedReader in, PrintStream out, McpDispatcher dispatcher,
            BooleanSupplier parentAlive) {
        BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> readLines(in, lines), "mcp-stdio-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            while (true) {
                if (!parentAlive.getAsBoolean()) {
                    return EXIT_ORPHANED;
                }
                Object next;
                try {
                    next = lines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return EXIT_PIPE_DIED;
                }
                if (next == null) {
                    continue; // quiet poll — re-check liveness
                }
                if (next == EOF) {
                    return EXIT_OK;
                }
                if (next == READ_ERROR) {
                    return parentAlive.getAsBoolean() ? EXIT_PIPE_DIED : EXIT_ORPHANED;
                }
                String line = (String) next;
                if (line.isBlank()) {
                    continue;
                }
                String response = dispatcher.handle(line);
                if (response != null) {
                    out.println(response);
                    out.flush();
                    if (out.checkError()) {
                        // PrintStream swallows write IOExceptions - the latched
                        // error flag is the ONLY way to see a dead host (B-012)
                        return EXIT_PIPE_DIED;
                    }
                }
            }
        } finally {
            reader.interrupt();
        }
    }

    private static void readLines(BufferedReader in, BlockingQueue<Object> lines) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                lines.put(line);
            }
            lines.put(EOF);
        } catch (IOException e) {
            lines.offer(READ_ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lines.offer(READ_ERROR);
        }
    }

    /**
     * Best-effort orphan detection (B-012): watches THIS JVM's parent (the
     * launcher script) and reports dead once it dies.
     *
     * <p>Failure modes handled (rubberduck F-5): Windows PID reuse is
     * neutralized by snapshotting the parent's {@code startInstant} at
     * construction and treating a changed instant as death (the handle itself
     * is snapshotted once — never re-resolved by PID). An empty parent at
     * construction (detached/adopted JVM, service/CI runners) DISABLES the
     * watch: the server keeps running, a healthy server never kills itself
     * for lack of a parent. A shell that exits while the MCP host keeps the
     * pipes open still ends this process deliberately — parent-death means
     * "abandoned" in every launcher chain we ship; the semantics are
     * best-effort and the exit code tells the host to respawn.</p>
     */
    public static final class ParentLiveness implements BooleanSupplier {

        private final Object lock = new Object();
        private final boolean watching;
        private boolean alive = true;

        public ParentLiveness() {
            Optional<ProcessHandle> parent = ProcessHandle.current().parent();
            watching = parent.isPresent();
            if (!watching) {
                return; // nothing to watch - never reports dead
            }
            ProcessHandle handle = parent.get();
            Optional<java.time.Instant> startInstant = handle.info().startInstant();
            Thread watcher = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(POLL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    boolean reused = startInstant.isPresent()
                            && !handle.info().startInstant().equals(startInstant);
                    if (!handle.isAlive() || reused) {
                        synchronized (lock) {
                            alive = false;
                        }
                        return;
                    }
                }
            }, "mcp-stdio-parent-watch");
            watcher.setDaemon(true);
            watcher.start();
        }

        @Override
        public boolean getAsBoolean() {
            if (!watching) {
                return true;
            }
            synchronized (lock) {
                return alive;
            }
        }
    }
}
