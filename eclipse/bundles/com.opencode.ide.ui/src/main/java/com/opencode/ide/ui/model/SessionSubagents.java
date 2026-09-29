package com.opencode.ide.ui.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Rows for the subagent children of ONE session (U-041): every session whose
 * {@code parentID} is the observed session, with its own title/agent, live
 * status and token actuals — the v2 subagent shape
 * ({@code SessionParsingTest.childSessionKeepsParentID}). The Session Details
 * view renders them as a nested section under the parent, each row opening
 * its own transcript (the {@code SessionViewIds} secondary-id seam).
 *
 * <p>SWT-free so the nesting is unit-testable; the view only renders. The
 * same rows serve chat sessions and fleet worker sessions — both are ordinary
 * sessions of their server, so the join needs nothing session-kind-specific.
 * Precedent: {@code SessionObserver#childOf} (client bundle) builds the same
 * child projection for fleet observability.</p>
 */
public final class SessionSubagents {

    private SessionSubagents() {
    }

    /**
     * @param parentSessionId the observed session (the view's session)
     * @param sessions        the session list (may be {@code null}); only
     *                        children of the observed session are kept
     * @param statuses        the active-session map ({@code GET /session/active}
     *                        lists BUSY sessions only, so an absent id is
     *                        idle); may be {@code null}
     * @return rows sorted by session id (stable order, SessionObserver's rule)
     */
    public static List<Row> rows(String parentSessionId, List<Session> sessions,
            Map<String, SessionStatus> statuses) {
        if (parentSessionId == null || parentSessionId.isBlank() || sessions == null) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (Session session : sessions) {
            if (session == null || session.id() == null
                    || !parentSessionId.equals(session.parentID())) {
                continue;
            }
            SessionStatus status = statuses == null ? null : statuses.get(session.id());
            boolean statusKnown = status != null && status.type() != null;
            rows.add(new Row(session.id(), name(session), session.agent(),
                    statusKnown ? status.type() : "idle",
                    session.cost(), tokensOf(session.tokens()), statusKnown));
        }
        rows.sort(Comparator.comparing(Row::sessionId));
        return List.copyOf(rows);
    }

    /**
     * Title, falling back to the agent name and the id (v2 titles are lazy) —
     * {@code BackgroundModel#name}'s rule, kept identical so the two surfaces
     * never label one session differently.
     */
    private static String name(Session session) {
        if (session.title() != null && !session.title().isBlank()) {
            return session.title();
        }
        if (session.agent() != null && !session.agent().isBlank()) {
            return "<" + session.agent() + ">";
        }
        return session.id();
    }

    /** Sum of input/output/reasoning (the session row's own actuals), or {@code null}. */
    private static Long tokensOf(Session.Tokens tokens) {
        return tokens == null ? null : Long.valueOf(tokens.input() + tokens.output() + tokens.reasoning());
    }

    /**
     * One subagent child row: identity, live status and the run's actuals.
     * {@code statusKnown} marks whether the status was reported (vs the
     * "idle" default) - the Details column only shows reported statuses.
     */
    public record Row(String sessionId, String title, String agent, String status,
            Double cost, Long tokens, boolean statusKnown) {

        /** Cost for the Details column; {@code ""} when the server reported none. */
        public String costLabel() {
            return cost == null ? "" : String.format(Locale.ROOT, "$%.4f", cost.doubleValue());
        }

        /** Token actuals for the Details column; {@code ""} when the server reported none. */
        public String tokensLabel() {
            return tokens == null ? "" : Long.toString(tokens.longValue());
        }

        /** {@code agent  •  status  •  cost  •  tokens} with blank parts skipped (the Details column and tooltip). */
        public String detailLabel() {
            StringBuilder sb = new StringBuilder();
            for (String part : new String[] { agent, statusKnown ? status : null,
                    costLabel(), tokensLabel() }) {
                if (part != null && !part.isBlank()) {
                    sb.append(sb.length() == 0 ? "" : "  \u2022  ").append(part);
                }
            }
            return sb.toString();
        }
    }
}
