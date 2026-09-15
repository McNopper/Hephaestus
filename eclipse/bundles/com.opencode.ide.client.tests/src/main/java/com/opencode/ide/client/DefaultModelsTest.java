package com.opencode.ide.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.google.gson.Gson;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.ProviderList;

/**
 * Unit tests for {@link DefaultModels} - the default-model resolution: a
 * {@code /config} default is TRUSTED as-is (C--001: silently swapping to
 * another provider on a partial/stale provider fetch is worse than a loud
 * send failure); the first-provider fallback only applies when the config
 * carries no usable default.
 */
public class DefaultModelsTest {

    private static final Gson GSON = new Gson();

    /** zai has glm-5.2 (valid default) and glm-4.6 is stale; ollama has one model. */
    private static final String PROVIDERS_JSON = """
            { "providers": [
                { "id": "zai-coding-plan", "name": "ZAI", "source": "api", "env": [], "options": {},
                  "models": {
                    "glm-5.2": { "id": "glm-5.2", "providerID": "zai-coding-plan", "name": "GLM 5.2" },
                    "glm-4.7": { "id": "glm-4.7", "providerID": "zai-coding-plan", "name": "GLM 4.7" } } },
                { "id": "ollama", "name": "Ollama", "source": "env", "env": [], "options": {},
                  "models": { "llama3": { "id": "llama3", "providerID": "ollama" } } } ],
              "default": {} }
            """;

    private static ProviderList providers() {
        return GSON.fromJson(PROVIDERS_JSON, ProviderList.class);
    }

    @Test
    public void validConfigDefaultWins() {
        ConfigInfo config = new ConfigInfo("zai-coding-plan/glm-5.2", null);
        assertArrayEquals(new String[] { "zai-coding-plan", "glm-5.2" },
                DefaultModels.resolve(config, providers()));
    }

    @Test
    public void staleConfigDefaultIsStillTrusted() {
        // glm-4.6 no longer exists on the provider -> trusted anyway: a loud
        // send failure beats a silent model swap
        ConfigInfo config = new ConfigInfo("zai-coding-plan/glm-4.6", null);
        assertArrayEquals(new String[] { "zai-coding-plan", "glm-4.6" },
                DefaultModels.resolve(config, providers()));
    }

    @Test
    public void unknownProviderInConfigIsStillTrusted() {
        ConfigInfo config = new ConfigInfo("ghost-provider/whatever", null);
        assertArrayEquals(new String[] { "ghost-provider", "whatever" },
                DefaultModels.resolve(config, providers()));
    }

    /**
     * The C--001 live incident: the provider fetch was partial (zai absent,
     * ollama listed first) - the config default must NOT silently become the
     * first available foreign model.
     */
    @Test
    public void partialProviderFetchNeverOverridesConfigDefault() {
        ProviderList partial = GSON.fromJson("""
                { "providers": [
                    { "id": "ollama", "name": "Ollama", "source": "env", "env": [], "options": {},
                      "models": { "qwen3.5-4b-32k": { "id": "qwen3.5-4b-32k", "providerID": "ollama" } } } ],
                  "default": {} }
                """, ProviderList.class);
        ConfigInfo config = new ConfigInfo("zai-coding-plan/glm-5.3", null);
        assertArrayEquals(new String[] { "zai-coding-plan", "glm-5.3" },
                DefaultModels.resolve(config, partial));
    }

    @Test
    public void nullConfigFallsBack() {
        assertArrayEquals(new String[] { "zai-coding-plan", "glm-5.2" },
                DefaultModels.resolve(null, providers()));
    }

    @Test
    public void nothingAvailableYieldsNull() {
        assertNull(DefaultModels.resolve(null, null));
        assertNull(DefaultModels.resolve(null, GSON.fromJson("{\"providers\":[]}", ProviderList.class)));
    }

    @Test
    public void malformedConfigDefaultFallsBack() {
        ConfigInfo config = new ConfigInfo("no-slash", null); // defaultModelParts() -> null
        assertArrayEquals(new String[] { "zai-coding-plan", "glm-5.2" },
                DefaultModels.resolve(config, providers()));
    }
}
