package com.opencode.ide.mcp.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;

import com.opencode.ide.tools.McpDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The MCP Streamable HTTP endpoint: single path {@code /mcp}, POST only,
 * JSON responses (no SSE), loopback only, no sessions. Mirrors the embedded
 * {@code com.sun.net.httpserver} approach of the chat bundle's ChatWebServer,
 * but with a thread pool so a long cmake build cannot block the endpoint.
 *
 * <p>Authenticated with a per-start random token (G-003): the endpoint
 * drives cmake/ctest/gdb with absolute paths, and loopback binding alone
 * still lets any local process call it. The token is handed out exactly
 * once through {@link #endpointUrl()} ({@code ?token=...}) — what the
 * opencode registration passes to the legitimate caller; every other
 * request gets 401. A {@code Bearer} header with the same secret is
 * accepted as an alternative.</p>
 */
public final class McpHttpServer {

    private final HttpServer server;
    private final ExecutorService executor;
    private final McpDispatcher dispatcher;
    private final String token;

    private McpHttpServer(HttpServer server, ExecutorService executor, McpDispatcher dispatcher,
            String token) {
        this.server = server;
        this.executor = executor;
        this.dispatcher = dispatcher;
        this.token = token;
    }

    public static McpHttpServer start(McpDispatcher dispatcher) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = com.opencode.ide.client.WorkerPools.executor("mcp-http");
        server.setExecutor(executor);
        McpHttpServer mcp = new McpHttpServer(server, executor, dispatcher, newToken());
        server.createContext("/mcp", mcp::handle);
        server.start();
        return mcp;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** The endpoint URL including the auth token - the one form registration may hand out. */
    public String endpointUrl() {
        return "http://127.0.0.1:" + port() + "/mcp?token=" + token;
    }

    /** The per-start shared secret (for the DS component to publish via {@link McpState}). */
    public String token() {
        return token;
    }

    /** One shared generator - seeding a SecureRandom per token is costly and a lint/bug pattern. */
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();

    private static String newToken() {
        byte[] raw = new byte[24];
        TOKEN_RANDOM.nextBytes(raw);
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respond(exchange, 405, "method not allowed: POST only".getBytes(StandardCharsets.UTF_8),
                        "text/plain; charset=utf-8");
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401,
                        ("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,"
                                + "\"message\":\"unauthorized: missing or invalid token\"}}")
                                .getBytes(StandardCharsets.UTF_8),
                        "application/json");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = dispatcher.handle(body);
            if (response == null) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            respond(exchange, 200, response.getBytes(StandardCharsets.UTF_8), "application/json");
        } catch (RuntimeException e) {
            String error = "{\"jsonrpc\":\"2.0\",\"id\":null,"
                    + "\"error\":{\"code\":-32603,\"message\":\"internal error\"}}";
            try {
                respond(exchange, 200, error.getBytes(StandardCharsets.UTF_8), "application/json");
            } catch (IOException ignored) {
                // headers may already be sent; nothing more we can do
            }
        }
    }

    /** Token gate: accepts {@code ?token=<secret>} (the registered URL) or {@code Bearer <secret>}. */
    private boolean authorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("token=") && tokenMatches(pair.substring("token=".length()))) {
                    return true;
                }
            }
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return tokenMatches(header.substring("Bearer ".length()));
        }
        return false;
    }

    /** Constant-time secret comparison - no timing oracle on the token. */
    private boolean tokenMatches(String candidate) {
        if (candidate == null || candidate.length() != token.length()) {
            return false;
        }
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String contentType)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length == 0) {
            exchange.close();
            return;
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
