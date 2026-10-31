package com.opencode.ide.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for the pure {@link ProviderColors} derivation used by provider icons. */
public class ProviderColorsTest {

  @Test
  public void hueIsDeterministic() {
    assertEquals(ProviderColors.hueFor("anthropic"), ProviderColors.hueFor("anthropic"), 0f);
  }

  @Test
  public void hueInRange() {
    for (String id : new String[] {"opencode", "anthropic", "openai", "google", "ollama"}) {
      float h = ProviderColors.hueFor(id);
      assertTrue("hue for " + id, h >= 0f && h < 360f);
    }
  }

  @Test
  public void nullOrBlankMapsToZero() {
    assertEquals(0f, ProviderColors.hueFor(null), 0f);
    assertEquals(0f, ProviderColors.hueFor(""), 0f);
    assertEquals(0f, ProviderColors.hueFor("   "), 0f);
  }

  @Test
  public void distinctProvidersGetDistinctColors() {
    // not guaranteed for every pair, but these common providers happen to differ
    assertNotEquals(ProviderColors.rgbFor("anthropic"), ProviderColors.rgbFor("openai"));
    assertNotEquals(ProviderColors.rgbFor("opencode"), ProviderColors.rgbFor("ollama"));
  }

  @Test
  public void initialFromNameThenIdThenFallback() {
    assertEquals('O', ProviderColors.initialFor("OpenAI", null));
    assertEquals('A', ProviderColors.initialFor(null, "anthropic"));
    assertEquals('?', ProviderColors.initialFor("", ""));
  }
}
