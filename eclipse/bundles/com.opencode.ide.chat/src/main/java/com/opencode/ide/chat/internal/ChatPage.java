package com.opencode.ide.chat.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;

/**
 * Reusable facade over the embedded SWT {@link Browser} hosting the chat page
 * (chat.html served by {@link ChatWebServer}): creates the widget, installs
 * the Java/JS bridge functions, queues JS until the page reported readiness,
 * and renders user/assistant/delta updates via {@link ChatScripts}.
 *
 * <p>Links always open in the external browser (navigating the embedded
 * Browser would replace the chat page), and page reports ("page-ready", render
 * confirmations, JS errors) are mirrored into the Eclipse log as
 * {@code [chat-page] …} so the UI side is verifiable from the log.</p>
 */
public final class ChatPage implements ChatSessionController.Renderer {

    private final Browser browser;
    private final BrowserFunction reportFunction;
    private final BrowserFunction openExternalFunction;
    private boolean pageReady;
    /** Whether reasoning progress is visible; re-applied on page reload (user toggle). */
    private boolean reasoningVisible = true;
    private final List<String> pendingJs = new ArrayList<>();

    private ChatPage(Browser browser) {
        this.browser = browser;
        // JS -> Java reporting bridge: chat.html calls __javaReport("...") so UI-side
        // rendering is verifiable from the Eclipse log (used by automated checks)
        this.reportFunction = new BrowserFunction(browser, "__javaReport") {
            @Override
            public Object function(Object[] arguments) {
                String message = (arguments.length > 0 && arguments[0] instanceof String s) ? s : "?";
                if ("page-ready".equals(message)) {
                    Display.getDefault().asyncExec(ChatPage.this::markPageReadyFromPage);
                }
                Platform.getLog(Platform.getBundle(ChatActivator.PLUGIN_ID))
                        .log(new Status(Status.INFO, ChatActivator.PLUGIN_ID, "[chat-page] " + message));
                return null;
            }
        };
        this.openExternalFunction = installLinkHandling();
        browser.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
    }

    /**
     * Creates the page inside {@code parent}, or {@code null} if no SWT Browser
     * (WebView2/Edge) is available - then a fallback label is shown instead.
     */
    public static ChatPage create(Composite parent) {
        Browser browser;
        try {
            browser = new Browser(parent, SWT.EDGE);
        } catch (SWTError | RuntimeException e) {
            Label fallback = new Label(parent, SWT.WRAP);
            fallback.setText("The chat view requires the SWT Browser (WebView2/Edge). "
                    + "WebView2 does not seem to be available: " + e.getMessage());
            fallback.setLayoutData(new GridData(GridData.FILL_BOTH));
            ChatLog.error("SWT Browser (EDGE) creation failed", e);
            return null;
        }
        return new ChatPage(browser);
    }

    /** The browser type in use (e.g. {@code "edge"}), for logging. */
    public String browserType() {
        return browser.getBrowserType();
    }

    /** Loads chat.html and arms the page-ready backstop probes. */
    public void load() {
        try {
            browser.setUrl(ChatActivator.webUrl("chat.html"));
        } catch (Exception e) {
            ChatLog.error("Failed to start chat web server / load page", e);
            return;
        }
        // the page reports readiness itself via __javaReport("page-ready");
        // these are only backstops if that callback is lost
        browser.addProgressListener(new ProgressListener() {
            @Override
            public void changed(ProgressEvent event) {
                // ignore
            }

            @Override
            public void completed(ProgressEvent event) {
                Display.getDefault().asyncExec(ChatPage.this::probePageReady);
            }
        });
        Display.getDefault().timerExec(3000, this::probePageReady);
        Display.getDefault().timerExec(8000, this::probePageReady);
    }

    /** Releases the bridge functions (the browser itself dies with its parent composite). */
    public void dispose() {
        // At workbench shutdown the parent composite - and with it the browser -
        // can already be disposed before the view's dispose runs; disposing the
        // BrowserFunctions would then throw "Widget is disposed" into the log.
        if (browser.isDisposed()) {
            return;
        }
        try {
            reportFunction.dispose();
            openExternalFunction.dispose();
        } catch (SWTException e) {
            // browser torn down concurrently - nothing left to release
        }
    }

    // ---------- rendering (ChatSessionController.Renderer) ----------

    @Override
    public void appendUser(String text) {
        executeJs(ChatScripts.appendUser(text));
    }

    @Override
    public void startAssistant(String messageId) {
        executeJs(ChatScripts.startAssistant(messageId));
    }

    @Override
    public void appendDelta(String messageId, String text) {
        executeJs(ChatScripts.appendDelta(messageId, text));
    }

    @Override
    public void appendReasoningDelta(String messageId, String text) {
        executeJs(ChatScripts.appendReasoning(messageId, text));
    }

    @Override
    public void setAssistantText(String messageId, String text, String reasoning, String meta,
            List<ChatSessionController.ToolLine> tools) {
        executeJs(ChatScripts.setAssistantText(messageId, text, reasoning, meta, tools));
    }

    @Override
    public void stopStream(String messageId) {
        executeJs(ChatScripts.stopStream(messageId));
    }

    @Override
    public void setMessages(List<Map<String, Object>> rows) {
        executeJs(ChatScripts.setMessages(rows));
    }

    @Override
    public void notice(String text) {
        executeJs(ChatScripts.setNotice(text));
    }

    @Override
    public void clear() {
        executeJs(ChatScripts.clear());
    }

    // ---------- link handling ----------

    /**
     * Links always open in the external browser: navigating the embedded Browser
     * would replace the chat page (losing the transcript). The page intercepts
     * clicks and calls {@code __javaOpenExternal}; the {@link LocationListener}
     * is the backstop for navigations that bypass the click handler
     * (middle-click, {@code window.open}, JS redirects).
     */
    private BrowserFunction installLinkHandling() {
        BrowserFunction openExternal = new BrowserFunction(browser, "__javaOpenExternal") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String url) {
                    openExternal(url);
                }
                return null;
            }
        };
        browser.addLocationListener(LocationListener.changingAdapter(event -> {
            String location = event.location;
            if (location == null || location.startsWith("about:") || isOwnPage(location)) {
                return; // our own page and its assets may load
            }
            event.doit = false;
            openExternal(location);
        }));
        return openExternal;
    }

    /** @return true for URLs served by our own {@link ChatWebServer}. */
    private static boolean isOwnPage(String location) {
        String base = ChatActivator.webUrlBase();
        return base != null && location.startsWith(base);
    }

    private void openExternal(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser()
                    .openURL(URI.create(url).toURL());
            ChatLog.info("opened externally: " + url);
        } catch (Exception e) {
            if (!Program.launch(url)) { // fallback: OS default handler
                ChatLog.error("could not open link externally: " + url, e);
            }
        }
    }

    // ---------- page readiness & JS execution ----------

    /** Page announced readiness (authoritative path). */
    private void markPageReadyFromPage() {
        if (pageReady || browser == null || browser.isDisposed()) {
            return;
        }
        pageReady = true;
        flushPending();
        ChatLog.info("chat page ready (page-reported)");
    }

    /** Backstop probe when no page-ready callback arrived. */
    private void probePageReady() {
        if (pageReady || browser == null || browser.isDisposed()) {
            return;
        }
        try {
            Object ok = browser.evaluate("typeof window.__appendUser === 'function'");
            if (Boolean.TRUE.equals(ok)) {
                pageReady = true;
                flushPending();
                ChatLog.info("chat page ready (probe)");
            }
        } catch (Exception e) {
            // not ready yet; a later backstop retries
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
        doExecute(ChatScripts.setReasoningVisible(reasoningVisible));
        notice("Connected. ENTER sends, Shift+ENTER = newline. Markdown, $math$ and mermaid render.");
    }

    /**
     * Toggles whether thinking/reasoning progress is visible in the page (a
     * CSS class hides the blocks - content is untouched, so toggling back is
     * complete without re-rendering). Stored here so a page reload (navigation
     * backstop) re-applies the state after readiness.
     */
    public void setReasoningVisible(boolean visible) {
        reasoningVisible = visible;
        executeJs(ChatScripts.setReasoningVisible(visible));
    }

    /** Queues JS until the page is ready, then executes (never drops a render call). */
    private void executeJs(String script) {
        if (browser == null || browser.isDisposed()) {
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
                ChatLog.error("chat JS returned false (script error): "
                        + script.substring(0, Math.min(120, script.length())), null);
            }
        } catch (Exception e) {
            ChatLog.error("chat JS call failed", e);
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
