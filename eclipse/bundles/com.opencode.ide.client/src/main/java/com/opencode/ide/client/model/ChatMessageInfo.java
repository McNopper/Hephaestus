package com.opencode.ide.client.model;

/**
 * The {@code info} object of a chat message entry from
 * {@code GET /api/session/:id/message} (v2 shape).
 *
 * <p>v2 flattened the message: {@code type} carries the role, the model is a
 * nested {@code {"id","providerID","variant"}} object for every role, and the
 * parts moved to {@code content}. {@link #providerId()} / {@link #modelId()}
 * still resolve either shape so older captures keep parsing.</p>
 *
 * <p>{@code completed} is the epoch-millis completion stamp of an assistant
 * message ({@code time.completed}); it is 0 while the turn is still streaming,
 * which is what {@link #isComplete()} polls on.</p>
 */
public record ChatMessageInfo(
        String id,
        String sessionID,
        String role,
        Session.Time time,
        String agent,
        String mode,
        String finish,
        Double cost,
        Session.Tokens tokens,
        String providerID,
        String modelID,
        String variant,
        Agent.ModelRef model,
        long completed) {

    /** @return true once the server stamped {@code time.completed} on this message. */
    public boolean isComplete() {
        return completed > 0;
    }

    /** Provider id for either role shape ({@code null} when the server omits it). */
    public String providerId() {
        if (providerID != null && !providerID.isBlank()) {
            return providerID;
        }
        return (model != null) ? model.providerID() : null;
    }

    /** Model id for either role shape ({@code null} when the server omits it). */
    public String modelId() {
        if (modelID != null && !modelID.isBlank()) {
            return modelID;
        }
        return (model != null) ? model.id() : null;
    }

    /** Reasoning-effort variant used for this message, or {@code null}. */
    public String variantName() {
        if (variant != null && !variant.isBlank()) {
            return variant;
        }
        return (model != null && model.variant() != null && !model.variant().isBlank())
                ? model.variant() : null;
    }

    /**
     * @return {@code provider/model} (plus {@code " (variant)"} when a reasoning
     *         variant was used), or {@code ""} when unknown - the chat meta line.
     */
    public String modelLabel() {
        String provider = providerId();
        String modelName = modelId();
        if (provider == null || modelName == null) {
            return "";
        }
        String label = provider + "/" + modelName;
        String variantName = variantName();
        return (variantName == null) ? label : label + " (" + variantName + ")";
    }
}
