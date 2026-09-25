package com.opencode.ide.ui.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The v2 form SCHEMA as the service declares it ({@code Form.Field} family:
 * string / number / integer / boolean / multiselect / external - each with
 * key, title, description, required, hidden). Capability alignment
 * 2026-09-25: we never guess the shape - the field map comes from
 * {@code listForms} and answers are keyed and TYPED by the declared field
 * types. Pure (no SWT) so the mapping is unit-testable.
 */
public final class FormSchema {

    /** One declared field, flattened for rendering. */
    public record Field(String key, String title, String type, boolean required,
            boolean hidden, List<String> options, String url) {
    }

    private FormSchema() {
    }

    /**
     * Extracts the declared fields from a form map's {@code fields} value.
     * Lenient on purpose: the value may be a key-&gt;field map or a field
     * list; malformed entries are skipped, never fatal.
     */
    @SuppressWarnings("unchecked")
    public static List<Field> fieldsOf(Object raw) {
        List<Field> fields = new ArrayList<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Field field = fieldOf(entry.getValue());
                if (field != null) {
                    fields.add(field.key() == null || field.key().isBlank()
                            ? new Field(String.valueOf(entry.getKey()), field.title(), field.type(),
                                    field.required(), field.hidden(), field.options(), field.url())
                            : field);
                }
            }
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                Field field = fieldOf(item);
                if (field != null) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static Field fieldOf(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) {
            return null;
        }
        List<String> options = new ArrayList<>();
        Object rawOptions = values.get("options");
        if (rawOptions instanceof List<?> list) {
            for (Object option : list) {
                // Form.Option is {value,label,description}; a plain string is
                // tolerated for lenient servers
                if (option instanceof Map<?, ?> map) {
                    Object value = map.get("value");
                    options.add(String.valueOf(value == null ? map.get("label") : value));
                } else if (option != null) {
                    options.add(String.valueOf(option));
                }
            }
        }
        return new Field(
                text(values.get("key")),
                text(values.get("title")),
                text(values.get("type")),
                Boolean.TRUE.equals(values.get("required")),
                Boolean.TRUE.equals(values.get("hidden")),
                options,
                text(values.get("url")));
    }

    /**
     * Builds the {@code Form.Reply} answer map from raw control values,
     * typed by each field's DECLARED type (boolean / integer / number /
     * everything else stays a string).
     */
    public static Map<String, Object> answer(List<Field> fields, Map<String, String> inputs) {
        Map<String, Object> answer = new java.util.LinkedHashMap<>();
        for (Field field : fields) {
            if (field.hidden() || field.key() == null) {
                continue;
            }
            String raw = inputs.get(field.key());
            if (raw == null) {
                continue;
            }
            switch (field.type()) {
                case "boolean" -> answer.put(field.key(), Boolean.valueOf(raw));
                case "integer" -> answer.put(field.key(), coerceLong(raw));
                case "number" -> answer.put(field.key(), coerceDouble(raw));
                default -> answer.put(field.key(), raw);
            }
        }
        return answer;
    }

    /** @return the missing required keys (empty when the answer is complete) */
    public static List<String> missingRequired(List<Field> fields, Map<String, String> inputs) {
        List<String> missing = new ArrayList<>();
        for (Field field : fields) {
            if (field.required() && !field.hidden()) {
                String raw = inputs.get(field.key());
                if (raw == null || raw.isBlank()) {
                    missing.add(field.key());
                }
            }
        }
        return missing;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long coerceLong(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double coerceDouble(String raw) {
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
