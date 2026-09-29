package com.opencode.ide.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * The shared minimal {@link OpencodeClient} fake for the chat permission
 * tests: everything the interface forces concrete either throws
 * "not used in these tests" or answers empty, so subclasses carry ONLY the
 * one override they actually assert on ({@code listPermissionRequests} in
 * the recovery tests, {@code respondToPermission} in the registry tests).
 * The point of the base is that the stub surface exists ONCE - the CPD gate
 * enforces exactly that.
 */
class ChatFakeClientBase implements OpencodeClient {

    @Override
    public HealthStatus getHealth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Agent> getAgents() {
        return List.of();
    }

    @Override
    public ProviderList getProviders() {
        return new ProviderList(List.of(), Map.of());
    }

    @Override
    public ConfigInfo getConfig() {
        return null;
    }

    @Override
    public List<Session> getSessions() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Session createSession(String title, Path directory) {
        return new Session("ses_1", null, title, null, null, null, null, null, null, null, null);
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) {
        // not needed here
    }

    @Override
    public List<ChatEntry> getMessages(String sessionId) {
        return List.of();
    }

    @Override
    public ChatEntry sendMessage(ChatRequest request) {
        return null;
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra) {
        // not needed here
    }
}
