package com.opencode.ide.ui.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.Session;

public class ServerSelectionTest {

    @Test
    public void emptyAndOfflineSelectionsCannotOperateOnAServer() {
        assertFalse(ServerSelection.EMPTY.openSession());
        assertFalse(ServerSelection.EMPTY.deleteSession());
        assertFalse(ServerSelection.EMPTY.abortSession());
        assertFalse(ServerSelection.EMPTY.copySessionId());
        var offline = new ServerSelection(null, true, session("ses_1"), agent("primary"), true);
        assertTrue(offline.copySessionId());
        assertTrue(offline.agentDetails());
        assertFalse(offline.openSession());
        assertFalse(offline.deleteSession());
        assertFalse(offline.abortSession());
        assertFalse(offline.newAgentSession());
    }

    @Test
    public void primaryAndRemoteMenusRespectConnectionAndBusyState() {
        OpencodeClient client = client(new ArrayList<>());
        var idle = new ServerSelection(client, true, session("ses_1"), null, false);
        assertTrue(idle.openSession());
        assertTrue(idle.deleteSession());
        assertFalse(idle.abortSession());
        var remote = new ServerSelection(client, false, idle.session(), null, true);
        assertFalse(remote.openSession());
        assertTrue(remote.deleteSession());
        assertTrue(remote.abortSession());
        assertFalse(remote.agentDetails());
        assertFalse(new ServerSelection(client, true, session(" "), null, true).deleteSession());
    }

    @Test
    public void onlyNamedUserFacingAgentsOnPrimaryCanStartChats() {
        OpencodeClient client = client(new ArrayList<>());
        for (String mode : List.of("primary", "all", "subagent")) {
            var target = new ServerSelection(client, true, null, agent(mode), false);
            assertTrue(target.agentDetails());
            assertEquals(!"subagent".equals(mode), target.newAgentSession());
            assertFalse(target.deleteSession());
            assertFalse(new ServerSelection(client, false, null, agent(mode), false).newAgentSession());
        }
    }

    @Test
    public void mcpDetailsNeedsAnyReachableServerNotASession() {
        // tier-0 view-only: any selection on a reachable server qualifies —
        // offline remotes (null client) and the empty target do not
        OpencodeClient client = client(new ArrayList<>());
        assertTrue(new ServerSelection(client, true, null, null, false).mcpDetails());
        assertTrue(new ServerSelection(client, false, null, null, false).mcpDetails());
        assertFalse(new ServerSelection(null, true, session("ses_1"), null, true).mcpDetails());
        assertFalse(ServerSelection.EMPTY.mcpDetails());
    }

    @Test
    public void equalSessionIdsRouteMutationsOnlyToCapturedOwner() throws Exception {
        List<String> primaryCalls = new ArrayList<>();
        List<String> remoteCalls = new ArrayList<>();
        var primary = new ServerSelection(client(primaryCalls), true, session("same"), null, true);
        var remote = new ServerSelection(client(remoteCalls), false, session("same"), null, true);
        assertEquals(primary.session(), remote.session());
        remote.changeSession(false);
        remote.changeSession(true);
        assertEquals(List.of("abortSession:same", "deleteSession:same"), remoteCalls);
        assertTrue(primaryCalls.isEmpty());
    }

    @Test
    public void disabledOperationIsRejectedEvenWhenInvokedDirectly() {
        List<String> calls = new ArrayList<>();
        var idle = new ServerSelection(client(calls), true, session("idle"), null, false);
        assertThrows(IllegalStateException.class, () -> idle.changeSession(false));
        assertThrows(IllegalStateException.class, () -> ServerSelection.EMPTY.changeSession(true));
        assertTrue(calls.isEmpty());
    }

    private static OpencodeClient client(List<String> calls) {
        return (OpencodeClient) Proxy.newProxyInstance(OpencodeClient.class.getClassLoader(),
                new Class<?>[] { OpencodeClient.class }, (proxy, method, args) -> {
                    calls.add(method.getName() + ":" + args[0]);
                    return null;
                });
    }

    private static Session session(String id) {
        return new Session(id, null, null, null, null, null, null, null, null, null, null);
    }

    private static Agent agent(String mode) {
        // v2 Agent.Info: (id, name, description, mode, hidden, permissions,
        // steps, color, model, system)
        return new Agent("worker", "worker", null, mode, null, null, null, null, null, null);
    }
}
