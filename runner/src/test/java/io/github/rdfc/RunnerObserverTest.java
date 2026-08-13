package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import rdfc.Common.ReceivingMessage;
import rdfc.Service.ToRunner;

/**
 * The observer is how the server's dashboard learns what a runner carries. It
 * hangs off the two seams every normal message goes through, and it may never
 * be able to hurt the runner it watches.
 */
class RunnerObserverTest {
    private static final String CHANNEL = "http://example.org/channel/1";

    /** One {@code onMessage} call, as a string, so it reads in an assertion. */
    private static final class Recorder implements RunnerObserver {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(String channelUri, Role role, int bytes) {
            this.seen.add(role.wire() + " " + channelUri + " " + bytes);
        }
    }

    private static ToRunner msg(String channel, String payload) {
        return ToRunner.newBuilder()
                .setMsg(ReceivingMessage.newBuilder()
                        .setChannel(channel)
                        .setData(ByteString.copyFromUtf8(payload))
                        .setGlobalSequenceNumber(1))
                .build();
    }

    private static Runner runner(FakeOrchestrator orchestrator, RunnerObserver observer) {
        return new Runner(FakeOrchestrator.stub(orchestrator), "http://example.org/runner/observed",
                () -> {
                }, observer);
    }

    @Test
    void countsWhatAProcessorReceives() {
        var orchestrator = new FakeOrchestrator();
        var recorder = new Recorder();
        var runner = runner(orchestrator, recorder);
        runner.setReader(CHANNEL, new Reader(CHANNEL, Logger.getLogger("test")));

        runner.onNext(msg(CHANNEL, "hello"));

        assertEquals(List.of("reader " + CHANNEL + " 5"), recorder.seen);
    }

    /**
     * A message for a channel nobody reads is dropped, so counting it would show
     * traffic on a channel that has no reader.
     */
    @Test
    void countsNothingForAChannelWithoutAReader() {
        var orchestrator = new FakeOrchestrator();
        var recorder = new Recorder();
        var runner = runner(orchestrator, recorder);

        runner.onNext(msg(CHANNEL, "hello"));

        assertTrue(recorder.seen.isEmpty());
    }

    @Test
    void countsWhatAProcessorSends() {
        var orchestrator = new FakeOrchestrator();
        var recorder = new Recorder();
        var runner = runner(orchestrator, recorder);
        var writer = new Writer(CHANNEL, runner, Logger.getLogger("test"));

        writer.chunk(ByteString.copyFromUtf8("a longer payload"));

        assertEquals(List.of("writer " + CHANNEL + " 16"), recorder.seen);
    }

    /**
     * The observer runs on the gRPC callback threads. One that throws may not take
     * the connection down — in server mode that would be one orchestrator's
     * pipeline killed by another one's dashboard.
     */
    @Test
    void survivesAnObserverThatThrows() {
        var orchestrator = new FakeOrchestrator();
        var runner = runner(orchestrator, (channelUri, role, bytes) -> {
            throw new IllegalStateException("the dashboard is on fire");
        });
        runner.setReader(CHANNEL, new Reader(CHANNEL, Logger.getLogger("test")));
        var writer = new Writer(CHANNEL, runner, Logger.getLogger("test"));

        runner.onNext(msg(CHANNEL, "hello"));
        writer.chunk(ByteString.copyFromUtf8("hello"));

        // Both went through: the ack for the incoming message and the outgoing message
        // itself are on the wire
        var sent = orchestrator.sentOn(rdfc.RunnerGrpc.getConnectMethod());
        assertTrue(sent.stream().anyMatch(m -> m.hasProcessed()), "the incoming message was acknowledged");
        assertTrue(sent.stream().anyMatch(m -> m.hasMsg()), "the outgoing message was sent");
    }

    /** The CLI passes no observer at all and nothing changes for it. */
    @Test
    void theDefaultObserverWatchesNothing() {
        var orchestrator = new FakeOrchestrator();
        var runner = new Runner(FakeOrchestrator.stub(orchestrator), "http://example.org/runner/plain", () -> {
        });
        var writer = new Writer(CHANNEL, runner, Logger.getLogger("test"));

        writer.chunk(ByteString.copyFromUtf8("hello"));

        assertTrue(orchestrator.sentOn(rdfc.RunnerGrpc.getConnectMethod()).stream().anyMatch(m -> m.hasMsg()));
    }
}
