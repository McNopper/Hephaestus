package com.opencode.ide.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.activity.PermissionRequest;

/**
 * Tests for the reconnect-recovery half of the permission surface (T-004):
 * {@link ChatPermissionRecovery} re-reads the pending asks via {@code GET
 * /permission/request} when a view appears or re-connects (SSE events are
 * not replayed) and surfaces the ones still unanswered - the ask-&gt;answer
 * half is pinned by {@link ChatPermissionsTest}. SWT-free: fake client and
 * recording listener, inline suppliers.
 */
public class ChatPermissionRecoveryTest {

    private FakeClient client;
    private RecordingListener listener;
    private Set<String> answered;
    private String sessionScope;
    private String directory;

    @Before
    public void setUp() {
        client = new FakeClient();
        listener = new RecordingListener();
        answered = new HashSet<>();
        sessionScope = "ses_1";
        directory = "C:/work/repo";
    }

    private ChatPermissionRecovery recovery() {
        return new ChatPermissionRecovery(() -> client, () -> directory, () -> sessionScope,
                () -> answered, listener);
    }

    @Test
    public void recoveryReListsThePendingAsksScopedToTheWorkingDirectory() {
        client.requests = List.of(ask("ses_1", "per_1", PermissionRequest.Status.PENDING));

        assertEquals(1, recovery().recover());

        assertEquals(List.of("C:/work/repo"), client.listedDirectories);
    }

    @Test
    public void pendingUnansweredAsksOfThisSessionAreSurfacedWithTheClient() {
        client.requests = List.of(
                ask("ses_1", "per_1", PermissionRequest.Status.PENDING),
                ask("ses_2", "per_2", PermissionRequest.Status.PENDING),
                ask("ses_1", "per_3", PermissionRequest.Status.ANSWERED));

        assertEquals(1, recovery().recover());

        assertEquals(1, listener.shown.size());
        assertEquals("per_1", listener.shown.get(0).permissionId());
        assertSame("the surfaced ask carries the client that can answer it",
                client, listener.clients.get(0));
    }

    @Test
    public void askAlreadyAnsweredHereIsNotResurfaced() {
        client.requests = List.of(ask("ses_1", "per_1", PermissionRequest.Status.PENDING));
        answered.add("per_1");

        assertEquals(0, recovery().recover());

        assertTrue(listener.shown.isEmpty());
    }

    @Test
    public void withoutSessionScopeEveryPendingAskIsSurfaced() {
        // a fresh view before its first message has no session yet - it must
        // still recover the asks (the banner answers them in any session)
        sessionScope = null;
        client.requests = List.of(
                ask("ses_1", "per_1", PermissionRequest.Status.PENDING),
                ask("ses_2", "per_2", PermissionRequest.Status.PENDING),
                ask("ses_1", "per_3", PermissionRequest.Status.ANSWERED));

        assertEquals(2, recovery().recover());

        assertEquals(List.of("per_1", "per_2"),
                listener.shown.stream().map(PermissionRequest::permissionId).toList());
    }

    @Test
    public void askWithoutAUsableIdIsSkipped() {
        // cannot be answered without an id - and must not break the loop
        client.requests = List.of(
                new PermissionRequest("ses_1", null, "bash", List.of("git push"), null,
                        PermissionRequest.Status.PENDING),
                new PermissionRequest("ses_1", "  ", "edit", List.of("src/A.java"), null,
                        PermissionRequest.Status.PENDING),
                ask("ses_1", "per_1", PermissionRequest.Status.PENDING));

        assertEquals(1, recovery().recover());

        assertEquals(List.of("per_1"),
                listener.shown.stream().map(PermissionRequest::permissionId).toList());
    }

    @Test
    public void unreadableServerDegradesWithoutThrowing() {
        client.listFailure = new OpencodeException("connection refused");

        assertEquals(0, recovery().recover());

        assertTrue("the banner simply stays hidden", listener.shown.isEmpty());
    }

    @Test
    public void noClientDegradesWithoutThrowing() {
        ChatPermissionRecovery notConnected =
                new ChatPermissionRecovery(() -> null, () -> directory, () -> sessionScope,
                        () -> answered, listener);

        assertEquals(0, notConnected.recover());

        assertTrue(listener.shown.isEmpty());
    }

    @Test
    public void throwingListenerIsContainedAndRecoveryContinues() {
        listener.throwOnFirst = true;
        client.requests = List.of(
                ask("ses_1", "per_1", PermissionRequest.Status.PENDING),
                ask("ses_1", "per_2", PermissionRequest.Status.PENDING));

        assertEquals("a broken surface must not hide the remaining asks", 2,
                recovery().recover());
    }

    // ---------- helpers / fakes ----------

    private static PermissionRequest ask(String sessionId, String permissionId,
            PermissionRequest.Status status) {
        return new PermissionRequest(sessionId, permissionId, "bash", List.of("git push"),
                "git push", status);
    }

    private static final class RecordingListener implements ChatPermissionRecovery.Listener {
        final List<PermissionRequest> shown = new ArrayList<>();
        final List<OpencodeClient> clients = new ArrayList<>();
        boolean throwOnFirst;

        @Override
        public void shown(PermissionRequest request, OpencodeClient client) {
            if (throwOnFirst && shown.isEmpty()) {
                throw new IllegalStateException("boom");
            }
            shown.add(request);
            clients.add(client);
        }
    }

    private static final class FakeClient extends ChatFakeClientBase {
        List<PermissionRequest> requests = List.of();
        final List<String> listedDirectories = new ArrayList<>();
        OpencodeException listFailure;

        @Override
        public List<PermissionRequest> listPermissionRequests(String directory)
                throws OpencodeException {
            listedDirectories.add(directory);
            if (listFailure != null) {
                throw listFailure;
            }
            return requests;
        }
    }
}
