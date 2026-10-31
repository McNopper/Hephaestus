package com.opencode.ide.chat.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Component test: runs the real {@link ChatWebServer} (as used inside Eclipse) against a filesystem
 * resolver over the actual bundled web assets, and checks that the page and every referenced asset
 * class is reachable with the right MIME type - the exact failure mode that produced the blank chat
 * (file:// extraction 404s) must be impossible here.
 */
public class ChatWebServerComponentTest {

  private static ChatWebServer server;
  private static Path webDir;

  @BeforeClass
  public static void start() throws IOException {
    // components/chat-web/web (the renderer is a standalone component) when
    // run from the Tycho workspace
    Path candidate = Paths.get("../../components/chat-web/web");
    if (!Files.exists(candidate)) {
      // fallback for surefire working dirs that differ
      candidate = Paths.get("../../../components/chat-web/web");
    }
    assertTrue(
        "web assets not found (looked at " + candidate.toAbsolutePath() + ")",
        Files.isDirectory(candidate));
    webDir = candidate;
    server =
        ChatWebServer.start(
            path -> {
              Path file = webDir.resolve(path).normalize();
              if (!file.startsWith(webDir) || !Files.isRegularFile(file)) {
                throw new FileNotFoundException(path);
              }
              return Files.newInputStream(file);
            });
  }

  @AfterClass
  public static void stop() {
    if (server != null) {
      server.stop();
    }
  }

  private static Result get(String path) throws IOException {
    HttpURLConnection conn =
        (HttpURLConnection) URI.create(server.url(path)).toURL().openConnection();
    int status = conn.getResponseCode();
    String mime = conn.getContentType();
    byte[] body = (status == 200) ? conn.getInputStream().readAllBytes() : new byte[0];
    conn.disconnect();
    return new Result(status, mime, body);
  }

  private record Result(int status, String mime, byte[] body) {}

  @Test
  public void chatHtmlServed() throws IOException {
    Result r = get("chat.html");
    assertEquals(200, r.status());
    assertTrue(r.mime(), r.mime().startsWith("text/html"));
    String html = new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue("page must load the app script", html.contains("chat.js"));
    assertTrue("page must load the highlighter", html.contains("hljs/highlight.min.js"));
  }

  @Test
  public void appScriptServedWithTheBridge() throws IOException {
    Result r = get("chat.js");
    assertEquals(200, r.status());
    assertTrue(r.mime(), r.mime().startsWith("text/javascript"));
    String js = new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue("app script must define the bridge", js.contains("window.__appendUser"));
    assertTrue("app script must report readiness", js.contains("page-ready"));
  }

  @Test
  public void allScriptAssetsReachable() throws IOException {
    for (String asset :
        new String[] {
          "chat.js",
          "markdown-it.min.js",
          "mermaid.min.js",
          "katex/katex.min.js",
          "katex/katex.min.css",
          "hljs/highlight.min.js",
          "hljs/cmake.min.js",
          "hljs/github.min.css",
          "hljs/github-dark.min.css"
        }) {
      Result r = get(asset);
      assertEquals("asset " + asset, 200, r.status());
      assertTrue("asset " + asset + " non-empty", r.body().length > 0);
    }
    assertTrue(get("markdown-it.min.js").mime().startsWith("text/javascript"));
    assertTrue(get("katex/katex.min.css").mime().startsWith("text/css"));
    assertTrue(get("hljs/github.min.css").mime().startsWith("text/css"));
  }

  @Test
  public void fontsReachableWithFontMime() throws IOException {
    Result r = get("katex/fonts/KaTeX_Main-Regular.woff2");
    assertEquals(200, r.status());
    assertTrue(r.mime(), r.mime().startsWith("font/"));
    assertTrue(r.body().length > 0);
  }

  @Test
  public void rootServesChatHtml() throws IOException {
    Result root = get("");
    Result named = get("chat.html");
    assertEquals(root.status(), named.status());
    assertNotEquals(0, root.body().length);
  }

  @Test
  public void unknownPathIs404AndTraversalRejected() throws IOException {
    assertEquals(404, get("does-not-exist.js").status());
    assertEquals(400, get("../MANIFEST.MF").status());
    assertEquals(400, get("katex/../nope.js").status()); // any ".." is rejected outright
  }
}
