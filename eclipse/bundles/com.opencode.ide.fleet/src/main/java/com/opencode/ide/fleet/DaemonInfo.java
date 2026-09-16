package com.opencode.ide.fleet;

import java.time.Instant;

/**
 * The fleet daemon's published identity: the payload of the pidfile
 * ({@code <repo>/.git/opencode-fleet/daemon.json}) and what
 * {@link FleetDaemon#probe} returns for a live daemon. This is the
 * discovery+auth surface for clients - a proxy (or the pwsh launcher)
 * answers "is there a daemon for this repo?" with one file read plus one
 * {@link ProcessHandle} check, then authenticates the TCP connection with
 * {@code token} via the {@code daemon/hello} handshake.
 *
 * <p>{@code startedAt} is the daemon PROCESS's start instant (the
 * pid-reuse anchor checked against {@link ProcessHandle#info}, exactly
 * like {@code DispatchGuard}'s OWNER markers), not the moment the listener
 * bound - so liveness survives pidfile re-reads while a recycled pid does
 * not pass.</p>
 *
 * <p>{@link #toString()} deliberately hides the token: pidfile hygiene is
 * "never logged, never in an exception message" (same rule as
 * {@code FleetControl.resolvePassword}), and records print all components
 * by default.</p>
 *
 * @param port       the loopback TCP port the daemon serves
 * @param pid        the daemon process id
 * @param token      the shared daemon password (env {@code FLEET_DAEMON_PASSWORD}
 *                   when set, else generated 64-hex)
 * @param startedAt  the daemon process's start instant
 */
public record DaemonInfo(int port, long pid, String token, Instant startedAt) {

    @Override
    public String toString() {
        return "DaemonInfo[port=" + port + ", pid=" + pid
                + ", startedAt=" + startedAt + ", token=\u2026hidden]";
    }
}
