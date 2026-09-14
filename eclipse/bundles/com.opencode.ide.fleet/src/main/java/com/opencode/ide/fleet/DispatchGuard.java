package com.opencode.ide.fleet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.opencode.ide.git.FileGate;
import com.opencode.ide.git.FleetGit;

/** Cross-process ticket ownership shared by dispatch, recovery and the Board. */
public final class DispatchGuard implements AutoCloseable {
    /** A peer/capacity race: callers should re-plan, rather than retry a broken launch. */
    public static final class AdmissionDeferred extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public AdmissionDeferred(String message) {
            super(message);
        }
    }
    private static final Pattern OWNER = Pattern.compile("^pid=(\\d+)(?: start=(\\S+))? at .+", Pattern.DOTALL);
    private final Path repo;
    private final Path marker;
    private final String ownership;
    private boolean released;

    private DispatchGuard(Path repo, Path marker, String ownership) {
        this.repo = repo;
        this.marker = marker;
        this.ownership = ownership;
    }

    /** Lock order: admission, then caller lifecycle, then repository mutation. */
    public static <T> T exclusive(Path repo, Supplier<T> action) {
        return FileGate.with(FleetGit.fleetRoot(repo).resolve("admission.lock"), action);
    }

    /** The callback must reserve synchronously before returning. Reentrant for acquire. */
    public static <T> T admit(Path repo, int maximum, Supplier<T> launch) {
        if (maximum < 1) {
            throw new IllegalArgumentException("maximum must be >= 1");
        }
        return exclusive(repo, () -> {
            sweepLocked(repo);
            if (runningLocked(repo).size() >= maximum) {
                throw new AdmissionDeferred("fleet concurrency cap reached: " + maximum);
            }
            return launch.get();
        });
    }

    /** Low-level exclusion only. Project-level callers must use the project overload. */
    public static DispatchGuard acquire(Path repo, String ticketId) {
        if (ticketId == null || !ticketId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid dispatch ticket id: " + ticketId);
        }
        return exclusive(repo, () -> {
            Path marker = FleetGit.fleetRoot(repo).resolve(ticketId + ".dispatch");
            sweepMarker(marker);
            ProcessHandle owner = ProcessHandle.current();
            String ownership = "pid=" + owner.pid()
                    + owner.info().startInstant().map(start -> " start=" + start).orElse("")
                    + " at " + Instant.now() + " token=" + UUID.randomUUID() + "\n";
            try {
                Files.writeString(marker, ownership, StandardOpenOption.CREATE_NEW);
                return new DispatchGuard(repo, marker, ownership);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new AdmissionDeferred("ticket " + ticketId
                        + " is being dispatched or reset by another engine (marker " + marker + " exists)");
            } catch (IOException e) {
                throw new IllegalStateException("cannot reserve ticket " + ticketId, e);
            }
        });
    }

    /** Project-aware reservation. Persistent ownership outlives this handle. */
    public static DispatchGuard acquire(Path repo, String project, String ticketId) {
        return exclusive(repo, () -> {
            DispatchGuard guard = acquire(repo, ticketId);
            try {
                FleetGit.defaultManager().claimProject(repo, project, ticketId);
                return guard;
            } catch (RuntimeException | Error e) {
                guard.close();
                throw e;
            }
        });
    }

    /** Execute a short transition only while this exact reservation still owns the slot. */
    public <T> T withOwnership(Path expectedRepo, String project, String ticketId, Supplier<T> action) {
        return exclusive(repo, () -> {
            try {
                if (released || !marker.equals(FleetGit.fleetRoot(expectedRepo).resolve(ticketId + ".dispatch"))
                        || !ownership.equals(Files.readString(marker))) {
                    throw new IllegalStateException("reservation no longer owns ticket " + ticketId);
                }
                FleetGit.defaultManager().claimProject(repo, project, ticketId);
                return action.get();
            } catch (IOException e) {
                throw new IllegalStateException("cannot verify reservation for " + ticketId, e);
            }
        });
    }

    /** Includes reservations not yet visible in any process-local jobs snapshot. */
    public static Set<String> runningIds(Path repo) {
        return exclusive(repo, () -> {
            sweepLocked(repo);
            return runningLocked(repo);
        });
    }

    private static Set<String> runningLocked(Path repo) {
        try (var files = Files.list(FleetGit.fleetRoot(repo))) {
            Set<String> ids = new TreeSet<>();
            files.map(p -> p.getFileName().toString()).filter(name -> name.endsWith(".dispatch"))
                    .forEach(name -> ids.add(name.substring(0, name.length() - ".dispatch".length())));
            return Set.copyOf(ids);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fleet reservations", e);
        }
    }

    public static int sweepStale(Path repo) {
        return exclusive(repo, () -> sweepLocked(repo));
    }

    private static int sweepLocked(Path repo) {
        try (var files = Files.list(FleetGit.fleetRoot(repo))) {
            int swept = 0;
            for (Path marker : files.filter(p -> p.getFileName().toString().endsWith(".dispatch")).toList()) {
                if (sweepMarker(marker)) {
                    swept++;
                }
            }
            return swept;
        } catch (IOException e) {
            throw new IllegalStateException("cannot sweep fleet reservations", e);
        }
    }

    // All reads AND deletes are under admission.lock; a fresh owner cannot
    // replace a dead marker between the check and deletion. Unknown owners stay.
    private static boolean sweepMarker(Path marker) {
        try {
            if (!Files.exists(marker)) {
                return false;
            }
            var match = OWNER.matcher(Files.readString(marker));
            if (!match.matches()) {
                return false;
            }
            var process = ProcessHandle.of(Long.parseLong(match.group(1)));
            if (process.isPresent() && process.get().isAlive()) {
                var start = process.get().info().startInstant();
                if (match.group(2) == null || start.isEmpty()
                        || start.get().equals(Instant.parse(match.group(2)))) {
                    return false;
                }
            }
            return Files.deleteIfExists(marker);
        } catch (IOException | RuntimeException e) {
            com.opencode.ide.client.ClientLog.warning("cannot inspect reservation " + marker + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (released) {
            return;
        }
        // Cleanup still needs to run when the worker was interrupted at shutdown.
        boolean interrupted = Thread.interrupted();
        try {
            exclusive(repo, () -> {
                try {
                    if (Files.exists(marker) && ownership.equals(Files.readString(marker))) {
                        Files.delete(marker);
                    }
                    released = true;
                } catch (IOException e) {
                    throw new IllegalStateException("cannot release " + marker, e);
                }
                return null;
            });
        } catch (RuntimeException e) {
            com.opencode.ide.client.ClientLog.warning(e.getMessage());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
