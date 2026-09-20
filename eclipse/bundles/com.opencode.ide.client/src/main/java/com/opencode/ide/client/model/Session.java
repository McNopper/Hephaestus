package com.opencode.ide.client.model;

/**
 * A session (a running agent instance) as returned by {@code GET /session}.
 *
 * <p>Modelled against opencode v2.0.x. {@code parentID} is present on child
 * sessions (e.g. a subagent spawned by another agent) and is used to nest the
 * session tree. {@code model} carries the model triple (id/providerID/variant)
 * as an object. {@code outcome} is only set on finished runs
 * ({@code "succeeded"} etc.). {@code location.directory} is the working
 * directory the session was created against.</p>
 */
public record Session(
        String id,
        String projectID,
        String title,
        String agent,
        String parentID,
        ModelRef model,
        Time time,
        Double cost,
        Tokens tokens,
        String outcome,
        Location location) {

    /** The model triple as a nested object ({@code {"id":"k3","providerID":"kimi-coding-plan","variant":"high"}}). */
    public record ModelRef(String id, String providerID, String variant) {
    }

    /** Epoch millis. */
    public record Time(long created, long updated, long idle) {
    }

    /** Token usage of the run; the cache breakdown is nullable on some providers. */
    public record Tokens(long input, long output, long reasoning, Cache cache) {
    }

    /** Cache hit (read) vs. fill (write) token counts. */
    public record Cache(long read, long write) {
    }

    /** The session's working directory ({@code {"directory":"C:\\..."}}). */
    public record Location(String directory) {
    }

    /** @return true when the session has an idle timestamp (finished/idle). */
    public boolean isIdle() {
        return time != null && time.idle() > 0;
    }

    /** @return the working directory, or {@code null}. */
    public String directory() {
        return location == null ? null : location.directory();
    }

    /** @return the model id, or {@code null}. */
    public String modelId() {
        return model == null ? null : model.id();
    }

    /** @return the provider id, or {@code null}. */
    public String providerId() {
        return model == null ? null : model.providerID();
    }
}
