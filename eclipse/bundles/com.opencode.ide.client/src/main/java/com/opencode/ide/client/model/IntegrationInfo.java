package com.opencode.ide.client.model;

import java.util.List;

/**
 * One provider integration ({@code GET /integration}). v2.0.19 live probe:
 * {@code {location, data: [{id, name, methods: [{type, names: [...]}],
 * connections: []}]}} - the connection bodies are not modelled, only their
 * COUNT is (the integrations info surface shows how many exist; the probe
 * found none). Nullable-tolerant.
 */
public record IntegrationInfo(String id, String name, List<IntegrationMethod> methods, int connections) {

    /**
     * One auth method of an integration: {@code type} is {@code "key"} or
     * {@code "env"} (v2.0.19 live probe); {@code names} are the credential or
     * environment names it accepts.
     */
    public record IntegrationMethod(String type, List<String> names) {
    }
}
