package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.opencode.ide.client.model.IntegrationInfo;
import com.opencode.ide.client.model.ProviderAuth;
import com.opencode.ide.ui.model.ConnectFlows.Attempt;
import com.opencode.ide.ui.model.ConnectFlows.AttemptKind;
import com.opencode.ide.ui.model.ConnectFlows.AttemptState;
import com.opencode.ide.ui.model.ConnectFlows.Flow;
import com.opencode.ide.ui.model.ConnectFlows.MethodRow;

/**
 * Unit tests for {@link ConnectFlows}, the SWT-free connect-flow model
 * behind the Integrations dialog (U-048): per-method action enablement from
 * the typed catalog shape, the label join, the attempt state machine across
 * lenient status maps, the abort path and key masking. No SWT, no HTTP.
 */
public class ConnectFlowsTest {

    private static IntegrationInfo integration(String id, String name, int connections,
            IntegrationInfo.IntegrationMethod... methods) {
        // varargs may carry nulls (the skip-fixtures pass them deliberately)
        List<IntegrationInfo.IntegrationMethod> usable = methods == null ? List.of()
                : Arrays.stream(methods).filter(java.util.Objects::nonNull).toList();
        return new IntegrationInfo(id, name, usable, connections);
    }

    private static IntegrationInfo.IntegrationMethod method(String type, String... names) {
        return new IntegrationInfo.IntegrationMethod(type, List.of(names));
    }

    // ---------- per-method action enablement (from the catalog shape) ----------

    @Test
    public void everyMethodBecomesARowWithItsFlow() {
        List<MethodRow> rows = ConnectFlows.methodRows(List.of(
                integration("anthropic", "Anthropic", 0,
                        method("key"),
                        method("env", "ANTHROPIC_API_KEY"),
                        method("oauth")),
                integration("acme", "Acme", 2, method("command", "cli"))));

        assertEquals(4, rows.size());
        // integrations sorted case-insensitively (acme first), methods in catalog order
        assertEquals("acme", rows.get(0).integrationId());
        assertEquals(Flow.COMMAND, rows.get(0).flow());
        assertTrue(rows.get(0).connectable());
        assertEquals(2, rows.get(0).connections());
        assertEquals("anthropic", rows.get(1).integrationId());
        assertEquals(Flow.KEY, rows.get(1).flow());
        assertTrue(rows.get(1).connectable());
        assertEquals(Flow.NONE, rows.get(2).flow());
        assertFalse("env methods have no connect route on the wire", rows.get(2).connectable());
        assertEquals(Flow.OAUTH, rows.get(3).flow());
        assertTrue(rows.get(3).connectable());
    }

    @Test
    public void unusableIntegrationsAndMethodsAreSkipped() {
        List<MethodRow> rows = ConnectFlows.methodRows(Arrays.asList(
                null,
                integration(null, "No id", 0, method("key")),
                integration("  ", "Blank id", 0, method("key")),
                integration("x", "X", 0, (IntegrationInfo.IntegrationMethod) null),
                integration("empty", "Empty", 0)));

        assertTrue(rows.isEmpty());
    }

    @Test
    public void unknownMethodTypesAreNotConnectable() {
        List<MethodRow> rows = ConnectFlows.methodRows(List.of(integration("x", "X", 0, method("banana"))));

        assertEquals(Flow.NONE, rows.get(0).flow());
        assertFalse(rows.get(0).connectable());
    }

    @Test
    public void methodIdComesFromTheFirstNameOnly() {
        assertEquals("cli", ConnectFlows.methodIdOf(method("command", "cli", "legacy")));
        assertEquals("next", ConnectFlows.methodIdOf(method("command", "  ", "next")));
        assertNull(ConnectFlows.methodIdOf(method("oauth")));
        assertNull(ConnectFlows.methodIdOf(null));
    }

    @Test
    public void methodRowsCarryTheMethodIdOnlyWhereAStartNeedsIt() {
        List<MethodRow> rows = ConnectFlows.methodRows(List.of(integration("acme", "Acme", 0,
                method("command", "cli"), method("oauth"), method("key", "ACME_KEY"))));

        assertEquals("cli", rows.get(0).methodId());
        assertNull("an oauth method without names has no derivable id (the dialog asks)",
                rows.get(1).methodId());
        assertNull("the key flow posts no method id at all", rows.get(2).methodId());
    }

    @Test
    public void methodTextPrefersTheWireLabelAndFallsBackToTheBadge() {
        MethodRow envRow = ConnectFlows.methodRows(List.of(
                integration("a", "A", 0, method("env", "A_KEY")))).get(0);
        MethodRow unlabeled = ConnectFlows.methodRows(List.of(
                integration("b", null, 0, method("key")))).get(0);

        assertEquals("env: A_KEY", envRow.methodText());
        assertEquals("API key", envRow.withLabel("API key").methodText());
        assertEquals("key: (any)", unlabeled.methodText());
        assertEquals("a (A)", envRow.integrationText());
        assertEquals("b", unlabeled.integrationText());
    }

    // ---------- label join ----------

    @Test
    public void labelsAttachPositionallyPerProviderAndType() {
        List<MethodRow> rows = ConnectFlows.methodRows(List.of(integration("openai", "OpenAI", 0,
                method("key"), method("env", "OPENAI_API_KEY"), method("oauth"), method("oauth"))));

        List<MethodRow> labeled = ConnectFlows.attachLabels(rows, List.of(
                new ProviderAuth("openai", "key", "API key"),
                new ProviderAuth("openai", "oauth", "ChatGPT Pro/Plus (browser)"),
                new ProviderAuth("openai", "oauth", "ChatGPT Pro/Plus (headless)"),
                new ProviderAuth("other", "key", "unrelated")));

        assertEquals("API key", labeled.get(0).label());
        assertNull("env methods carry no label on the wire", labeled.get(1).label());
        assertEquals("ChatGPT Pro/Plus (browser)", labeled.get(2).label());
        assertEquals("ChatGPT Pro/Plus (headless)", labeled.get(3).label());
        assertEquals("ChatGPT Pro/Plus (browser)", labeled.get(2).methodText());
    }

    @Test
    public void labelJoinLeavesRowsAloneWithoutAuths() {
        List<MethodRow> rows = ConnectFlows.methodRows(List.of(integration("a", "A", 0, method("key"))));

        assertEquals(rows, ConnectFlows.attachLabels(rows, null));
        assertEquals(rows, ConnectFlows.attachLabels(rows, List.of()));
        assertTrue(ConnectFlows.attachLabels(null, null).isEmpty());
    }

    // ---------- attempt state machine ----------

    @Test
    public void startedAttemptReadsTheHandleAndStaysPending() {
        Attempt attempt = ConnectFlows.started(AttemptKind.COMMAND, "anthropic",
                Map.of("attemptID", "att_1", "status", "pending", "command", "opencode auth login"));

        assertTrue(attempt.pending());
        assertEquals(AttemptState.PENDING, attempt.state());
        assertEquals("att_1", attempt.attemptId());
        assertTrue(attempt.detail().contains("status: pending"));
        assertTrue(attempt.detail().contains("opencode auth login"));
    }

    @Test
    public void anAnswerWithoutAnAttemptIdFailsHonestly() {
        Attempt attempt = ConnectFlows.started(AttemptKind.OAUTH, "anthropic", Map.of("status", "pending"));

        assertFalse("an unpollable answer must not read as connecting", attempt.pending());
        assertNull(attempt.attemptId());
        assertTrue(attempt.detail().contains("no attempt id"));
        assertTrue(ConnectFlows.outcomeNotice(attempt).startsWith("connect failed"));
    }

    @Test
    public void pollsTransitionPendingToEveryTerminalState() {
        Attempt pending = ConnectFlows.started(AttemptKind.OAUTH, "a", Map.of("attemptID", "att"));

        assertEquals(AttemptState.COMPLETE,
                pending.on(Map.of("status", "complete", "connectionID", "conn_1")).state());
        assertEquals(AttemptState.FAILED,
                pending.on(Map.of("status", "failed", "message", "nope")).state());
        assertEquals(AttemptState.EXPIRED, pending.on(Map.of("status", "expired")).state());
        assertEquals(AttemptState.ABORTED, pending.on(Map.of("status", "cancelled")).state());
    }

    @Test
    public void terminalStatusSpellingsAreMatchedLeniently() {
        Attempt pending = ConnectFlows.started(AttemptKind.COMMAND, "a", Map.of("attemptID", "att"));

        for (String status : new String[] {"completed", "done", "succeeded"}) {
            assertEquals(status, AttemptState.COMPLETE, pending.on(Map.of("status", status)).state());
        }
        assertEquals(AttemptState.FAILED, pending.on(Map.of("status", "error")).state());
        assertEquals(AttemptState.ABORTED, pending.on(Map.of("status", "aborted")).state());
        assertEquals(AttemptState.ABORTED, pending.on(Map.of("status", "canceled")).state());
    }

    @Test
    public void unknownOrMissingStatusesKeepPolling() {
        Attempt pending = ConnectFlows.started(AttemptKind.COMMAND, "a", Map.of("attemptID", "att"));

        assertTrue(pending.on(Map.of("status", "weird")).pending());
        assertTrue(pending.on(Map.of()).pending());
        assertTrue(pending.on(null).pending());
        assertTrue(pending.on(Map.of("status", "PENDING")).pending());
    }

    @Test
    public void settledAttemptsIgnoreLaterStatuses() {
        Attempt complete = ConnectFlows.started(AttemptKind.OAUTH, "a", Map.of("attemptID", "att"))
                .on(Map.of("status", "complete"));

        assertEquals("a late poll must not resurrect a settled attempt",
                complete, complete.on(Map.of("status", "failed")));
    }

    @Test
    public void describeRendersWhateverFieldsExistAndNothingElse() {
        String detail = ConnectFlows.describe(Map.of(
                "attemptID", "att_9",
                "status", "complete",
                "exitCode", 0,
                "url", "https://auth.example",
                "code", "abc",
                "extra", "visible",
                "time", Map.of("created", 1L, "expires", 2L)));

        assertTrue(detail.contains("status: complete"));
        assertTrue(detail.contains("exitCode: 0"));
        assertTrue(detail.contains("url: https://auth.example"));
        assertTrue(detail.contains("code: abc"));
        assertTrue("unknown scalar fields render too", detail.contains("extra: visible"));
        assertFalse("the poll handle is not an outcome", detail.contains("att_9"));
        assertFalse("nested objects carry no line", detail.contains("created"));
        assertEquals("", ConnectFlows.describe(null));
        assertEquals("", ConnectFlows.describe(Map.of()));
    }

    // ---------- abort path ----------

    @Test
    public void abortSettlesTheAttemptAsCancelled() {
        Attempt pending = ConnectFlows.started(AttemptKind.COMMAND, "a", Map.of("attemptID", "att"));
        Attempt cancelled = pending.settled(AttemptState.ABORTED, "cancelled");

        assertFalse(cancelled.pending());
        assertEquals("connect cancelled - cancelled", ConnectFlows.outcomeNotice(cancelled));
        assertEquals(AttemptKind.COMMAND, cancelled.kind());
        assertEquals("a", cancelled.integrationId());
        assertEquals("att", cancelled.attemptId());
    }

    @Test
    public void outcomeNoticesNameTheVerdictAndTheServerAnswer() {
        Attempt failed = ConnectFlows.started(AttemptKind.OAUTH, "a", Map.of("attemptID", "att"))
                .on(Map.of("status", "failed", "message", "denied"));
        Attempt expired = ConnectFlows.started(AttemptKind.COMMAND, "a", Map.of("attemptID", "att"))
                .on(Map.of("status", "expired"));

        assertEquals("connect failed - status: failed  \u2022  message: denied",
                ConnectFlows.outcomeNotice(failed));
        assertTrue(ConnectFlows.outcomeNotice(expired).startsWith("connect attempt expired"));
    }

    // ---------- key masking ----------

    @Test
    public void keyNoticeRendersTheConnectionNeverTheKey() {
        String notice = ConnectFlows.keyNotice(Map.of(
                "id", "conn_key_1", "type", "key", "label", "Work"));

        assertEquals("connected: conn_key_1 (key) - Work", notice);
        assertFalse(notice.contains("sk-secret"));

        assertEquals("connected: conn_2 (key)",
                ConnectFlows.keyNotice(Map.of("id", "conn_2", "type", "key")));
        assertEquals("connected (the server accepted the key)", ConnectFlows.keyNotice(Map.of()));
        assertEquals("connected (the server accepted the key)", ConnectFlows.keyNotice(null));
    }

    @Test
    public void maskEchoesConstantBulletsOnly() {
        assertEquals("\u2022\u2022\u2022", ConnectFlows.mask("sk-ant-very-long-secret"));
        assertEquals(ConnectFlows.mask("a"), ConnectFlows.mask("a different secret"));
        assertFalse(ConnectFlows.mask("sk-secret").contains("sk-secret"));
    }
}
