package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.rdfc.RunnerObserver.Role;

/**
 * The state feeds {@code /api/state} and, through it, the dashboard ported from
 * the py-runner: the JSON shape asserted here is that runner's, field for
 * field.
 */
class StateTest {

    /** The one runner in a snapshot with a given id. */
    private static Map<String, Object> find(List<Map<String, Object>> snapshot, String id) {
        for (Map<String, Object> runner : snapshot) {
            if (id.equals(runner.get("id"))) {
                return runner;
            }
        }
        throw new AssertionError("No runner " + id + " in " + snapshot);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channels(Map<String, Object> runner) {
        return (Map<String, Object>) runner.get("channels");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channel(Map<String, Object> runner, String key) {
        var found = (Map<String, Object>) channels(runner).get(key);
        assertNotNull(found, "no channel " + key + " in " + channels(runner).keySet());
        return found;
    }

    @Test
    void handsOutMonotonicIds() {
        var state = new State(5);

        assertEquals("1", state.registerRunner("10.0.0.1", "urn:a"));
        assertEquals("2", state.registerRunner("10.0.0.2", "urn:b"));
        // Deregistering does not free the id: an id that came back would make two
        // different runs indistinguishable in the dashboard's history
        state.deregisterRunner("1");
        assertEquals("3", state.registerRunner("10.0.0.3", "urn:c"));
    }

    @Test
    void aFreshRunnerIsConnecting() {
        var state = new State(5);
        var id = state.registerRunner("10.0.0.1", "urn:a");

        var runner = find(state.snapshot(), id);
        assertEquals("connecting", runner.get("status"));
        assertEquals("IDLE", runner.get("grpcState"));
        assertEquals("10.0.0.1", runner.get("host"));
        assertEquals("urn:a", runner.get("uri"));
        assertNotNull(runner.get("connectedAt"));
        assertNull(runner.get("disconnectedAt"), "it has not disconnected yet");
        assertTrue(channels(runner).isEmpty());
        assertFalse(runner.containsKey("error"), "no failure, no error field");
    }

    @Test
    void followsTheStatusTransitions() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");

        state.setStatus(id, State.Status.RUNNING);
        assertEquals("running", find(state.snapshot(), id).get("status"));

        state.setGrpcState(id, "READY");
        assertEquals("READY", find(state.snapshot(), id).get("grpcState"));

        state.setStatus(id, State.Status.DONE);
        assertEquals("done", find(state.snapshot(), id).get("status"));
    }

    @Test
    void aFinishedRunnerIsDone() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");
        state.setStatus(id, State.Status.RUNNING);

        state.deregisterRunner(id);

        var runner = find(state.snapshot(), id);
        assertEquals("done", runner.get("status"));
        assertNotNull(runner.get("disconnectedAt"));
    }

    /**
     * A failure survives the disconnection. Overwriting it with "done" on the way
     * out would hide exactly the run somebody opens the dashboard for.
     */
    @Test
    void aFailedRunnerStaysFailed() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");
        state.markError(id, "the processor threw");

        state.deregisterRunner(id);

        var runner = find(state.snapshot(), id);
        assertEquals("error", runner.get("status"));
        assertEquals("the processor threw", runner.get("error"));
    }

    @Test
    void trimsTheHistoryToItsSize() {
        var state = new State(2);
        for (int i = 0; i < 5; i++) {
            state.deregisterRunner(state.registerRunner("host", "urn:" + i));
        }

        var snapshot = state.snapshot();
        assertEquals(2, snapshot.size());
        // Newest first: the dashboard shows the run that just ended at the top
        assertEquals("urn:4", snapshot.get(0).get("uri"));
        assertEquals("urn:3", snapshot.get(1).get("uri"));
    }

    @Test
    void keepsEverythingWhenTheHistoryIsUnbounded() {
        var state = new State(-1);
        for (int i = 0; i < 20; i++) {
            state.deregisterRunner(state.registerRunner("host", "urn:" + i));
        }

        assertEquals(20, state.snapshot().size());
    }

    @Test
    void keepsNothingWhenTheHistoryIsZero() {
        var state = new State(0);
        state.deregisterRunner(state.registerRunner("host", "urn:a"));

        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void showsTheRunningRunnersBeforeTheHistory() {
        var state = new State(5);
        var finished = state.registerRunner("host", "urn:finished");
        state.deregisterRunner(finished);
        state.registerRunner("host", "urn:running");

        var snapshot = state.snapshot();
        assertEquals("urn:running", snapshot.get(0).get("uri"));
        assertEquals("urn:finished", snapshot.get(1).get("uri"));
    }

    @Test
    void accumulatesMessagesPerChannel() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");

        state.recordMessage(id, "urn:channel", Role.WRITER, 10);
        state.recordMessage(id, "urn:channel", Role.WRITER, 32);

        var stats = channel(find(state.snapshot(), id), "writer:urn:channel");
        assertEquals(2L, stats.get("messageCount"));
        assertEquals(42L, stats.get("bytesTotal"));
        assertEquals("urn:channel", stats.get("uri"));
        assertEquals("writer", stats.get("role"));
        assertNotNull(stats.get("lastMessageAt"));
    }

    /**
     * One channel URI can be read and written inside the same runner — a processor
     * feeding another one in the same pipeline — so the role is part of the key.
     * Keying on the URI alone would sum both directions into one record.
     */
    @Test
    void keepsTheTwoDirectionsOfOneChannelApart() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");

        state.recordMessage(id, "urn:channel", Role.READER, 1);
        state.recordMessage(id, "urn:channel", Role.WRITER, 2);
        state.recordMessage(id, "urn:channel", Role.WRITER, 3);

        var runner = find(state.snapshot(), id);
        assertEquals(2, channels(runner).size());
        assertEquals(1L, channel(runner, "reader:urn:channel").get("messageCount"));
        assertEquals(2L, channel(runner, "writer:urn:channel").get("messageCount"));
        assertEquals(5L, channel(runner, "writer:urn:channel").get("bytesTotal"));
    }

    @Test
    void tracksAChannelBeforeItCarriesAnything() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");

        state.trackChannel(id, "urn:channel", Role.READER);

        var stats = channel(find(state.snapshot(), id), "reader:urn:channel");
        assertEquals(0L, stats.get("messageCount"));
        assertNull(stats.get("lastMessageAt"), "it has been quiet so far");
    }

    @Test
    void untracksAChannelAgain() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");
        state.trackChannel(id, "urn:channel", Role.READER);

        state.untrackChannel(id, "urn:channel", Role.READER);

        assertTrue(channels(find(state.snapshot(), id)).isEmpty());
    }

    @Test
    void boundsTheLatencySamples() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");

        for (int i = 0; i < State.MAX_LATENCY_SAMPLES + 10; i++) {
            state.recordMessage(id, "urn:channel", Role.WRITER, 1, (double) i);
        }

        @SuppressWarnings("unchecked")
        var latencies = (List<Double>) channel(find(state.snapshot(), id), "writer:urn:channel").get("latenciesMs");
        assertEquals(State.MAX_LATENCY_SAMPLES, latencies.size());
        // The oldest ones went, so what is left describes what is happening now
        assertEquals(10.0, latencies.get(0).doubleValue());
    }

    /**
     * A snapshot is handed to a JSON serializer while the runners it describes go
     * on running, so it may not share a single mutable structure with them.
     */
    @Test
    void aSnapshotIsADeepCopy() {
        var state = new State(5);
        var id = state.registerRunner("host", "urn:a");
        state.recordMessage(id, "urn:channel", Role.WRITER, 10);

        var snapshot = state.snapshot();
        find(snapshot, id).put("status", "tampered");
        channels(find(snapshot, id)).clear();
        snapshot.clear();

        var fresh = state.snapshot();
        assertEquals(1, fresh.size());
        assertEquals("connecting", find(fresh, id).get("status"));
        assertEquals(10L, channel(find(fresh, id), "writer:urn:channel").get("bytesTotal"));
    }

    @Test
    void ignoresAnUnknownRunner() {
        var state = new State(5);

        // Every one of these can arrive for a runner that was just deregistered, on
        // a gRPC callback thread that did not know yet
        state.setStatus("404", State.Status.RUNNING);
        state.setGrpcState("404", "READY");
        state.markError("404");
        state.trackChannel("404", "urn:channel", Role.READER);
        state.untrackChannel("404", "urn:channel", Role.READER);
        state.recordMessage("404", "urn:channel", Role.READER, 1);
        state.deregisterRunner("404");

        assertTrue(state.snapshot().isEmpty());
    }
}
