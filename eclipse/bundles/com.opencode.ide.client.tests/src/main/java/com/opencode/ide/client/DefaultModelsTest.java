package com.opencode.ide.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.opencode.ide.client.model.ConfigInfo;
import com.opencode.ide.client.model.ProviderList;
import org.junit.Test;

/**
 * Unit tests for {@link DefaultModels} - the default-model resolution: a {@code /config} default is
 * TRUSTED as-is (C--001: silently swapping to another provider on a partial/stale provider fetch is
 * worse than a loud send failure); the first-provider fallback only applies when the config carries
 * no usable default.
 */
public class DefaultModelsTest {

  private static final Gson GSON = new Gson();

  /** acme has llm-1 (valid default) and llm-stale is stale; ollama has one model. */
  private static final String PROVIDERS_JSON =
      """
            { "providers": [
                { "id": "acme", "name": "ACME", "source": "api", "env": [], "options": {},
                  "models": {
                    "llm-1": { "id": "llm-1", "providerID": "acme", "name": "LLM 1" },
                    "llm-3": { "id": "llm-3", "providerID": "acme", "name": "LLM 3" } } },
                { "id": "ollama", "name": "Ollama", "source": "env", "env": [], "options": {},
                  "models": { "llama3": { "id": "llama3", "providerID": "tinylm" } } } ],
              "default": {} }
            """;

  private static ProviderList providers() {
    return GSON.fromJson(PROVIDERS_JSON, ProviderList.class);
  }

  @Test
  public void validConfigDefaultWins() {
    ConfigInfo config = new ConfigInfo("acme/llm-1", null);
    assertArrayEquals(new String[] {"acme", "llm-1"}, DefaultModels.resolve(config, providers()));
  }

  @Test
  public void staleConfigDefaultIsStillTrusted() {
    // llm-stale no longer exists on the provider -> trusted anyway: a loud
    // send failure beats a silent model swap
    ConfigInfo config = new ConfigInfo("acme/llm-stale", null);
    assertArrayEquals(
        new String[] {"acme", "llm-stale"}, DefaultModels.resolve(config, providers()));
  }

  @Test
  public void unknownProviderInConfigIsStillTrusted() {
    ConfigInfo config = new ConfigInfo("ghost-provider/whatever", null);
    assertArrayEquals(
        new String[] {"ghost-provider", "whatever"}, DefaultModels.resolve(config, providers()));
  }

  /**
   * The C--001 live incident: the provider fetch was partial (acme absent, ollama listed first) -
   * the config default must NOT silently become the first available foreign model.
   */
  @Test
  public void partialProviderFetchNeverOverridesConfigDefault() {
    ProviderList partial =
        GSON.fromJson(
            """
                { "providers": [
                    { "id": "ollama", "name": "Ollama", "source": "env", "env": [], "options": {},
                      "models": { "tiny-4b": { "id": "tiny-4b", "providerID": "tinylm" } } } ],
                  "default": {} }
                """,
            ProviderList.class);
    ConfigInfo config = new ConfigInfo("acme/llm-2", null);
    assertArrayEquals(new String[] {"acme", "llm-2"}, DefaultModels.resolve(config, partial));
  }

  @Test
  public void nullConfigFallsBack() {
    assertArrayEquals(new String[] {"acme", "llm-1"}, DefaultModels.resolve(null, providers()));
  }

  @Test
  public void nothingAvailableYieldsNull() {
    assertNull(DefaultModels.resolve(null, null));
    assertNull(
        DefaultModels.resolve(null, GSON.fromJson("{\"providers\":[]}", ProviderList.class)));
  }

  @Test
  public void malformedConfigDefaultFallsBack() {
    ConfigInfo config = new ConfigInfo("no-slash", null); // defaultModelParts() -> null
    assertArrayEquals(new String[] {"acme", "llm-1"}, DefaultModels.resolve(config, providers()));
  }
}
