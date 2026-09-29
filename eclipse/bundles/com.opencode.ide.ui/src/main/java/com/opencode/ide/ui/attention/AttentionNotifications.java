package com.opencode.ide.ui.attention;

import org.eclipse.jface.notifications.NotificationPopup;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Display;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.WorkerPools;
import com.opencode.ide.core.OpencodeConnection;

/**
 * U-047 attention parity: desktop notifications (plus an optional beep) for
 * the moments an agent needs the human or the human's wait ends — permission
 * asks, questions, session errors, session completions.
 *
 * <p><b>Event consumer others can feed:</b> {@link #notify(AttentionEvent)}
 * is a plain static entry point; any caller (now or from other UI lanes) can
 * raise a notification without knowing anything about popups or preferences.
 * The opencode auto-wiring is {@link #install()}: it registers one
 * {@link OpencodeEventListener} on the primary connection's global
 * {@code /event} SSE stream (the public
 * {@link OpencodeConnection#addEventListener} seam — no existing class was
 * edited) and forwards stream events through {@link AttentionClassifier}.
 * {@link AttentionStartup} calls it at workbench
 * startup via the {@code org.eclipse.ui.startup} extension; the one missing
 * lifecycle hook is a {@link #uninstall()} call from {@code UiActivator#stop}
 * (noted for central wiring — see class use of
 * {@code AgentToolsConsole.uninstall()} for the precedent).</p>
 *
 * <p><b>Rendering:</b> {@code org.eclipse.jface.notifications.NotificationPopup}
 * — verified present in the 2026-06 target platform (bundle
 * {@code org.eclipse.jface.notifications} 0.8.100; the old
 * {@code org.eclipse.ui.notifications}/{@code NotificationPopupBuilder} API
 * no longer exists). Sound is SWT's own {@link Display#beep()} (no AWT
 * toolkit initialization on the SWT thread). Both are gated by
 * {@link AttentionPreferences}: {@code attention.enabled} (default false,
 * TUI parity — everything is silent until opted in) and
 * {@code attention.sound} (default true when enabled).</p>
 *
 * <p><b>Coverage note:</b> events flow from the primary connection's stream
 * only — remote {@code ConnectionsManager} streams are liveness-only by
 * design. {@link #install()} also warm-starts that stream (once, best
 * effort, only when notifications are enabled) so attention works with no
 * OpenCode view open; when the preference is flipped on later mid-session,
 * the stream starts with the next view/chat connection or a restart.</p>
 */
public final class AttentionNotifications {

    /** How long the popup stays on screen before auto-closing (ms). */
    private static final long POPUP_CLOSE_DELAY_MILLIS = 6000L;

    /** Popup text cap — long tool output must not blow up the popup. */
    private static final int MAX_TEXT_LENGTH = 400;

    private static volatile OpencodeEventListener listener;

    private AttentionNotifications() {
    }

    /**
     * Surfaces one attention event: popup on the UI thread plus an optional
     * beep. Safe from any thread (the SSE reader thread is the usual
     * caller); silently does nothing when notifications are disabled, there
     * is no display, or rendering fails — an attention popup must never take
     * down the caller.
     *
     * @param event what happened; {@code null} is ignored
     */
    public static void notify(AttentionEvent event) {
        AttentionPreferences prefs = preferencesSafely();
        if (prefs == null || !prefs.shouldRender(event)) {
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        boolean sound = prefs.shouldPlaySound();
        display.asyncExec(() -> render(display, event, sound));
    }

    /**
     * Registers the global event listener (idempotent). Called once at
     * workbench startup by {@link AttentionStartup}; cheap when nothing is
     * enabled — the listener always stays registered so flipping the
     * preference on applies to the very next event without a restart.
     */
    public static synchronized void install() {
        if (listener != null) {
            return;
        }
        AttentionClassifier fresh = new AttentionClassifier();
        OpencodeEventListener registration = event -> {
            AttentionEvent attention = fresh.apply(event);
            if (attention != null) {
                notify(attention);
            }
        };
        OpencodeConnection.getInstance().addEventListener(registration);
        listener = registration;
        warmUpEventStreamOnce();
    }

    /**
     * Unregisters the global event listener (idempotent). Pairs with
     * {@link #install()}; the intended call site is bundle stop (see the
     * class javadoc's wiring note).
     */
    public static synchronized void uninstall() {
        OpencodeEventListener registration = listener;
        if (registration != null) {
            OpencodeConnection.getInstance().removeEventListener(registration);
        }
        listener = null;
    }

    /** @return whether {@link #install()} currently has a listener registered. */
    public static synchronized boolean isInstalled() {
        return listener != null;
    }

    // ---------- internals ----------

    private static void render(Display display, AttentionEvent event, boolean sound) {
        try {
            NotificationPopup.forDisplay(display)
                    .title(event.kind().title(), true)
                    .text(abbreviate(event.message(), MAX_TEXT_LENGTH))
                    .delay(POPUP_CLOSE_DELAY_MILLIS)
                    .fadeIn(true)
                    .open();
            if (sound) {
                display.beep();   // SWT-native beep: no AWT on the SWT thread
            }
        } catch (RuntimeException | SWTError e) {
            ClientLog.warning("attention popup failed: " + e.getMessage());
        }
    }

    /**
     * Best effort one-time stream warm-up: {@code getClient()} spins the
     * primary connection's SSE stream (attach/spawn as configured) so
     * attention works with no OpenCode view open. Only runs for opted-in
     * users — the default-off preference keeps idle workbenches connection-free.
     */
    private static void warmUpEventStreamOnce() {
        AttentionPreferences prefs = preferencesSafely();
        if (prefs == null || !prefs.isEnabled()) {
            return;
        }
        WorkerPools.submit("opencode-attention-stream", () -> {
            try {
                OpencodeConnection.getInstance().getClient();
            } catch (Exception e) {
                ClientLog.info("attention: event stream not up yet (" + e.getMessage() + ")");
            }
        });
    }

    /**
     * @return the plugin preference view, or {@code null} when the platform
     *         preference service is unavailable (treated as disabled)
     */
    private static AttentionPreferences preferencesSafely() {
        try {
            return AttentionPreferences.forPlugin();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
