package com.opencode.ide.ui.testsupport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.opencode.ide.client.ChatRequest;
import com.opencode.ide.client.McpServerConfig;
import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.OpencodeException;
import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ChatEntry;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.HealthStatus;
import com.opencode.ide.client.model.ProviderList;
import com.opencode.ide.client.model.Session;
import com.opencode.ide.client.model.SessionStatus;

/**
 * Shared fake base for the {@link OpencodeClient} surface (fixes the CPD
 * findings of 2026-09-23: every test class copy-pasted this boilerplate).
 * Every abstract endpoint throws {@link UnsupportedOperationException}: a
 * test fake extends this class and overrides ONLY the endpoints it
 * exercises, which also turns "the code under test touched an endpoint it
 * must not touch" into a loud failure.
 */
public abstract class StubClient implements OpencodeClient {

    @Override
    public HealthStatus getHealth() throws OpencodeException {
        throw new UnsupportedOperationException("getHealth");
    }

    @Override
    public List<Agent> getAgents() throws OpencodeException {
        throw new UnsupportedOperationException("getAgents");
    }

    @Override
    public ProviderList getProviders() throws OpencodeException {
        throw new UnsupportedOperationException("getProviders");
    }

    @Override
    public ConfigInfo getConfig() throws OpencodeException {
        throw new UnsupportedOperationException("getConfig");
    }

    @Override
    public List<Session> getSessions() throws OpencodeException {
        throw new UnsupportedOperationException("getSessions");
    }

    @Override
    public Map<String, SessionStatus> getSessionStatus() throws OpencodeException {
        throw new UnsupportedOperationException("getSessionStatus");
    }

    @Override
    public Session createSession(String title, Path directory) throws OpencodeException {
        throw new UnsupportedOperationException("createSession");
    }

    @Override
    public void registerMcp(String name, McpServerConfig config) throws OpencodeException {
        throw new UnsupportedOperationException("registerMcp");
    }

    @Override
    public List<ChatEntry> getMessages(String sessionId) throws OpencodeException {
        throw new UnsupportedOperationException("getMessages");
    }

    @Override
    public ChatEntry sendMessage(ChatRequest request) throws OpencodeException {
        throw new UnsupportedOperationException("sendMessage");
    }

    @Override
    public void log(String service, String level, String message, Map<String, Object> extra)
            throws OpencodeException {
        throw new UnsupportedOperationException("log");
    }
}
