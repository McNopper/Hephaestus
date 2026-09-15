package com.opencode.ide.chat.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.opencode.ide.client.model.Agent;
import com.opencode.ide.client.model.ProviderList;

/** UI-thread-confined selector state; explicit choices survive delayed catalog loads. */
public final class ChatSelectorState {

    private List<String> agents = List.of();
    private final List<String> models = new ArrayList<>(List.of(""));
    private final Map<String, List<String>> variants = new LinkedHashMap<>();
    private String requestedAgent;
    private String requestedModel;
    private String agent;
    private String model = "";

    public void load(List<Agent> availableAgents, ProviderList providers, String[] preferred, String[] fallback) {
        agents = availableAgents == null ? List.of() : availableAgents.stream()
                .filter(a -> a != null && a.isPrimary() && a.name() != null && !a.name().isBlank())
                .map(Agent::name).distinct().toList();
        agent = requestedAgent != null && agents.contains(requestedAgent) ? requestedAgent
                : agents.contains("build") ? "build" : agents.stream().findFirst().orElse(null);
        models.clear();
        models.add("");
        variants.clear();
        if (providers != null && providers.providers() != null) {
            for (var provider : providers.providers()) {
                if (provider == null || provider.id() == null || provider.models() == null) {
                    continue;
                }
                for (var entry : provider.models().values()) {
                    if (entry != null && entry.id() != null) {
                        String id = provider.id() + "/" + entry.id();
                        if (!models.contains(id)) {
                            models.add(id);
                        }
                        variants.put(id, entry.variantNames());
                    }
                }
            }
        }
        String defaultModel = combined(preferred);
        if (defaultModel == null || !models.contains(defaultModel)) {
            defaultModel = combined(fallback);
        }
        // C--001 (live 2026-09-15): a catalog reload must never silently
        // change the EFFECTIVE model of a running conversation. Rank: an
        // explicit user pick (requestedModel) always wins; a workspace
        // preference wins when its model is in the catalog (a pref change is
        // user intent); otherwise the CURRENT selection is sticky while it
        // still exists - the server-derived fallback (first provider in
        // server order) is unstable across fetches, and following it turned
        // zai/glm-5.3 into ollama/qwen3.5-4b-32k (empty reply) with no user
        // interaction. The fallback applies only when nothing else is set or
        // the current model vanished from the catalog.
        String preferredModel = (preferred != null && models.contains(combined(preferred)))
                ? combined(preferred)
                : null;
        String current = (model != null && !model.isBlank()) ? model : null;
        if (requestedModel != null) {
            model = requestedModel;
        } else if (preferredModel != null) {
            model = preferredModel;
        } else if (current != null && models.contains(current)) {
            model = current;
        } else {
            model = defaultModel == null ? "" : defaultModel;
        }
        ensureModel();
    }

    public void selectAgent(String name) {
        if (name != null && !name.isBlank()) {
            requestedAgent = name;
            if (agents.contains(name)) {
                agent = name;
            }
        }
    }

    /** Empty string is an explicit request for the server default. */
    public void selectModel(String id) {
        if (id != null) {
            requestedModel = id;
            model = id;
            ensureModel();
        }
    }

    private void ensureModel() {
        if (!models.contains(model)) {
            models.add(1, model);
        }
    }

    private static String combined(String[] parts) {
        return parts == null || parts.length < 2 || parts[0] == null || parts[0].isBlank()
                || parts[1] == null || parts[1].isBlank() ? null : parts[0] + "/" + parts[1];
    }

    public List<String> agents() {
        return agents;
    }

    public List<String> models() {
        return List.copyOf(models);
    }

    public String agent() {
        return agent;
    }

    public String model() {
        return model;
    }

    public List<String> variants() {
        return variants.getOrDefault(model, List.of());
    }
}
