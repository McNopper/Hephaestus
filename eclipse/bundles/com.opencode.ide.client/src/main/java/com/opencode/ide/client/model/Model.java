package com.opencode.ide.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A model as returned by {@code GET /api/model} (v2) — v1 nested models under
 * the provider, v2 lists them flat with a {@code providerID}.
 *
 * <p>Mirrors {@code Model.Info} in the opencode v2 OpenAPI schema, verified
 * against a live 2.0.10 server. Two shapes changed in ways that break naive
 * parsing: {@code cost} is now an <em>array</em> of price tiers (v1 had a
 * single object), and {@code variants} is an array of variant objects (v1 had
 * an object keyed by name).</p>
 */
public record Model(
        String id,
        String modelID,
        String providerID,
        Api api,
        String name,
        String family,
        Capabilities capabilities,
        List<Cost> cost,
        Limit limit,
        String status,
        Boolean enabled,
        Map<String, Object> options,
        Map<String, String> headers,
        com.google.gson.JsonElement variants) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_BETA = "beta";
    public static final String STATUS_ALPHA = "alpha";
    public static final String STATUS_DEPRECATED = "deprecated";

    /**
     * Variant names in server order (e.g. {@code none, low, medium, high, xhigh, max}
     * or {@code none, thinking}), empty when the model has no variants.
     *
     * <p>Variants are opencode's per-request reasoning-effort settings: they are
     * sent as {@code "variant": "<name>"} on {@code POST /session/:id/message}.
     * v1: object keyed by name; v2: array of variant objects.</p>
     */
    public List<String> variantNames() {
        if (variants == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        if (variants.isJsonObject()) {
            names.addAll(variants.getAsJsonObject().keySet());
        } else if (variants.isJsonArray()) {
            for (var item : variants.getAsJsonArray()) {
                if (item.isJsonObject() && item.getAsJsonObject().has("name")) {
                    names.add(item.getAsJsonObject().get("name").getAsString());
                } else if (item.isJsonPrimitive()) {
                    names.add(item.getAsString());
                }
            }
        }
        return names;
    }

    /** @return true when {@code name} is a variant this model supports. */
    public boolean hasVariant(String name) {
        if (name == null) {
            return false;
        }
        return variantNames().contains(name);
    }

    /** The model's provider API endpoint (rarely set by local providers). */
    public record Api(String id, String url, String npm) {
    }

    /** What the model accepts/supports; drives the Providers view's R/A/T letters. */
    public record Capabilities(
            boolean temperature,
            boolean reasoning,
            boolean attachment,
            boolean tools,
            com.google.gson.JsonElement input,
            com.google.gson.JsonElement output) {

        /** v2 renamed {@code toolcall} to {@code tools}; kept for existing callers. */
        public boolean toolcall() {
            return tools;
        }

        /** Whether the model accepts text input (v1 boolean or v2 string-array). */
        public boolean inputText() {
            return hasModality(input, "text");
        }

        /** Whether the model produces text output. */
        public boolean outputText() {
            return hasModality(output, "text");
        }

        /** Whether the model accepts images (v1 boolean or v2 string-array). */
        public boolean inputImage() {
            return hasModality(input, "image");
        }

        private static boolean hasModality(com.google.gson.JsonElement el, String modality) {
            if (el == null) {
                return false;
            }
            if (el.isJsonArray()) {
                // v2: ["text", "image"]
                for (var item : el.getAsJsonArray()) {
                    if (modality.equals(item.getAsString())) {
                        return true;
                    }
                }
                return false;
            }
            if (el.isJsonObject()) {
                // v1: {"text": true}
                var obj = el.getAsJsonObject();
                return obj.has(modality) && obj.get(modality).isJsonPrimitive()
                        && obj.get(modality).getAsBoolean();
            }
            return false;
        }
    }

    /**
     * One price tier in USD per million tokens. v2 returns an array: the first
     * entry is the base rate and any further entries apply above a context
     * threshold (the {@code tier} discriminator), replacing v1's single
     * {@code experimentalOver200K} object.
     */
    public record Cost(Tier tier, double input, double output, CacheCost cache) {
    }

    /** The threshold a non-base {@link Cost} tier applies above. */
    public record Tier(String type, long size) {
    }

    /** Cached-token prices (cheaper than fresh input). */
    public record CacheCost(double read, double write) {
    }

    /** Context/output token limits — the context column in the Providers view. */
    public record Limit(long context, long output) {
    }

    /** @return the base (untiered) price row, or {@code null} when unpriced. */
    public Cost baseCost() {
        if (cost == null || cost.isEmpty()) {
            return null;
        }
        for (Cost entry : cost) {
            if (entry != null && entry.tier() == null) {
                return entry;
            }
        }
        return cost.get(0);
    }
}
