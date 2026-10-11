package com.opencode.ide.client;

/**
 * One {@code POST /session/:id/message} request.
 *
 * <p>A parameter object rather than a long argument list, because the server keeps adding
 * orthogonal knobs ({@code variant}, {@code system}, later {@code tools}/{@code format}). All
 * fields except {@code sessionId} and {@code text} are optional; {@code null}/blank means "server
 * default".
 *
 * @param sessionId target session ({@code ses_…})
 * @param agent agent name (e.g. {@code build}), or {@code null}
 * @param providerId provider id, or {@code null} for the server default model
 * @param modelId model id, or {@code null} for the server default model
 * @param variant reasoning-effort variant (e.g. {@code high}, {@code thinking}), must be one of
 *     {@link com.opencode.ide.client.model.Model#variantNames()}
 * @param system per-request system prompt, or {@code null}
 * @param text the user prompt (markdown allowed)
 */
public record ChatRequest(
    String sessionId,
    String agent,
    String providerId,
    String modelId,
    String variant,
    String system,
    String text) {

  /** A minimal request: session + prompt, everything else server-default. */
  public static ChatRequest of(String sessionId, String text) {
    return new ChatRequest(sessionId, null, null, null, null, null, text);
  }

  public ChatRequest withAgent(String newAgent) {
    return new ChatRequest(sessionId, newAgent, providerId, modelId, variant, system, text);
  }

  public ChatRequest withModel(String newProviderId, String newModelId) {
    return new ChatRequest(sessionId, agent, newProviderId, newModelId, variant, system, text);
  }

  public ChatRequest withVariant(String newVariant) {
    return new ChatRequest(sessionId, agent, providerId, modelId, newVariant, system, text);
  }

  public ChatRequest withSystem(String newSystem) {
    return new ChatRequest(sessionId, agent, providerId, modelId, variant, newSystem, text);
  }

  /**
   * @return true when both provider and model are set (an explicit model choice).
   */
  public boolean hasModel() {
    return providerId != null && !providerId.isBlank() && modelId != null && !modelId.isBlank();
  }
}
