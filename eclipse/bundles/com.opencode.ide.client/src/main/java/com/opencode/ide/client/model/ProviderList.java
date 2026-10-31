package com.opencode.ide.client.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code GET /config/providers}: the providers and the default model selected per
 * provider.
 *
 * <p>Mirrors the server schema {@code { providers: Provider[], default: {[pid]: mid} }}. {@code
 * default} is a Java keyword so the field is named {@code defaults} and mapped to the {@code
 * "default"} JSON key via {@link SerializedName}.
 */
public record ProviderList(
    List<Provider> providers, @SerializedName("default") Map<String, String> defaults) {}
