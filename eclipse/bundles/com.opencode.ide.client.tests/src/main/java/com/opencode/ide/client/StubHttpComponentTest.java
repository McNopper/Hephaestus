package com.opencode.ide.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.AfterClass;
import org.junit.Before;

/**
 * Shared plumbing for the HTTP component tests (the H5-family classes and
 * the U-046 verb suite): a local stub {@code HttpServer} that records the
 * last request (method, path, query, body), serves either a settable
 * body/status override or the subclass's happy-path {@code respond} body,
 * and treats an empty body as 2xx no-content. The point of the base class
 * is that the stub harness exists ONCE - subclasses contribute only their
 * {@code respond} routing and their assertions (the CPD gate enforces
 * exactly that).
 *
 * <p>Subclasses declare their own {@code @BeforeClass} that calls
 * {@link #startStubServer(Function)} with a static
 * {@code respond(String path)} method reference; {@link #resetStub()} and
 * {@link #stopStub()} are inherited ({@code @Before}/{@code @AfterClass}
 * methods run for subclasses too).</p>
 */
abstract class StubHttpComponentTest {

    static com.sun.net.httpserver.HttpServer server;
    static OpencodeClient client;

    static final AtomicReference<String> lastMethod = new AtomicReference<>();
    static final AtomicReference<String> lastPath = new AtomicReference<>();
    static final AtomicReference<String> lastQuery = new AtomicReference<>();
    static final AtomicReference<String> lastBody = new AtomicReference<>();
    /** Settable body/status the stub serves instead of the built-in happy path. */
    static final AtomicReference<String> bodyOverride = new AtomicReference<>();
    static final AtomicInteger statusOverride = new AtomicInteger(200);
    /**
     * Optional single SSE frame served on {@code GET /api/event} (H5b's event
     * test): when non-null, that path answers {@code text/event-stream} with
     * one {@code data:} frame instead of the JSON happy path.
     */
    static volatile String sseEventJson;

    static void startStubServer(Function<String, String> respond) throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("/api/event".equals(exchange.getRequestURI().getPath()) && sseEventJson != null) {
                byte[] sse = ("data: " + sseEventJson + "\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, sse.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(sse);
                }
                return;
            }
            String body = bodyOverride.get() != null ? bodyOverride.get()
                    : respond.apply(exchange.getRequestURI().getPath());
            if (body.isEmpty()) {
                // 2xx no-content (e.g. POST /api/session/:id/view answers empty)
                exchange.sendResponseHeaders(statusOverride.get(), -1);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusOverride.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        client = new com.opencode.ide.client.internal.HttpOpencodeClient(
                new ConnectionConfig(URI.create("http://127.0.0.1:" + port), null, null));
    }

    @Before
    public void resetStub() {
        bodyOverride.set(null);
        statusOverride.set(200);
        lastMethod.set(null);
        lastPath.set(null);
        lastQuery.set(null);
        lastBody.set(null);
    }

    @AfterClass
    public static void stopStub() {
        server.stop(0);
    }
}
