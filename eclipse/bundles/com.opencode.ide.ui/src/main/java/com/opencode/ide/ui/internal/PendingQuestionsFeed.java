package com.opencode.ide.ui.internal;

import com.opencode.ide.client.ClientLog;
import com.opencode.ide.client.OpencodeEventListener;
import com.opencode.ide.client.WorkerPools;
import com.opencode.ide.client.activity.PermissionEvents;
import com.opencode.ide.client.activity.PermissionRequest;
import com.opencode.ide.client.model.OpencodeEvent;
import com.opencode.ide.core.OpencodeConnection;
import com.opencode.ide.ui.model.PendingQuestions;
import java.util.List;

/**
 * The ONE feed behind the collected agent-questions surfaces (U-096): registers a single {@link
 * OpencodeEventListener} on the primary connection's global {@code /event} SSE stream (the public
 * {@link OpencodeConnection#addEventListener} seam - the {@code AttentionNotifications.install}
 * precedent, no existing class edited) and applies every parsed permission event to the shared
 * {@link PendingQuestions} registry: {@code permission.asked}/{@code permission.replied} via the
 * client bundle's {@link PermissionEvents} parser (reused - no second parser), {@code
 * session.deleted} as the void-cleanup (an ask of a deleted session waits on nobody).
 *
 * <p>The stream is the fast path; {@link #install()} also runs ONE best-effort reconciliation
 * ({@code GET /permission/request}, unscoped) so an ask raised before the feed installed - a
 * subagent question that pended while no surface was open - is visible immediately instead of
 * waiting for the next event. The Background view's poll keeps reconciling afterwards (the list
 * stays a floor; removal rides replied events and session deletion).
 *
 * <p>Install is idempotent and lazy: every consuming view (Server view, Background view, Session
 * Details) calls it from its control creation, so the registry is live whenever a surface can show
 * it; {@link #uninstall()} pairs from {@code UiActivator#stop}. Events flow from the primary
 * connection's stream only (v2's single stream carries every session the server processes,
 * subagents included - the attention classifier's coverage note).
 */
public final class PendingQuestionsFeed {

  private static volatile OpencodeEventListener listener;

  private PendingQuestionsFeed() {}

  /**
   * Registers the global event listener and warms the registry once (idempotent). Cheap when the
   * connection is not up yet: registration is in-memory, the warm pass degrades silently to the
   * next event or a view's poll.
   */
  public static synchronized void install() {
    if (listener != null) {
      return;
    }
    OpencodeEventListener registration = PendingQuestionsFeed::onEvent;
    OpencodeConnection.getInstance().addEventListener(registration);
    listener = registration;
    warmReconcileOnce();
  }

  /** Unregisters the listener (idempotent). Pairs with {@link #install()} at bundle stop. */
  public static synchronized void uninstall() {
    OpencodeEventListener registration = listener;
    if (registration != null) {
      try {
        OpencodeConnection.getInstance().removeEventListener(registration);
      } catch (RuntimeException e) {
        // best effort during bundle stop
      }
    }
    listener = null;
  }

  /**
   * @return whether {@link #install()} currently has a listener registered
   */
  public static synchronized boolean isInstalled() {
    return listener != null;
  }

  /** SSE event in (the reader thread): questions in, clears and voids out. Never throws. */
  private static void onEvent(OpencodeEvent event) {
    if (event == null || event.type() == null) {
      return;
    }
    if ("session.deleted".equals(event.type())) {
      // v2 keeps the flat {sessionID} payload on session.deleted (the fleet bridge's note)
      PendingQuestions.getDefault().dropSession(event.string("sessionID"));
      return;
    }
    PermissionRequest request = PermissionEvents.parse(event);
    if (request != null) {
      PendingQuestions.getDefault().apply(request);
    }
  }

  /**
   * One-shot floor pass after install: asks the stream cannot have delivered yet (raised before
   * install, or during a stream gap) reach the registry from the service's own list. Failures are
   * silent by design - the next event or a view's poll reconciles.
   */
  private static void warmReconcileOnce() {
    WorkerPools.submit(
        "opencode-questions-warmup",
        () -> {
          try {
            List<PermissionRequest> pending =
                OpencodeConnection.getInstance().getClient().listPermissionRequests();
            PendingQuestions.getDefault().reconcile(pending);
          } catch (Exception e) {
            ClientLog.info("questions warm-up skipped (" + e.getMessage() + ")");
          }
        });
  }
}
