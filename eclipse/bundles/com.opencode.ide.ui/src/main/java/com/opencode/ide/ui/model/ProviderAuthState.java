package com.opencode.ide.ui.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.opencode.ide.client.OpencodeClient;
import com.opencode.ide.client.model.ProviderAuth;

/**
 * Pure (SWT-free) provider auth model for the Providers view: per provider,
 * the auth methods available ({@code GET /api/integration}) —
 * {@link #authenticated()} means at least one method exists — and their
 * labels (falling back to the method type). Drives the
 * {@code " · auth"} suffix of a provider's rows and the availability of the
 * Connect… action.
 *
 * <p>{@link #load(OpencodeClient)} is lenient by design: an absent endpoint,
 * empty or {@code null} list or any transport failure degrades to an empty
 * map — never {@code null}, never an exception — so the view renders exactly
 * as before while auth data is unavailable. Entries without a provider id or
 * without any label/type are skipped.</p>
 */
public final class ProviderAuthState {

    private final List<String> methods;

    private ProviderAuthState(List<String> methods) {
        this.methods = List.copyOf(methods);
    }

    /**
     * The auth methods of every provider, keyed by provider id, in wire
     * order. Failure degrades to an empty map.
     */
    public static Map<String, ProviderAuthState> load(OpencodeClient client) {
        return load(client, null);
    }

    /** Loads the integration catalog for the view's project directory. */
    public static Map<String, ProviderAuthState> load(OpencodeClient client, String directory) {
        if (client == null) {
            return Map.of();
        }
        List<ProviderAuth> auths;
        try {
            auths = client.getProviderAuths(directory);
        } catch (Exception e) {
            return Map.of();
        }
        if (auths == null || auths.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> byProvider = new LinkedHashMap<>();
        for (ProviderAuth auth : auths) {
            if (auth == null || auth.provider() == null || auth.provider().isBlank()) {
                continue;
            }
            String method = auth.label() == null || auth.label().isBlank() ? auth.type() : auth.label();
            if (method == null || method.isBlank()) {
                continue;
            }
            byProvider.computeIfAbsent(auth.provider(), key -> new ArrayList<>()).add(method);
        }
        Map<String, ProviderAuthState> states = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : byProvider.entrySet()) {
            states.put(entry.getKey(), new ProviderAuthState(entry.getValue()));
        }
        return Collections.unmodifiableMap(states);
    }

    /**
     * The pure factory behind every instance (tests and future callers):
     * blank method labels are dropped; a {@code null} input yields no
     * methods.
     */
    public static ProviderAuthState of(List<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return new ProviderAuthState(List.of());
        }
        return new ProviderAuthState(methods.stream()
                .filter(method -> method != null && !method.isBlank())
                .collect(Collectors.toList()));
    }

    /** Whether at least one auth method is available for the provider. */
    public boolean authenticated() {
        return !methods.isEmpty();
    }

    /** The method labels (falling back to types), unmodifiable, in wire order. */
    public List<String> methods() {
        return methods;
    }
}
