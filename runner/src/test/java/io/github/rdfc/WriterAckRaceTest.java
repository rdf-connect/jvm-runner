package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import rdfc.Common.LocalAck;
import rdfc.RunnerGrpc;
import rdfc.Service.FromRunner;
import rdfc.Service.ToRunner;

/**
 * The Writer used to send the message first and only then remember which future
 * the acknowledgement completes. An orchestrator that acknowledged before the
 * send returned found nothing to complete, the acknowledgement was dropped and
 * the producer waited forever.
 */
class WriterAckRaceTest {
    private static final String CHANNEL = "http://example.org/channel/out";
    private static final Logger LOGGER = Logger.getLogger(WriterAckRaceTest.class.getName());

    /** Acknowledges every outgoing message from inside the send itself. */
    private static void ackWhileSending(FakeOrchestrator orchestrator, AtomicInteger acks) {
        orchestrator.whileSending(RunnerGrpc.getConnectMethod(), (FromRunner message) -> {
            if (!message.hasMsg()) {
                return;
            }

            var ack = LocalAck.newBuilder()
                    .setChannel(CHANNEL)
                    .setLocalSequenceNumber(acks.getAndIncrement());
            orchestrator.respond(RunnerGrpc.getConnectMethod(), ToRunner.newBuilder().setProcessed(ack).build());
        });
    }

    @Test
    void chunkCompletesWhenTheAckArrivesFromInsideTheSend() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = new Runner(FakeOrchestrator.stub(orchestrator), "http://example.org/runner/ack-race", () -> {
        });

        var writer = new Writer(CHANNEL, runner, LOGGER);
        runner.setWriter(CHANNEL, writer);

        var acks = new AtomicInteger();
        ackWhileSending(orchestrator, acks);

        // Repeated: an ordering bug that only shows up now and then would still be
        // caught, and the acknowledgement of chunk n may not linger into chunk n+1.
        for (int i = 0; i < 500; i++) {
            var sending = writer.chunk(ByteString.copyFromUtf8("chunk " + i));
            assertTrue(sending.isDone(), "chunk " + i + " was not completed by the acknowledgement");
            sending.get(5, TimeUnit.SECONDS);
        }

        assertEquals(500, acks.get());
    }

    @Test
    void chunkCompletesWhenTheAckArrivesFromAnotherThread() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = new Runner(FakeOrchestrator.stub(orchestrator), "http://example.org/runner/ack-race-threaded",
                () -> {
                });

        var writer = new Writer(CHANNEL, runner, LOGGER);
        runner.setWriter(CHANNEL, writer);

        // The acknowledgement is handed to another thread, so it can land before or
        // after chunk() returned.
        var acknowledger = Executors.newSingleThreadExecutor();
        var acks = new AtomicInteger();
        try {
            orchestrator.whileSending(RunnerGrpc.getConnectMethod(), (FromRunner message) -> {
                if (!message.hasMsg()) {
                    return;
                }

                var arrived = new CountDownLatch(1);
                acknowledger.execute(() -> {
                    var ack = LocalAck.newBuilder()
                            .setChannel(CHANNEL)
                            .setLocalSequenceNumber(acks.getAndIncrement());
                    orchestrator.respond(RunnerGrpc.getConnectMethod(),
                            ToRunner.newBuilder().setProcessed(ack).build());
                    arrived.countDown();
                });

                try {
                    // Give the other thread a real chance to win the race
                    arrived.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            for (int i = 0; i < 200; i++) {
                writer.chunk(ByteString.copyFromUtf8("chunk " + i)).get(5, TimeUnit.SECONDS);
            }
        } finally {
            acknowledger.shutdownNow();
        }

        assertEquals(200, acks.get());
    }
}
