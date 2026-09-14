package com.opencode.ide.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;

/** Persistent project binding for legacy ID-only branches and worktrees.
 * Bindings survive reset/reaping. Unknown existing work is never auto-adopted. */
public final class FleetOwnership {
    private FleetOwnership() { }

    public static void claim(Path repo, String project, String id, BooleanSupplier residue) {
        if (project == null || project.isBlank() || project.contains("\n") || project.contains("\r")
                || id == null || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid fleet project/ticket identity");
        }
        FileGate.with(FleetGit.fleetRoot(repo).resolve("admission.lock"), () -> {
            Path owner = FleetGit.fleetRoot(repo).resolve(id + ".project");
            String binding = "fleet-project-v1\n" + project + "\n";
            try {
                if (Files.exists(owner)) {
                    if (!binding.equals(Files.readString(owner))) {
                        throw new IllegalStateException("fleet slot " + id + " belongs to another project or has unknown ownership");
                    }
                } else {
                    if (residue.getAsBoolean()) {
                        throw new IllegalStateException("fleet slot " + id + " has existing work with unknown project ownership; explicit recovery required");
                    }
                    Files.writeString(owner, binding, StandardOpenOption.CREATE_NEW);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot bind fleet slot " + id, e);
            }
            return null;
        });
    }
}
