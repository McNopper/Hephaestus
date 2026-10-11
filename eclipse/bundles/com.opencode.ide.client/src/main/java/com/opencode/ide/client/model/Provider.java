package com.opencode.ide.client.model;

import java.util.List;
import java.util.Map;

/**
 * A provider as returned by {@code GET /config/providers} (inside {@link ProviderList}).
 *
 * <p>Note: {@code models} is a map keyed by model id in the server schema, not an array. Mirrors
 * the {@code Provider} type in the opencode OpenAPI schema.
 */
public record Provider(
    String id,
    String name,
    String source,
    List<String> env,
    String key,
    Map<String, Object> options,
    Map<String, Model> models) {

  public static final String SOURCE_ENV = "env";
  public static final String SOURCE_CONFIG = "config";
  public static final String SOURCE_CUSTOM = "custom";
  public static final String SOURCE_API = "api";
}
