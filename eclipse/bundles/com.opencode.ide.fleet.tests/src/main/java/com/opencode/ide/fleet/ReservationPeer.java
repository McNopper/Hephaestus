package com.opencode.ide.fleet;

import java.nio.file.Path;

/** Child JVM entry point; stdout/stdin are the test's synchronization protocol. */
public final class ReservationPeer {
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]);
        System.out.println("READY");
        System.out.flush();
        System.in.read();
        DispatchGuard guard;
        try {
            guard = DispatchGuard.admit(repo, 1, () -> DispatchGuard.acquire(repo, args[1]));
        } catch (IllegalStateException e) {
            System.out.println("REFUSED");
            System.out.flush();
            return;
        }
        try (guard) {
            System.out.println("ACQUIRED");
            System.out.flush();
            System.in.read();
        }
    }
}
