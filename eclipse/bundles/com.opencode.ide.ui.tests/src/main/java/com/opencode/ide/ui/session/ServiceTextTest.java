package com.opencode.ide.ui.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link ServiceText} - the pure part of the log/stats/terminal
 * dialogs (Wave A, 2026-09-25).
 */
public class ServiceTextTest {

    @Test
    public void terminalRendersTheScreenWithItsContext() {
        Map<String, Object> read = new LinkedHashMap<>();
        read.put("title", "build");
        read.put("cwd", "C:/repo");
        read.put("foregroundProcess", "make");
        read.put("screen", "ok 12/12");

        String text = ServiceText.terminal(read);

        assertTrue("the header carries the title: " + text, text.contains("title: build"));
        assertTrue("the foreground process is named: " + text, text.contains("foreground: make"));
        assertTrue("the screen is rendered: " + text, text.contains("ok 12/12"));
    }

    @Test
    public void terminalWithoutASessionSaysSo() {
        assertEquals("(no controlled terminal for this session)", ServiceText.terminal(Map.of()));
        assertEquals("(no controlled terminal for this session)", ServiceText.terminal(null));
    }

    @Test
    public void keyValuesAreOnePerLine() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sessions", 3);
        stats.put("cost", 0.5);

        assertEquals("sessions: 3\ncost: 0.5", ServiceText.keyValues(stats));
    }

    @Test
    public void pluginsRenderOneLineEach() {
        java.util.List<Map<String, Object>> infos = new java.util.ArrayList<>();
        Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("id", "mermaid");
        plugin.put("source", "npm:mermaid");
        plugin.put("state", "loaded");
        infos.add(plugin);

        String text = ServiceText.plugins(infos);

        assertTrue("the plugin line carries id and state: " + text, text.contains("mermaid"));
        assertTrue(text.contains("loaded"));
    }

    @Test
    public void emptyPluginListSaysSo() {
        assertEquals("(no plugins installed)", ServiceText.plugins(java.util.List.of()));
        assertEquals("(no plugins installed)", ServiceText.plugins(null));
    }

    @Test
    public void listRendersOneBlockPerEntry() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", "p1");
        first.put("current", "1.0");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", "p2");

        String text = ServiceText.list(java.util.List.of(first, second));

        assertEquals("id: p1\ncurrent: 1.0\n\nid: p2", text);
        assertEquals("(none)", ServiceText.list(java.util.List.of()));
    }

    @Test
    public void nullsAndEmptyMapsAreTolerated() {
        assertEquals("(no data)", ServiceText.keyValues(null));
        assertEquals("(no data)", ServiceText.keyValues(Map.of()));
    }
}
