package com.opencode.ide.client;

import com.google.gson.JsonObject;

/**
 * Builds opencode chat request bodies (pure JSON building, no I/O - unit-testable).
 *
 * <p>v2 split v1's single {@code POST /session/:id/message} body across several
 * endpoints, so one {@link ChatRequest} now produces several bodies:</p>
 * <ul>
 *   <li>{@code agent} → {@code POST /session/:id/agent} (session state)</li>
 *   <li>{@code providerId}/{@code modelId}/{@code variant} → {@code POST /session/:id/model}
 *       (session state; v2's {@code Model.Ref} names the model {@code id}, not
 *       {@code modelID}, and folds {@code variant} into the same object)</li>
 *   <li>{@code system} → {@code POST /session/:id/synthetic} (v2 has no
 *       per-request system field on the prompt)</li>
 *   <li>{@code text} → {@code POST /session/:id/prompt}</li>
 * </ul>
 */
public final class ChatRequests {

    private ChatRequests() {
    }

    /** {@code POST /session/:id/prompt} body: the user prompt text. */
    public static String promptBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("text", request.text() == null ? "" : request.text());
        return body.toString();
    }

    /** {@code POST /session/:id/agent} body, or {@code null} when no agent is requested. */
    public static String agentBody(ChatRequest request) {
        if (!isSet(request.agent())) {
            return null;
        }
        JsonObject body = new JsonObject();
        body.addProperty("agent", request.agent());
        return body.toString();
    }

    /** {@code POST /session/:id/model} body, or {@code null} without an explicit model. */
    public static String modelBody(ChatRequest request) {
        if (!request.hasModel()) {
            return null;
        }
        JsonObject model = new JsonObject();
        model.addProperty("id", request.modelId());
        model.addProperty("providerID", request.providerId());
        if (isSet(request.variant())) {
            model.addProperty("variant", request.variant());
        }
        JsonObject body = new JsonObject();
        body.add("model", model);
        return body.toString();
    }

    /**
     * {@code POST /session/:id/synthetic} body carrying the per-request system
     * prompt, or {@code null} when none is set. A synthetic message enters the
     * model's context without showing up as a user turn — the closest v2
     * equivalent of v1's per-request {@code system} field.
     */
    public static String syntheticBody(ChatRequest request) {
        if (!isSet(request.system())) {
            return null;
        }
        JsonObject body = new JsonObject();
        body.addProperty("text", request.system());
        body.addProperty("description", "client capabilities");
        return body.toString();
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
