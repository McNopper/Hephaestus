package com.opencode.ide.board.model;

import com.opencode.ide.fleet.dispatch.AutoDispatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for the SWT-free {@link DispatchPolicyStore} against an
 * in-memory fake backend: the safe defaults on empty, the save/load round
 * trip under the stable keys (save flushes), each documented load rule
 * (maxConcurrent below one or corrupt → 4; negative/infinite/corrupt
 * budget → the default 5; includeStale true only for the exact "true";
 * blank bootstrap → none), the value record's null normalization, and the
 * total behavior on a failing backend.
 */
public class DispatchPolicyStoreTest {

    /** In-memory fake backend recording the raw key/value pairs. */
    private static final class FakeBackend implements DispatchPolicyStore.Backend {

        final Map<String, String> values = new LinkedHashMap<>();
        boolean failReads;
        int flushes;

        @Override
        public String get(String key) {
            if (failReads) {
                throw new IllegalStateException("backend gone");
            }
            return values.get(key);
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void flush() {
            flushes++;
        }
    }

    private static DispatchPolicyStore.DispatchSettings load(FakeBackend backend) {
        return new DispatchPolicyStore(backend).load();
    }

    // ------------------------------------------------------------------
    // Defaults
    // ------------------------------------------------------------------

    @Test
    public void emptyBackendLoadsTheDefaults() {
        assertEquals(DispatchPolicyStore.defaults(), load(new FakeBackend()));
    }

    @Test
    public void defaultsAreFourFiveUsdFalseAndNoBootstrap() {
        DispatchPolicyStore.DispatchSettings defaults = DispatchPolicyStore.defaults();
        assertEquals(AutoDispatch.of(4, 5, false), defaults.policy());
        assertEquals("", defaults.bootstrapAgent());
        assertEquals("", defaults.bootstrapCommand());
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Test
    public void saveLoadRoundTripsUnderTheStableKeys() {
        FakeBackend backend = new FakeBackend();
        DispatchPolicyStore.DispatchSettings settings = new DispatchPolicyStore.DispatchSettings(
                AutoDispatch.of(7, 1.25, false), "build", "npm install");

        new DispatchPolicyStore(backend).save(settings);

        assertEquals("7", backend.values.get(DispatchPolicyStore.KEY_MAX_CONCURRENT));
        assertEquals("1.25", backend.values.get(DispatchPolicyStore.KEY_COST_BUDGET_USD));
        assertEquals("false", backend.values.get(DispatchPolicyStore.KEY_INCLUDE_STALE));
        assertEquals("build", backend.values.get(DispatchPolicyStore.KEY_BOOTSTRAP_AGENT));
        assertEquals("npm install", backend.values.get(DispatchPolicyStore.KEY_BOOTSTRAP_COMMAND));
        assertEquals("save flushes the backend", 1, backend.flushes);
        assertEquals(settings, load(backend));
    }

    // ------------------------------------------------------------------
    // Load validation rules
    // ------------------------------------------------------------------

    @Test
    public void maxConcurrentBelowOneFallsBackToTheDefault() {
        FakeBackend backend = new FakeBackend();
        backend.values.put(DispatchPolicyStore.KEY_MAX_CONCURRENT, "0");
        assertEquals(4, load(backend).policy().maxConcurrent());
        backend.values.put(DispatchPolicyStore.KEY_MAX_CONCURRENT, "-3");
        assertEquals(4, load(backend).policy().maxConcurrent());
    }

    @Test
    public void negativeOrInfiniteBudgetFallsBackToTheDefault() {
        FakeBackend backend = new FakeBackend();
        backend.values.put(DispatchPolicyStore.KEY_COST_BUDGET_USD, "-1");
        assertEquals(5, load(backend).policy().costBudgetUsd(), 0);
        backend.values.put(DispatchPolicyStore.KEY_COST_BUDGET_USD, "Infinity");
        assertEquals(5, load(backend).policy().costBudgetUsd(), 0);
    }

    @Test
    public void corruptNumbersLoadTheirDefaults() {
        FakeBackend backend = new FakeBackend();
        backend.values.put(DispatchPolicyStore.KEY_MAX_CONCURRENT, "four");
        backend.values.put(DispatchPolicyStore.KEY_COST_BUDGET_USD, "cheap");
        DispatchPolicyStore.DispatchSettings loaded = load(backend);
        assertEquals(4, loaded.policy().maxConcurrent());
        assertEquals(5, loaded.policy().costBudgetUsd(), 0);
    }

    @Test
    public void includeStaleIsTrueOnlyForTheExactTrue() {
        FakeBackend backend = new FakeBackend();
        backend.values.put(DispatchPolicyStore.KEY_INCLUDE_STALE, "true");
        assertTrue(load(backend).policy().includeStale());
        for (String corrupt : new String[] { null, "false", "TRUE", "yes", "1" }) {
            if (corrupt == null) {
                backend.values.remove(DispatchPolicyStore.KEY_INCLUDE_STALE);
            } else {
                backend.values.put(DispatchPolicyStore.KEY_INCLUDE_STALE, corrupt);
            }
            assertFalse(String.valueOf(corrupt) + " reads as the default false",
                    load(backend).policy().includeStale());
        }
    }

    @Test
    public void blankBootstrapLoadsAsNone() {
        FakeBackend backend = new FakeBackend();
        backend.values.put(DispatchPolicyStore.KEY_BOOTSTRAP_AGENT, "   ");
        backend.values.put(DispatchPolicyStore.KEY_BOOTSTRAP_COMMAND, "");
        DispatchPolicyStore.DispatchSettings loaded = load(backend);
        assertEquals("", loaded.bootstrapAgent());
        assertEquals("", loaded.bootstrapCommand());
    }

    @Test
    public void failingBackendLoadsTheDefaults() {
        FakeBackend backend = new FakeBackend();
        backend.failReads = true;
        assertEquals(DispatchPolicyStore.defaults(), load(backend));
    }

    // ------------------------------------------------------------------
    // Value record
    // ------------------------------------------------------------------

    @Test
    public void recordTrimsBootstrapStringsAndRejectsNullPolicy() {
        DispatchPolicyStore.DispatchSettings settings =
                new DispatchPolicyStore.DispatchSettings(AutoDispatch.of(2, 0, true), null, "  npm ci  ");
        assertEquals("", settings.bootstrapAgent());
        assertEquals("npm ci", settings.bootstrapCommand());
        assertThrows(NullPointerException.class,
                () -> new DispatchPolicyStore.DispatchSettings(null, "build", "npm ci"));
    }
}
