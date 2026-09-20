package com.opencode.ide.client.model;

import com.google.gson.annotations.SerializedName;

/**
 * Minimal mapping of the v2 {@code GET /config} info object - only the fields
 * the IDE needs. v2 {@code model} is an object {@code {providerID, model}};
 * v1 used a {@code "provider/model"} string. {@link #defaultModelParts()}
 * normalizes both.
 */
public record ConfigInfo(
        String model,
        @SerializedName("small_model") String smallModel) {

    /** v2 model object ({@code {providerID:"zai-coding-plan",model:"glm-5.3"}}). */
    public record ModelRef(String providerID, String model) {
    }

    /** @return the default model split into {@code [provider, model]}, or {@code null}. */
    public String[] defaultModelParts() {
        if (model == null) {
            return null;
        }
        int slash = model.indexOf('/');
        if (slash <= 0 || slash == model.length() - 1) {
            return null;
        }
        return new String[] { model.substring(0, slash), model.substring(slash + 1) };
    }

    /** @return parts from a v2 model object, or {@code null}. */
    public static String[] partsOf(ModelRef ref) {
        if (ref == null || ref.model() == null || ref.providerID() == null) {
            return null;
        }
        return new String[] { ref.providerID(), ref.model() };
    }
}
