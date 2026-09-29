package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link EnvironmentText}: the KEY=VALUE model of the session
 * environment dialog (U-048) — the wire's PUT fully replaces the variable
 * map and has no read route, so a stable parse/format round-trip is the
 * whole contract. SWT-free.
 */
public class EnvironmentTextTest {

    @Test
    public void formatRendersSortedKeyValueLines() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("ZED", "last");
        variables.put("ANTHROPIC_API_KEY", "sk-1");
        variables.put("CI", "true");

        assertEquals("ANTHROPIC_API_KEY=sk-1\nCI=true\nZED=last", EnvironmentText.format(variables));
    }

    @Test
    public void parseKeepsOrderAndToleratesBlankAndCommentLines() {
        Map<String, String> parsed = EnvironmentText.parse("""
                # a comment

                ONE=first
                  TWO  =  second
                THREE=
                """);

        assertEquals(3, parsed.size());
        assertEquals("first", parsed.get("ONE"));
        assertEquals("inner spaces around key and value are stripped", "second", parsed.get("TWO"));
        assertEquals("", parsed.get("THREE"));
        assertEquals("insertion order kept", "ONE", parsed.keySet().iterator().next());
    }

    @Test
    public void formatAndParseRoundTrip() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("A", "1");
        variables.put("B", "two words");

        assertEquals(variables, EnvironmentText.parse(EnvironmentText.format(variables)));
    }

    @Test
    public void aLineWithoutASeparatorIsRejectedWithName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EnvironmentText.parse("GOOD=1\nBADLINE\nOTHER=2"));
        assertTrue("the error names the line: " + e.getMessage(), e.getMessage().contains("line 2"));
        assertTrue(e.getMessage().contains("BADLINE"));
    }

    @Test
    public void invalidVariableNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EnvironmentText.parse("1BAD=x"));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentText.parse("BAD NAME=x"));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentText.parse("=x"));
        // valid shapes the dialog must accept
        assertEquals("1", EnvironmentText.parse("_UNDER=1").get("_UNDER"));
        assertEquals("1", EnvironmentText.parse("A.B-C_D=1").get("A.B-C_D"));
    }

    @Test
    public void nullAndEmptyInputsAreEmptyMaps() {
        assertTrue(EnvironmentText.parse(null).isEmpty());
        assertTrue(EnvironmentText.parse("").isEmpty());
        assertTrue(EnvironmentText.parse("   \n  \n").isEmpty());
        assertEquals("", EnvironmentText.format(null));
        assertEquals("", EnvironmentText.format(Map.of()));
    }
}
