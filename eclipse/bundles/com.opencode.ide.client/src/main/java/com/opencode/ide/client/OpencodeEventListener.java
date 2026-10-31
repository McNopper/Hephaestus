package com.opencode.ide.client;

import com.opencode.ide.client.model.OpencodeEvent;

/**
 * Receiver for opencode server events. Called on a background (SSE) thread - implementations must
 * dispatch to the UI thread themselves where needed.
 */
@FunctionalInterface
public interface OpencodeEventListener {

  void onEvent(OpencodeEvent event);
}
