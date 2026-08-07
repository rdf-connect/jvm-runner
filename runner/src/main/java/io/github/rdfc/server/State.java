package io.github.rdfc.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.rdfc.RunnerObserver.Role;

/**
 * What the runners served by this process are doing, for {@code /api/state} and
 * the dashboard.
 *
 * One instance per server, shared by every connection, so every method is
 * {@code synchronized}: registrations come from the accept loop, status changes
 * and message statistics from gRPC callback threads, and snapshots from the
 * HTTP threads.
 *
 * A runner that finishes is not forgotten straight away — it moves to a history
 * list, newest first, trimmed to the configured size. Without that the
 * dashboard would go blank the moment a pipeline completes, which is exactly
 * the moment somebody wants to look at what it did.
 *
 * The JSON shape {@link #snapshot()} produces is the one the py-runner's
 * {@code /api/state} produces, field for field, so the dashboard ported from it
 * works unchanged.
 */
public final class State {
    /** How far a runner got. */
    public enum Status {
        /** Accepted, not yet running processors. */
        CONNECTING,
        /** Executing its pipeline. */
        RUNNING,
        /** Finished its work. */
        DONE,
        /** Ended badly. */
        ERROR;

        /** @return the name the dashboard reads, lowercase */
        public String wire() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * How many round-trip times are kept per channel.
     *
     * Enough for a stable median and a rough 99th percentile, small enough that a
     * pipeline running for hours does not grow this without bound.
     */
    static final int MAX_LATENCY_SAMPLES = 100;

    /** The gRPC state a connection starts in, before anything is watched. */
    static final String INITIAL_GRPC_STATE = "IDLE";

    private final Map<String, RunnerStats> runners = new LinkedHashMap<>();

    /** Finished runners, newest first. */
    private final List<RunnerStats> history = new ArrayList<>();

    private final int historySize;

    private int nextId = 1;

    /**
     * @param historySize how many finished runners to keep, -1 for all of them
     */
    public State(int historySize) {
        this.historySize = Math.max(-1, historySize);
    }

    /** Uses the default history size. */
    public State() {
        this(ServerConfig.DEFAULT_HISTORY_SIZE);
    }

    /**
     * A new orchestrator connection arrived.
     *
     * @param host the peer it came from
     * @param uri  the runner IRI it asked for
     * @return the id everything else in here is keyed on
     */
    public synchronized String registerRunner(String host, String uri) {
        String id = Integer.toString(this.nextId++);
        this.runners.put(id, new RunnerStats(id, host, uri, now()));
        return id;
    }

    /**
     * A runner is gone: move it to the history and trim that back.
     *
     * A runner that was never marked as failed counts as done, whatever it was
     * doing when the connection ended — the connection ending <em>is</em> how a
     * successful run ends.
     *
     * @param id of the runner
     */
    public synchronized void deregisterRunner(String id) {
        RunnerStats runner = this.runners.remove(id);
        if (runner == null) {
            return;
        }

        runner.disconnectedAt = now();
        if (runner.status != Status.ERROR) {
            runner.status = Status.DONE;
        }

        this.history.add(0, runner);
        if (this.historySize >= 0) {
            while (this.history.size() > this.historySize) {
                this.history.remove(this.history.size() - 1);
            }
        }
    }

    /**
     * @param id     of the runner
     * @param status what it is doing now
     */
    public synchronized void setStatus(String id, Status status) {
        RunnerStats runner = this.runners.get(id);
        if (runner != null) {
            runner.status = status;
        }
    }

    /**
     * @param id        of the runner
     * @param grpcState the name of its channel's connectivity state
     */
    public synchronized void setGrpcState(String id, String grpcState) {
        RunnerStats runner = this.runners.get(id);
        if (runner != null) {
            runner.grpcState = grpcState;
        }
    }

    /**
     * A runner failed.
     *
     * @param id of the runner
     */
    public synchronized void markError(String id) {
        this.markError(id, null);
    }

    /**
     * A runner failed, with something to say about it.
     *
     * The message is only put in the snapshot when there is one, so a state
     * without failures serializes to exactly what the py-runner serializes.
     *
     * @param id      of the runner
     * @param message what went wrong, may be null
     */
    public synchronized void markError(String id, String message) {
        RunnerStats runner = this.runners.get(id);
        if (runner != null) {
            runner.status = Status.ERROR;
            if (message != null) {
                runner.error = message;
            }
        }
    }

    /**
     * Starts counting a channel, before it has carried anything.
     *
     * Called when a channel is registered, so the dashboard shows a channel that
     * exists but has been quiet so far — which is a different thing from a channel
     * that is not there at all.
     *
     * @param id   of the runner
     * @param uri  of the channel
     * @param role which end of it this runner is on
     */
    public synchronized void trackChannel(String id, String uri, Role role) {
        RunnerStats runner = this.runners.get(id);
        if (runner != null) {
            channel(runner, uri, role);
        }
    }

    /**
     * Stops counting a channel, e.g. when its registration is rolled back after a
     * processor failed to initialize.
     *
     * @param id   of the runner
     * @param uri  of the channel
     * @param role which end of it this runner is on
     */
    public synchronized void untrackChannel(String id, String uri, Role role) {
        RunnerStats runner = this.runners.get(id);
        if (runner != null) {
            runner.channels.remove(key(uri, role));
        }
    }

    /**
     * A message went over a channel.
     *
     * @param id    of the runner
     * @param uri   of the channel
     * @param role  which end of it this runner is on
     * @param bytes the size of the payload
     */
    public synchronized void recordMessage(String id, String uri, Role role, int bytes) {
        this.recordMessage(id, uri, role, bytes, null);
    }

    /**
     * A message went over a channel, and took a measurable time to be
     * acknowledged.
     *
     * @param id        of the runner
     * @param uri       of the channel
     * @param role      which end of it this runner is on
     * @param bytes     the size of the payload
     * @param latencyMs how long the acknowledgement took, or null when nothing was
     *                  waited for
     */
    public synchronized void recordMessage(String id, String uri, Role role, int bytes, Double latencyMs) {
        RunnerStats runner = this.runners.get(id);
        if (runner == null) {
            return;
        }

        ChannelStats stats = channel(runner, uri, role);
        stats.messageCount++;
        stats.bytesTotal += bytes;
        stats.lastMessageAt = now();

        if (latencyMs != null) {
            stats.latenciesMs.add(latencyMs);
            if (stats.latenciesMs.size() > MAX_LATENCY_SAMPLES) {
                stats.latenciesMs.remove(0);
            }
        }
    }

    /**
     * Everything this state knows, as plain maps and lists ready for Jackson.
     *
     * Running runners first, in the order they connected, then the history newest
     * first. Every map and list is freshly built, so whoever gets a snapshot can
     * hold onto it, walk it, or sort it while the runners it describes keep
     * changing.
     *
     * @return one map per runner
     */
    public synchronized List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>(this.runners.size() + this.history.size());
        for (RunnerStats runner : this.runners.values()) {
            out.add(runner.toJson());
        }
        for (RunnerStats runner : this.history) {
            out.add(runner.toJson());
        }
        return out;
    }

    /**
     * How many finished runners are being kept. Visible for testing.
     *
     * @return the size of the history
     */
    synchronized int historyLength() {
        return this.history.size();
    }

    /**
     * The statistics of one channel of one runner, created on first use.
     *
     * @param runner the runner
     * @param uri    of the channel
     * @param role   which end of it the runner is on
     * @return the entry
     */
    private static ChannelStats channel(RunnerStats runner, String uri, Role role) {
        return runner.channels.computeIfAbsent(key(uri, role), ignored -> new ChannelStats(uri, role));
    }

    /**
     * The key a channel's statistics live under.
     *
     * @param uri  of the channel
     * @param role which end of it the runner is on
     * @return {@code <role>:<uri>}
     */
    private static String key(String uri, Role role) {
        return role.wire() + ":" + uri;
    }

    /** @return now, in milliseconds since the epoch */
    private static long now() {
        return System.currentTimeMillis();
    }

    /** One runner, from the moment it connected until it falls off the history. */
    private static final class RunnerStats {
        private final String id;
        private final String host;
        private final String uri;
        private final long connectedAt;
        private Long disconnectedAt;
        private Status status = Status.CONNECTING;
        private String grpcState = INITIAL_GRPC_STATE;
        private String error;

        /** Keyed {@code <role>:<uri>}; one channel can appear once per role. */
        private final Map<String, ChannelStats> channels = new LinkedHashMap<>();

        RunnerStats(String id, String host, String uri, long connectedAt) {
            this.id = id;
            this.host = host;
            this.uri = uri;
            this.connectedAt = connectedAt;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", this.id);
            json.put("host", this.host);
            json.put("uri", this.uri);
            json.put("connectedAt", this.connectedAt);
            json.put("disconnectedAt", this.disconnectedAt);
            json.put("status", this.status.wire());
            json.put("grpcState", this.grpcState);

            Map<String, Object> channels = new LinkedHashMap<>();
            for (Map.Entry<String, ChannelStats> entry : this.channels.entrySet()) {
                channels.put(entry.getKey(), entry.getValue().toJson());
            }
            json.put("channels", channels);

            if (this.error != null) {
                json.put("error", this.error);
            }
            return json;
        }
    }

    /** One end of one channel of one runner. */
    private static final class ChannelStats {
        private final String uri;
        private final Role role;
        private long messageCount;
        private long bytesTotal;
        private Long lastMessageAt;
        private final List<Double> latenciesMs = new ArrayList<>();

        ChannelStats(String uri, Role role) {
            this.uri = uri;
            this.role = role;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("uri", this.uri);
            json.put("role", this.role.wire());
            json.put("messageCount", this.messageCount);
            json.put("bytesTotal", this.bytesTotal);
            json.put("lastMessageAt", this.lastMessageAt);
            json.put("latenciesMs", new ArrayList<>(this.latenciesMs));
            return json;
        }
    }
}
