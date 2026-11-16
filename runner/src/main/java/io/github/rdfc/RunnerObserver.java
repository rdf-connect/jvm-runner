package io.github.rdfc;

import java.util.Locale;

/**
 * Watches the traffic a {@link Runner} carries.
 *
 * The only reason this exists is the server's dashboard: it shows, per running
 * runner, which channels moved how many messages and how many bytes. The runner
 * itself has no interest in any of that, so it is kept to one method and one
 * no-op implementation — the CLI passes {@link #NOOP} and pays nothing.
 *
 * <b>Implementations must not throw and must not block.</b> This is called from
 * the gRPC callback threads and from whatever thread a processor produces on;
 * an exception there takes a connection down, and a slow observer slows the
 * pipeline it is watching. The runner guards the calls anyway, but an observer
 * that relies on that guard is one that reports nothing.
 */
public interface RunnerObserver {
    /**
     * Which end of a channel a runner is on.
     *
     * The same channel IRI can appear on both ends inside one runner — a
     * processor feeding another one in the same pipeline — so the role is part
     * of how the statistics are keyed. Without it the two directions would be
     * merged into one record with the counts summed over both.
     */
    enum Role {
        READER,
        WRITER;

        /**
         * @return the name the dashboard and the other runners use, lowercase
         */
        public String wire() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A message went over a channel.
     *
     * @param channelUri the channel it went over
     * @param role       whether this runner read it or wrote it
     * @param bytes      the size of its payload
     */
    void onMessage(String channelUri, Role role, int bytes);

    /** An observer that watches nothing, for everything outside server mode. */
    RunnerObserver NOOP = (channelUri, role, bytes) -> {
    };
}
