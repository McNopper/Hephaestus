package com.opencode.ide.fleet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * U-038 maintenance gate: one flag file under the fleet root
 * ({@code .git/opencode-fleet/maintenance}) that makes every dispatch path
 * refuse with a clear {@code maintenance} reason while a full-stack shutdown
 * or a maintenance window is active. {@link #engage} writes the reason,
 * {@link #clear} removes the flag (bring-up), and an absent file reads as
 * open for dispatch. Visible in status surfaces via {@link #reason}.
 */
public final class MaintenanceGate {

    /** The flag file name inside the fleet root. */
    public static final String FILE_NAME = "maintenance";

    private MaintenanceGate() {
    }

    /**
     * @return the active maintenance reason, or {@code null} when dispatch
     *         is open (no flag, or unreadable = fail open with a warning by
     *         the caller)
     */
    public static String reason(Path fleetRoot) {
        if (fleetRoot == null) {
            return null;
        }
        Path flag = fleetRoot.resolve(FILE_NAME);
        try {
            return Files.isRegularFile(flag) ? Files.readString(flag).strip() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** @return true while the maintenance flag is set */
    public static boolean isActive(Path fleetRoot) {
        return reason(fleetRoot) != null;
    }

    /** Engages the gate with a reason (the shutdown's first, ordered step). */
    public static void engage(Path fleetRoot, String reason) throws IOException {
        Files.createDirectories(fleetRoot);
        Files.writeString(fleetRoot.resolve(FILE_NAME),
                reason == null || reason.isBlank() ? "maintenance" : reason.strip());
    }

    /** Clears the gate (bring-up); idempotent. */
    public static void clear(Path fleetRoot) throws IOException {
        if (fleetRoot != null) {
            Files.deleteIfExists(fleetRoot.resolve(FILE_NAME));
        }
    }
}
