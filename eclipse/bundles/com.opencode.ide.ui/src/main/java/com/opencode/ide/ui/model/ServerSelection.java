package com.opencode.ide.ui.model;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.Session;

/**
 * Immutable action target projected from ONE selected tree path. The owning
 * connection is supplied by that path, never looked up by session/agent equality.
 * Keeping the target through confirmation also prevents selection changes from
 * redirecting a destructive operation to a different server.
 */
public record ServerSelection(OpencodeClient client, boolean primary, Session session, Agent agent,
        boolean busy) {

    public static final ServerSelection EMPTY = new ServerSelection(null, false, null, null, false);

    public boolean copySessionId() {
        return session != null && session.id() != null && !session.id().isBlank();
    }

    public boolean deleteSession() {
        return copySessionId() && client != null;
    }

    public boolean abortSession() {
        return deleteSession() && busy;
    }

    /** Transcript and chat views currently connect only to the primary server. */
    public boolean openSession() {
        return deleteSession() && primary;
    }

    public boolean agentDetails() {
        return agent != null;
    }

    public boolean newAgentSession() {
        return agent != null && agent.isPrimary() && agent.name() != null && !agent.name().isBlank()
                && primary && client != null;
    }

    /** Called by the background loader after confirmation; enforces the same menu policy. */
    public void changeSession(boolean delete) throws OpencodeException {
        if (delete ? !deleteSession() : !abortSession()) {
            throw new IllegalStateException("No eligible session selected");
        }
        if (delete) {
            client.deleteSession(session.id());
        } else {
            client.abortSession(session.id());
        }
    }
}
