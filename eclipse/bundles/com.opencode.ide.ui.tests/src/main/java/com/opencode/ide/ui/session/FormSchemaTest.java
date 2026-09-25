package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link FormSchema} - the v2 form shape is DECLARED by the
 * service (Form.Field family); these pin that we parse it leniently and type
 * answers by the declared field types (never by guessing).
 */
public class FormSchemaTest {

    @Test
    public void fieldsParseFromAKeyedMapWithKeyFallback() {
        List<FormSchema.Field> fields = FormSchema.fieldsOf(Map.of(
                "env", Map.of("title", "Environment", "type", "string", "required", true),
                "prod", Map.of("key", "prod", "type", "boolean", "hidden", true)));

        assertEquals(2, fields.size());
        FormSchema.Field env = fields.stream().filter(f -> "env".equals(f.key())).findFirst().orElseThrow();
        assertTrue("required survives the parse", env.required());
        FormSchema.Field prod = fields.stream().filter(f -> "prod".equals(f.key())).findFirst().orElseThrow();
        assertTrue("hidden survives the parse", prod.hidden());
    }

    @Test
    public void fieldsParseFromAListAndOptionsBecomeValues() {
        List<FormSchema.Field> fields = FormSchema.fieldsOf(List.of(
                Map.of("key", "size", "type", "string", "options",
                        List.of(Map.of("value", "s", "label", "Small"), Map.of("value", "l", "label", "Large")))));

        assertEquals(List.of("s", "l"), fields.get(0).options());
    }

    @Test
    public void answersAreTypedByTheDeclaredFieldType() {
        List<FormSchema.Field> fields = List.of(
                new FormSchema.Field("name", "Name", "string", true, false, List.of(), null),
                new FormSchema.Field("count", "Count", "integer", false, false, List.of(), null),
                new FormSchema.Field("ratio", "Ratio", "number", false, false, List.of(), null),
                new FormSchema.Field("ok", "OK", "boolean", false, false, List.of(), null),
                new FormSchema.Field("secret", "Secret", "string", false, true, List.of(), null));

        Map<String, Object> answer = FormSchema.answer(fields, Map.of(
                "name", "hello", "count", "42", "ratio", "0.5", "ok", "true", "secret", "leak"));

        assertEquals("hello", answer.get("name"));
        assertEquals(Long.valueOf(42), answer.get("count"));
        assertEquals(Double.valueOf(0.5), answer.get("ratio"));
        assertEquals(Boolean.TRUE, answer.get("ok"));
        assertFalse("hidden fields are never answered", answer.containsKey("secret"));
    }

    @Test
    public void missingRequiredKeysAreNamed() {
        List<FormSchema.Field> fields = List.of(
                new FormSchema.Field("a", "A", "string", true, false, List.of(), null),
                new FormSchema.Field("b", "B", "string", true, false, List.of(), null));

        assertEquals(List.of("a", "b"), FormSchema.missingRequired(fields, Map.of()));
        assertTrue(FormSchema.missingRequired(fields, Map.of("a", "x", "b", "y")).isEmpty());
    }
}
