package com.opencode.ide.client.model;

/**
 * One skill exposed by the opencode server ({@code GET /skill}; loaded from the
 * working directory's {@code .opencode/skills/}). {@code id} is the payload of
 * the experimental attach verb ({@code POST .../session/{id}/skill} wants the
 * skill id in its {@code skill} body field); {@code name}/{@code description}
 * are the display fields. Nullable-tolerant; unknown fields of the full skill
 * body are ignored by the mapping.
 */
public record SkillInfo(String id, String name, String description) {

    /**
     * @return the id to send when attaching this skill: the wire {@code id}
     *         when present, the name as the fallback for payloads without one
     *         (older servers), or {@code null} when neither is usable.
     */
    public String attachId() {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return name == null || name.isBlank() ? null : name;
    }
}
