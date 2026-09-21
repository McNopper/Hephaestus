package com.opencode.ide.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ChatMessageInfo;
import com.opencode.ide.client.model.ChatPart;
import com.opencode.ide.client.model.Session;

/**
 * Unit tests for the pure {@link FleetTelemetry} helpers: the exact cost
 * actuals comment format (with omissions and null-tolerance).
 */
public class FleetTelemetryTest {

    private static ChatEntry assistant(Double cost, Session.Tokens tokens, String agent,
            String provider, String model) {
        // The final component is v2's time.completed stamp.
        ChatMessageInfo info = new ChatMessageInfo(
                "msg_1", "ses_1", "assistant", null, agent, null, null,
                cost, tokens, provider, model, null, null, 1_700_000_000_000L);
        return new ChatEntry(info, List.of(new ChatPart("text", "done", null, null)));
    }

    private static ChatEntry user(String text) {
        ChatMessageInfo info = new ChatMessageInfo(
                "msg_0", "ses_1", "user", null, null, null, null,
                null, null, null, null, null, null, 1_700_000_000_000L);
        return new ChatEntry(info, List.of(new ChatPart("text", text, null, null)));
    }

    @Test
    public void actualsCommentFormatsAllPartsDeterministically() {
        Session.Tokens tokens = new Session.Tokens(6736, 3, 22, new Session.Cache(100, 5));

        String comment = FleetTelemetry.actualsComment(assistant(0.0123, tokens, "executor",
                "zai-coding-plan", "glm-5.2"));

        assertEquals("fleet actuals: cost 0.0123 USD, tokens 6761 (in 6736 / out 3 / reasoning 22),"
                + " agent executor, model zai-coding-plan/glm-5.2", comment);
    }

    @Test
    public void actualsCommentOmitsAbsentParts() {
        assertEquals("cost and tokens absent -> omitted",
                "fleet actuals: agent executor, model zai-coding-plan/glm-5.2",
                FleetTelemetry.actualsComment(assistant(null, null, "executor",
                        "zai-coding-plan", "glm-5.2")));
        assertEquals("only cost known",
                "fleet actuals: cost 0.0123 USD",
                FleetTelemetry.actualsComment(assistant(0.0123, null, null, null, null)));
        assertEquals("only tokens known",
                "fleet actuals: tokens 3 (in 0 / out 3 / reasoning 0)",
                FleetTelemetry.actualsComment(assistant(null,
                        new Session.Tokens(0, 3, 0, null), null, null, null)));
    }

    @Test
    public void actualsCommentIsNullWhenNothingIsKnownAndNeverThrows() {
        assertNull("no assistant at all", FleetTelemetry.actualsComment((ChatEntry) null));
        assertNull("empty info carries nothing", FleetTelemetry.actualsComment(
                assistant(null, null, null, null, null)));
        assertNull(FleetTelemetry.actualsComment((List<ChatEntry>) null));
        assertNull("user-only history", FleetTelemetry.actualsComment(List.of(user("hi"))));
        assertNull("empty history", FleetTelemetry.actualsComment(List.of()));
        assertNull("null entries are tolerated",
                FleetTelemetry.actualsComment(java.util.Arrays.asList(user("hi"), null)));
    }

    @Test
    public void actualsCommentUsesTheLastAssistantMessage() {
        Session.Tokens early = new Session.Tokens(100, 100, 100, null);
        List<ChatEntry> history = List.of(
                assistant(0.5, early, "executor", "a", "big-model"),
                user("hi again"),
                assistant(null, new Session.Tokens(6736, 3, 22, null), "executor",
                        "zai-coding-plan", "glm-5.2"));

        String comment = FleetTelemetry.actualsComment(history);

        assertEquals("the LAST assistant wins, not the first with cost",
                "fleet actuals: tokens 6761 (in 6736 / out 3 / reasoning 22),"
                        + " agent executor, model zai-coding-plan/glm-5.2", comment);
    }
}
