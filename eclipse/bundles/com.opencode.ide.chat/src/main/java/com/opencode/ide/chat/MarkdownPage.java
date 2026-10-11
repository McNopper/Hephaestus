package com.opencode.ide.chat;

import com.opencode.ide.chat.internal.ChatActivator;
import com.opencode.ide.chat.internal.ChatLog;
import com.opencode.ide.chat.internal.ChatScripts;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Read-only markdown document renderer for embedders outside the chat bundle (e.g. the Board's
 * ticket details): the same web component the chat uses — full markdown pipeline including tables,
 * KaTeX math, mermaid diagrams and copy-buttons — loaded in {@code doc} mode ({@code
 * chat.html?doc=1}: no chat chrome, full content width).
 *
 * <p>Lifecycle: {@link #create(Composite)} returns {@code null} when no SWT Browser (WebView2/Edge)
 * is available — callers must show a plain-text fallback then. {@link #setDocument(String)} queues
 * until the page reported readiness, so it can be called immediately after {@link #load()}. {@link
 * #dispose()} on close; the shared asset server is owned by the chat bundle, not this page.
 */
public final class MarkdownPage {

  private final Browser browser;
  private final BrowserFunction reportFunction;
  private final BrowserFunction openExternalFunction;
  private boolean pageReady;
  private final List<String> pendingJs = new ArrayList<>();

  private MarkdownPage(Browser browser) {
    this.browser = browser;
    this.reportFunction =
        new BrowserFunction(browser, "__javaReport") {
          @Override
          public Object function(Object[] arguments) {
            String message = (arguments.length > 0 && arguments[0] instanceof String s) ? s : "?";
            if ("page-ready".equals(message)) {
              Display.getDefault().asyncExec(MarkdownPage.this::markPageReady);
            }
            return null;
          }
        };
    this.openExternalFunction =
        new BrowserFunction(browser, "__javaOpenExternal") {
          @Override
          public Object function(Object[] arguments) {
            if (arguments.length > 0 && arguments[0] instanceof String url) {
              openExternal(url);
            }
            return null;
          }
        };
    browser.addLocationListener(
        LocationListener.changingAdapter(
            event -> {
              String location = event.location;
              String base = ChatActivator.webUrlBase();
              if (location == null
                  || location.startsWith("about:")
                  || (base != null && location.startsWith(base))) {
                return;
              }
              event.doit = false; // links never navigate the embedded page
              openExternal(location);
            }));
    browser.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
  }

  /**
   * Creates the page inside {@code parent}, or {@code null} if no SWT Browser (WebView2/Edge) is
   * available — the caller should show a plain fallback.
   */
  public static MarkdownPage create(Composite parent) {
    Browser browser;
    try {
      browser = new Browser(parent, SWT.EDGE);
    } catch (SWTError | RuntimeException e) {
      ChatLog.error("SWT Browser (EDGE) creation failed for markdown page", e);
      return null;
    }
    return new MarkdownPage(browser);
  }

  /** Loads the page in doc mode; safe to call {@link #setDocument} right after. */
  public void load() {
    try {
      browser.setUrl(ChatActivator.webUrl("chat.html?doc=1"));
    } catch (Exception e) {
      ChatLog.error("Failed to start chat web server / load doc page", e);
      return;
    }
    Display.getDefault().timerExec(3000, this::probePageReady);
  }

  /** Releases the bridge functions (the browser dies with its parent composite). */
  public void dispose() {
    reportFunction.dispose();
    openExternalFunction.dispose();
  }

  /** Replaces the document with {@code markdown} (rendered by the page pipeline). */
  public void setDocument(String markdown) {
    String doc = markdown == null ? "" : markdown;
    executeJs(ChatScripts.clear());
    executeJs(ChatScripts.setAssistantText("doc", doc, "", "", List.of()));
  }

  // ---------- internals ----------

  private void markPageReady() {
    if (pageReady || browser.isDisposed()) {
      return;
    }
    pageReady = true;
    flushPending();
  }

  private void probePageReady() {
    if (pageReady || browser.isDisposed()) {
      return;
    }
    try {
      Object ok = browser.evaluate("typeof window.__setDocMode === 'function'");
      if (Boolean.TRUE.equals(ok)) {
        pageReady = true;
        flushPending();
      }
    } catch (Exception e) {
      // not ready yet; setDocument calls stay queued
    }
  }

  private void flushPending() {
    synchronized (pendingJs) {
      for (String script : pendingJs) {
        doExecute(script);
      }
      pendingJs.clear();
    }
    doExecute(ChatScripts.setTheme(detectTheme()));
  }

  private void executeJs(String script) {
    if (browser.isDisposed()) {
      return;
    }
    if (!pageReady) {
      synchronized (pendingJs) {
        pendingJs.add(script);
      }
      return;
    }
    doExecute(script);
  }

  private void doExecute(String script) {
    try {
      Object ok = browser.execute(script);
      if (!Boolean.TRUE.equals(ok)) {
        ChatLog.error("doc JS returned false (script error)", null);
      }
    } catch (Exception e) {
      ChatLog.error("doc JS call failed", e);
    }
  }

  private void openExternal(String url) {
    try {
      PlatformUI.getWorkbench()
          .getBrowserSupport()
          .getExternalBrowser()
          .openURL(URI.create(url).toURL());
    } catch (Exception e) {
      if (!Program.launch(url)) {
        ChatLog.error("could not open link externally: " + url, e);
      }
    }
  }

  private static String detectTheme() {
    try {
      RGB rgb = Display.getDefault().getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
      double luminance = (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255.0;
      return luminance < 0.5 ? "dark" : "light";
    } catch (Exception e) {
      return "light";
    }
  }
}
