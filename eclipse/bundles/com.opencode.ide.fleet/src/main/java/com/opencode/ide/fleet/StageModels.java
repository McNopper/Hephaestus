package com.opencode.ide.fleet;

import java.util.function.Function;

/**
 * The optional per-stage model policy (U-072): hosts that own dispatch
 * settings INSTALL a resolver (V-stage {@code -> provider/model[#variant]})
 * at startup; the fleet's launch precedence consults it as its LAST fallback
 * - per-run override &gt; ticket model &gt; stage policy &gt; server default.
 *
 * <p>Uninstalled (the default) returns {@code null} everywhere, which is
 * exactly today's behavior - the fleet bundle stays free of any preference
 * dependency (the policy store lives in the board bundle; layering keeps the
 * dependency arrow pointing up). A host without dispatch settings simply
 * never installs a resolver.</p>
 */
public final class StageModels {

    private static volatile Function<String, String> resolver;

    private StageModels() {
    }

    /** Installs the host's resolver (stage -> model); replaces a previous one. */
    public static void install(Function<String, String> stageToModel) {
        resolver = stageToModel;
    }

    /** Removes the resolver (tests and shutdown hygiene). */
    public static void clear() {
        resolver = null;
    }

    /** @return the configured model for the stage, or {@code null} when uninstalled, unmapped or blank */
    public static String forStage(String stage) {
        Function<String, String> current = resolver;
        if (current == null || stage == null || stage.isBlank()) {
            return null;
        }
        String model = current.apply(stage);
        return model == null || model.isBlank() ? null : model;
    }
}
