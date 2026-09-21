package com.opencode.ide.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.Session;

/**
 * Pure telemetry helpers for {@link TaskFleet} (no I/O, never throws):
 * <ul>
 *   <li>{@link #actualsComment(List)} - the per-run cost/token actuals line
 *       recorded as a ticket comment when a fleet job merges; the accumulated
 *       comments calibrate project-manager-estimate-costs.</li>
 * </ul>
 *
 * <p>Every input may be {@code null} or partially populated (the DTOs are
 * nullable-tolerant); absent parts are simply omitted.</p>
 */
public final class FleetTelemetry {

    private FleetTelemetry() {
    }

    /**
     * Builds the actuals comment from the LAST assistant message of the
     * session history.
     *
     * @return the comment line, or {@code null} when the history carries no
     *         assistant message with anything to report
     */
    public static String actualsComment(List<ChatEntry> messages) {
        if (messages == null) {
            return null;
        }
        ChatEntry lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatEntry entry = messages.get(i);
            if (entry != null && entry.info() != null
                    && "assistant".equals(entry.info().role())) {
                lastAssistant = entry;
                break;
            }
        }
        return actualsComment(lastAssistant);
    }

    /**
     * Formats one assistant message's actuals as
     * {@code fleet actuals: cost 0.0123 USD, tokens 6761 (in 6736 / out 3 / reasoning 22), agent build, model provider/model},
     * deterministically omitting every absent part ({@code null} cost,
     * missing tokens, blank agent, unknown model). The token total is
     * {@code input + output + reasoning} (cache read/write ride on the DTO
     * but not on this line).
     *
     * @return the line, or {@code null} when nothing about the run is known
     */
    public static String actualsComment(ChatEntry assistant) {
        if (assistant == null || assistant.info() == null) {
            return null;
        }
        ChatMessageInfo info = assistant.info();
        List<String> parts = new ArrayList<>();
        if (info.cost() != null) {
            parts.add("cost " + String.format(Locale.ROOT, "%.4f", info.cost()) + " USD");
        }
        Session.Tokens tokens = info.tokens();
        if (tokens != null) {
            parts.add("tokens " + (tokens.input() + tokens.output() + tokens.reasoning())
                    + " (in " + tokens.input() + " / out " + tokens.output()
                    + " / reasoning " + tokens.reasoning() + ")");
        }
        if (info.agent() != null && !info.agent().isBlank()) {
            parts.add("agent " + info.agent());
        }
        String provider = info.providerId();
        String model = info.modelId();
        if (provider != null && model != null) {
            parts.add("model " + provider + "/" + model);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "fleet actuals: " + String.join(", ", parts);
    }
}
