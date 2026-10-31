package com.opencode.ide.chat;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.activity.PermissionRequest;

/**
 * Receiver for permission events of chat (non-fleet) opencode sessions: {@code permission.asked}
 * while a session waits for a human decision, {@code permission.replied} once it was answered (by
 * this sink or any other client, e.g. an attached TUI). Registered globally through {@link
 * ChatPermissions#setSink(ChatPermissionSink)}; the chat bundle itself installs none — without a
 * sink, permission events are ignored exactly as before.
 *
 * <p>Called on the SSE background thread: implementations dispatch to the UI thread themselves and
 * must not block. Throwing is contained — an exception never breaks the event loop; the next event
 * is still delivered. With several chat views open the same event reaches the sink once per view:
 * deduplicate by {@link PermissionRequest#permissionId()}. Answer a pending request through the
 * handed client ({@code respondToPermission(sessionId, permissionId, response, remember)}).
 */
public interface ChatPermissionSink {

  /**
   * A request was raised (status {@link PermissionRequest.Status#PENDING}).
   *
   * @param request the parsed ask — ids never {@code null}, other fields may be (shape-tolerant
   *     parse)
   * @param client live client to answer with; never {@code null} — an ask arriving while no client
   *     is available is not delivered
   */
  void asked(PermissionRequest request, OpencodeClient client);

  /**
   * A request was answered — possibly by another client.
   *
   * @param sessionId the session that asked
   * @param requestId the answered permission id ({@code per_...})
   */
  void replied(String sessionId, String requestId);
}
