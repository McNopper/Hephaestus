package com.opencode.ide.chat.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Tiny localhost HTTP server that serves the chat web assets (chat.html, markdown-it, KaTeX incl.
 * fonts, mermaid) from an injectable resolver.
 *
 * <p>Why: {@code FileLocator.toFileURL} on a jar'd bundle extracts single files only - a {@code
 * file://} chat.html then 404s all its relative {@code <script>} and font requests and the page
 * never boots. Serving over plain HTTP from the bundle stream makes all relative URLs work,
 * independent of bundle shape.
 *
 * <p>Bind address is 127.0.0.1 only; paths are guarded against traversal and confined to what the
 * resolver provides.
 */
public final class ChatWebServer {

  /** Provides the content for a bundle-relative path (e.g. {@code "katex/katex.min.css"}). */
  @FunctionalInterface
  public interface ResourceResolver {
    InputStream open(String path) throws IOException;
  }

  private static final Map<String, String> MIME =
      Map.of(
          ".html", "text/html; charset=utf-8",
          ".js", "text/javascript; charset=utf-8",
          ".css", "text/css; charset=utf-8",
          ".png", "image/png",
          ".svg", "image/svg+xml",
          ".woff", "font/woff",
          ".woff2", "font/woff2",
          ".ttf", "font/ttf");

  private final HttpServer server;
  private final int port;

  private ChatWebServer(HttpServer server) {
    this.server = server;
    this.port = server.getAddress().getPort();
  }

  public static ChatWebServer start(ResourceResolver resolver) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> handle(exchange, resolver));
    server.start();
    return new ChatWebServer(server);
  }

  /** Absolute URL for a resource (e.g. {@code url("chat.html")}). */
  public String url(String path) {
    return "http://127.0.0.1:" + port + "/" + path;
  }

  public int port() {
    return port;
  }

  public void stop() {
    server.stop(0);
  }

  private static void handle(HttpExchange exchange, ResourceResolver resolver) throws IOException {
    String method = exchange.getRequestMethod();
    if (!"GET".equals(method) && !"HEAD".equals(method)) {
      respond(exchange, 405, "method not allowed".getBytes(StandardCharsets.UTF_8), "text/plain");
      return;
    }
    String path = exchange.getRequestURI().getPath();
    if (path == null || path.contains("..")) {
      respond(exchange, 400, "bad request".getBytes(StandardCharsets.UTF_8), "text/plain");
      return;
    }
    // getPath() is already percent-decoded, so this is the ONLY decode: a
    // second one would turn %252e%252e%2f into ../ past the guard above.
    String relative = path.startsWith("/") ? path.substring(1) : path;
    if (relative.isEmpty()) {
      relative = "chat.html";
    }

    byte[] body;
    try (InputStream in = resolver.open(relative)) {
      body = in.readAllBytes();
    } catch (IOException notFound) {
      respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8), "text/plain");
      return;
    }
    // Outside the try: a write failure on a committed 200 must not be
    // mistaken for "not found" and answered again.
    respond(exchange, 200, body, mimeFor(relative));
  }

  private static void respond(HttpExchange exchange, int status, byte[] body, String mime)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", mime);
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    if ("HEAD".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    } else {
      exchange.close();
    }
  }

  private static String mimeFor(String path) {
    int dot = path.lastIndexOf('.');
    String mime = (dot >= 0) ? MIME.get(path.substring(dot).toLowerCase()) : null;
    return mime != null ? mime : "application/octet-stream";
  }
}
