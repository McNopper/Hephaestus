package com.opencode.ide.chat.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
    private final BrowserFunction forkAtFunction;
    private final BrowserFunction inboxActionFunction;
    private final BrowserFunction fileQueryFunction;
    private final BrowserFunction filePickFunction;
    private final BrowserFunction formReplyFunction;
    private final BrowserFunction formCancelFunction;
    private boolean pageReady;
    /** Whether reasoning progress is visible; re-applied on page reload (user toggle). */
    private boolean reasoningVisible = true;
    /**
     * Receives fork-at-message requests from the page's per-message Fork
     * buttons ({@code __javaForkAt(mid)}); registered by the owning view.
     */
    private volatile Consumer<String> forkHandler;
    /**
     * Receives queued-prompt management requests from the page's composer
     * queue row ({@code __javaInboxAction(action, messageId)} with action
     * {@code steer|queue|cancel}); registered by the owning view.
     */
    private volatile java.util.function.BiConsumer<String, String> inboxHandler;
    /**
     * Receives file-search requests from the page's {@code @}-file dropdown
     * ({@code __javaFileQuery(query)}, U-012); registered by the owning view.
     */
    private volatile Consumer<String> fileQueryHandler;
    /**
     * Receives pick notifications from the page's {@code @}-file dropdown
     * ({@code __javaFilePick(path)} - a row was clicked); registered by the
     * owning view, which replaces the {@code @} token in its composer.
     */
    private volatile Consumer<String> filePickHandler;
    /**
     * Receives form answers from the page's question-form cards (U-014:
     * {@code __javaFormReply(formId, answersJson)}); registered by the
     * owning view, which POSTs the reply through the controller.
     */
    private volatile java.util.function.BiConsumer<String, Map<String, Object>> formReplyHandler;
    /**
     * Receives cancel requests from the page's question-form cards (U-014:
     * {@code __javaFormCancel(formId)}); registered by the owning view.
     */
    private volatile Consumer<String> formCancelHandler;
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
        // JS -> Java fork bridge: a message's Fork button calls
        // __javaForkAt(messageId) - the view forks the session at that message
        this.forkAtFunction = new BrowserFunction(browser, "__javaForkAt") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String messageId) {
                    Consumer<String> handler = forkHandler;
                    if (handler != null && !messageId.isBlank()) {
                        handler.accept(messageId); // Browser calls run on the UI thread
                    }
                }
                return null;
            }
        };
        // JS -> Java inbox bridge: a queued prompt's Steer now / Deliver next /
        // Cancel button calls __javaInboxAction(action, messageId) - the view
        // steers, schedules or cancels that server-side parked prompt
        this.inboxActionFunction = new BrowserFunction(browser, "__javaInboxAction") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 1 && arguments[0] instanceof String action
                        && arguments[1] instanceof String messageId) {
                    java.util.function.BiConsumer<String, String> handler = inboxHandler;
                    if (handler != null && !messageId.isBlank()) {
                        handler.accept(action, messageId); // Browser calls run on the UI thread
                    }
                }
                return null;
            }
        };
        // JS -> Java file-search bridge (U-012): the @-file dropdown asks for
        // the matches of a query (__javaFileQuery) - the view runs the
        // server-side file search and pushes the list back via
        // __setFileCompletions
        this.fileQueryFunction = new BrowserFunction(browser, "__javaFileQuery") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String query) {
                    Consumer<String> handler = fileQueryHandler;
                    if (handler != null) {
                        handler.accept(query); // Browser calls run on the UI thread
                    }
                }
                return null;
            }
        };
        // JS -> Java file-pick bridge (U-012): a clicked completion row hands
        // the picked path to the view (__javaFilePick), which replaces the @
        // token in its composer input
        this.filePickFunction = new BrowserFunction(browser, "__javaFilePick") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String path) {
                    Consumer<String> handler = filePickHandler;
                    if (handler != null && !path.isBlank()) {
                        handler.accept(path); // Browser calls run on the UI thread
                    }
                }
                return null;
            }
        };
        // JS -> Java form-reply bridge (U-014): a question-form card's Submit
        // button calls __javaFormReply(formId, answersJson) - the answers
        // travel as a JSON string (the card builds the map; the service owns
        // the field schema), parsed leniently here
        this.formReplyFunction = new BrowserFunction(browser, "__javaFormReply") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 1 && arguments[0] instanceof String formId
                        && arguments[1] instanceof String answersJson && !formId.isBlank()) {
                    java.util.function.BiConsumer<String, Map<String, Object>> handler = formReplyHandler;
                    if (handler != null) {
                        handler.accept(formId, ChatScripts.parseFormAnswers(answersJson));
                    }
                }
                return null;
            }
        };
        // JS -> Java form-cancel bridge (U-014): a question-form card's
        // Cancel button calls __javaFormCancel(formId)
        this.formCancelFunction = new BrowserFunction(browser, "__javaFormCancel") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String formId) {
                    Consumer<String> handler = formCancelHandler;
                    if (handler != null && !formId.isBlank()) {
                        handler.accept(formId); // Browser calls run on the UI thread
                    }
                }
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
            forkAtFunction.dispose();
            inboxActionFunction.dispose();
            fileQueryFunction.dispose();
            filePickFunction.dispose();
            formReplyFunction.dispose();
            formCancelFunction.dispose();
            openExternalFunction.dispose();
        } catch (SWTException e) {
            // browser torn down concurrently - nothing left to release
        }
    }

    /**
     * Registers the handler for the page's per-message Fork buttons
     * ({@code __javaForkAt(messageId)}); {@code null} disables forking.
     */
    public void setForkHandler(Consumer<String> handler) {
        this.forkHandler = handler;
    }

    /**
     * Registers the handler for the composer queue row's action buttons
     * ({@code __javaInboxAction(action, messageId)}, action
     * {@code steer|queue|cancel}); {@code null} disables inbox management.
     */
    public void setInboxHandler(java.util.function.BiConsumer<String, String> handler) {
        this.inboxHandler = handler;
    }

    /**
     * Registers the handler for the {@code @}-file dropdown's search requests
     * ({@code __javaFileQuery(query)}, U-012); {@code null} disables the
     * dropdown's data path.
     */
    public void setFileQueryHandler(Consumer<String> handler) {
        this.fileQueryHandler = handler;
    }

    /**
     * Registers the handler for picked {@code @}-file completions
     * ({@code __javaFilePick(path)}, U-012); {@code null} disables
     * click-to-pick.
     */
    public void setFilePickHandler(Consumer<String> handler) {
        this.filePickHandler = handler;
    }

    /**
     * Registers the handler for answered question forms (U-014:
     * {@code __javaFormReply(formId, answersJson)}, the answers already
     * parsed into a map keyed by the form's field keys); {@code null}
     * disables answering.
     */
    public void setFormReplyHandler(java.util.function.BiConsumer<String, Map<String, Object>> handler) {
        this.formReplyHandler = handler;
    }

    /**
     * Registers the handler for cancelled question forms (U-014:
     * {@code __javaFormCancel(formId)}); {@code null} disables cancelling.
     */
    public void setFormCancelHandler(Consumer<String> handler) {
        this.formCancelHandler = handler;
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
    public void setInboxItems(List<ChatSessionController.InboxEntry> items) {
        executeJs(ChatScripts.setInboxItems(items));
    }

    @Override
    public void setForms(List<ChatSessionController.FormCard> forms) {
        executeJs(ChatScripts.setForms(forms));
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
     * backstop) re-applies the state after readiness. Also the
     * {@link ChatSessionController.Renderer} hook the controller pushes
     * through ({@code /thinking} and the re-apply after history renders).
     */
    public void setReasoningVisible(boolean visible) {
        reasoningVisible = visible;
        executeJs(ChatScripts.setReasoningVisible(visible));
    }

    // ---------- @-file autocomplete (U-012) ----------

    /**
     * Opens the page's {@code @}-file dropdown for {@code query} (the text
     * after the {@code @}); the page asks for the matches through
     * {@code __javaFileQuery}, which the registered
     * {@link #setFileQueryHandler(Consumer)} handler serves.
     */
    public void showFileQuery(String query) {
        executeJs(ChatScripts.setFileQuery(query));
    }

    /**
     * Renders the {@code @}-dropdown's two groups (U-047): alias reference
     * roots above the file matches, highlighting merged row {@code selected}
     * (aliases first).
     */
    public void setFileCompletions(List<ChatSessionController.ReferenceProposal> aliases,
            List<String> paths, int selected) {
        executeJs(ChatScripts.setFileCompletions(aliases, paths, selected));
    }

    /** Closes the {@code @}-file dropdown (token gone, Esc, picked, submitted). */
    public void hideFileCompletions() {
        executeJs(ChatScripts.hideFileCompletions());
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
