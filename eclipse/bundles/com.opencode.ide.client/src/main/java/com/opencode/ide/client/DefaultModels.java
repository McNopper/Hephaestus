package com.opencode.ide.client;

import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.Model;
import com.opencode.ide.client.model.Provider;
import com.opencode.ide.client.model.ProviderList;

/**
 * Resolves which model to send when the user has not picked one. A
 * {@code /config} default is TRUSTED as-is: the server is authoritative for
 * it, while the live provider list can be partial or stale - and silently
 * swapping to a different provider's model because the fetch missed the
 * configured one is worse than a loud send failure (C--001, live 2026-09-15:
 * a partial fetch turned glm-5.3 into a local 4b model that returned empty
 * replies). The first-provider fallback only applies when the config carries
 * no default at all. Pure - unit-testable.
 */
public final class DefaultModels {

    private DefaultModels() {
    }

    /**
     * @param config    the server config (may be {@code null}); its default model wins, trusted as-is
     * @param providers the live provider list (may be {@code null}); only used for the no-config fallback
     * @return {@code [providerId, modelId]} or {@code null} when nothing can be resolved
     */
    public static String[] resolve(ConfigInfo config, ProviderList providers) {
        String[] configured = (config != null) ? config.defaultModelParts() : null;
        if (configured != null && configured[0] != null && configured[1] != null) {
            return configured;
        }
        // fall back: first provider (in server order) that has models
        if (providers != null && providers.providers() != null) {
            for (Provider provider : providers.providers()) {
                if (provider == null || provider.models() == null || provider.models().isEmpty()) {
                    continue;
                }
                for (Model model : provider.models().values()) {
                    if (model != null && model.id() != null) {
                        return new String[] { provider.id(), model.id() };
                    }
                }
            }
        }
        return null;
    }
}
