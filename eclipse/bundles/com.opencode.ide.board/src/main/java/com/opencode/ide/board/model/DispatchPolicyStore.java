package com.opencode.ide.board.model;

import com.opencode.ide.fleet.dispatch.AutoDispatch;

import java.util.Objects;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * SWT-free persistence for the dispatch policy (H6): one {@link AutoDispatch}
 * plus the optional per-launch bootstrap (agent + command strings, blank =
 * none) under stable keys of a stable node, backend-agnostic through the tiny
 * {@link Backend} seam — production runs on Eclipse instance preferences
 * ({@link #eclipse()}, the core {@code OpencodePreferences} pattern), tests
 * on an in-memory fake. Both Board dispatch actions load through
 * {@link #load()} at action time, so dialog edits apply without a restart.
 *
 * <p>Load validation (documented rules — a broken stored value never breaks
 * dispatch): {@code maxConcurrent} must parse to an {@code int >= 1}, else
 * the default {@value #DEFAULT_MAX_CONCURRENT} applies (absent, corrupt, or
 * out of range); {@code costBudgetUsd} must parse to a finite
 * {@code double >= 0}, else the default {@value #DEFAULT_COST_BUDGET_USD}
 * applies; {@code includeStale} is true only when the stored value is
 * exactly {@code "true"} — absent or corrupt reads as the default false.
 * Bootstrap strings are trimmed; blank means none. {@link #load()} never
 * throws: a failing backend yields {@link #defaults()}.</p>
 */
public final class DispatchPolicyStore {

    /** Stable instance-preferences node (see {@link #eclipse()}). */
    public static final String NODE = "com.opencode.ide.board.dispatchPolicy";

    /** Stable keys under {@link #NODE}. */
    public static final String KEY_MAX_CONCURRENT = "maxConcurrent";
    public static final String KEY_COST_BUDGET_USD = "costBudgetUsd";
    public static final String KEY_INCLUDE_STALE = "includeStale";
    public static final String KEY_BOOTSTRAP_AGENT = "bootstrapAgent";
    public static final String KEY_BOOTSTRAP_COMMAND = "bootstrapCommand";

    /** The default concurrency (also the fallback of a corrupt {@link #KEY_MAX_CONCURRENT}). */
    public static final int DEFAULT_MAX_CONCURRENT = 4;

    /**
     * The default cost budget in USD (also the fallback of an absent,
     * corrupt, or out-of-range {@link #KEY_COST_BUDGET_USD}): bounded on
     * purpose — {@code 0} means unlimited, and unlimited is not a safe
     * default (see {@link #defaults()}).
     */
    public static final double DEFAULT_COST_BUDGET_USD = 5;

    /**
     * The default {@code includeStale} (also the fallback of an absent or
     * corrupt {@link #KEY_INCLUDE_STALE}): STALE re-runs wait for an
     * explicit opt-in (see {@link #defaults()}).
     */
    public static final boolean DEFAULT_INCLUDE_STALE = false;

    private final Backend backend;

    /**
     * One stored dispatch policy plus its bootstrap strings (trimmed;
     * blank means none).
     */
    public record DispatchSettings(AutoDispatch policy, String bootstrapAgent, String bootstrapCommand) {

        /** Normalizes: non-null policy, trimmed never-null bootstrap strings. */
        public DispatchSettings {
            policy = Objects.requireNonNull(policy, "policy");
            bootstrapAgent = stripToEmpty(bootstrapAgent);
            bootstrapCommand = stripToEmpty(bootstrapCommand);
        }

        private static String stripToEmpty(String value) {
            return value == null ? "" : value.strip();
        }
    }

    /** Minimal string key/value persistence the store runs on (java.util.prefs-style). */
    public interface Backend {

        /** @return the stored value, or {@code null} when the key is absent */
        String get(String key);

        void put(String key, String value);

        /** Persists pending writes; may throw when persistence is unavailable. */
        void flush();
    }

    /**
     * @param backend the persistence seam; production uses {@link #eclipse()},
     *                tests an in-memory fake
     */
    public DispatchPolicyStore(Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** Production store over Eclipse instance preferences (node {@value #NODE}). */
    public static DispatchPolicyStore eclipse() {
        IEclipsePreferences node = InstanceScope.INSTANCE.getNode(NODE);
        return new DispatchPolicyStore(new Backend() {
            @Override
            public String get(String key) {
                return node.get(key, null);
            }

            @Override
            public void put(String key, String value) {
                node.put(key, value);
            }

            @Override
            public void flush() {
                try {
                    node.flush();
                } catch (BackingStoreException e) {
                    throw new IllegalStateException("cannot persist dispatch settings: " + e.getMessage(), e);
                }
            }
        });
    }

    /**
     * The safe defaults (P-003): {@code AutoDispatch.of(4, 5, false)} —
     * concurrency {@value #DEFAULT_MAX_CONCURRENT}, an explicit
     * ${@value #DEFAULT_COST_BUDGET_USD} budget, {@code includeStale} off —
     * and no bootstrap. Why bounded and stale-off: a zero (unlimited) budget
     * combined with {@code includeStale} on and a churning STALE ticket is
     * an unbounded token burn loop — every admitted re-run books the
     * {@link AutoDispatch#ESTIMATED_COST_USD} placeholder against no cap,
     * and the next snapshot admits it again — so out of the box spend is
     * bounded and STALE re-runs need an explicit opt-in.
     */
    public static DispatchSettings defaults() {
        return new DispatchSettings(
                AutoDispatch.of(DEFAULT_MAX_CONCURRENT, DEFAULT_COST_BUDGET_USD, DEFAULT_INCLUDE_STALE), "", "");
    }

    /** Loads and validates (see class doc); never throws — a failing backend yields {@link #defaults()}. */
    public DispatchSettings load() {
        try {
            return new DispatchSettings(
                    AutoDispatch.of(maxConcurrentOf(backend.get(KEY_MAX_CONCURRENT)),
                            budgetOf(backend.get(KEY_COST_BUDGET_USD)),
                            "true".equals(backend.get(KEY_INCLUDE_STALE))),
                    backend.get(KEY_BOOTSTRAP_AGENT),
                    backend.get(KEY_BOOTSTRAP_COMMAND));
        } catch (RuntimeException e) {
            return defaults();
        }
    }

    /** Persists all five values; a flush failure propagates (the dialog shows it). */
    public void save(DispatchSettings settings) {
        DispatchSettings value = Objects.requireNonNull(settings, "settings");
        backend.put(KEY_MAX_CONCURRENT, Integer.toString(value.policy().maxConcurrent()));
        backend.put(KEY_COST_BUDGET_USD, Double.toString(value.policy().costBudgetUsd()));
        backend.put(KEY_INCLUDE_STALE, Boolean.toString(value.policy().includeStale()));
        backend.put(KEY_BOOTSTRAP_AGENT, value.bootstrapAgent());
        backend.put(KEY_BOOTSTRAP_COMMAND, value.bootstrapCommand());
        backend.flush();
    }

    /** An {@code int >= 1} or the default (see class doc). */
    private static int maxConcurrentOf(String raw) {
        if (raw == null) {
            return DEFAULT_MAX_CONCURRENT;
        }
        try {
            int parsed = Integer.parseInt(raw.strip());
            return parsed >= 1 ? parsed : DEFAULT_MAX_CONCURRENT;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_CONCURRENT;
        }
    }

    /** A finite {@code double >= 0} or the default budget (see class doc). */
    private static double budgetOf(String raw) {
        if (raw == null) {
            return DEFAULT_COST_BUDGET_USD;
        }
        try {
            double parsed = Double.parseDouble(raw.strip());
            return Double.isFinite(parsed) && parsed >= 0 ? parsed : DEFAULT_COST_BUDGET_USD;
        } catch (NumberFormatException e) {
            return DEFAULT_COST_BUDGET_USD;
        }
    }
}
