package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static ToRunner ack(int sequenceNumber) {
        return ToRunner.newBuilder()
                .setProcessed(LocalAck.newBuilder().setChannel(CHANNEL).setLocalSequenceNumber(sequenceNumber))
                .build();
    }

    private static Writer writerOn(FakeOrchestrator orchestrator, String runnerUri) {
        var runner = new Runner(FakeOrchestrator.stub(orchestrator), runnerUri, () -> {
        });

        var writer = new Writer(CHANNEL, runner, LOGGER);
        runner.setWriter(CHANNEL, writer);
        return writer;
    }

    /**
     * The regression itself: the acknowledgement lands on the sending thread,
     * before chunk() ever returns. Only a Writer that installed its future before
     * sending can still complete it.
     */
    @Test
    void chunkCompletesWhenTheAckArrivesFromInsideTheSend() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var writer = writerOn(orchestrator, "http://example.org/runner/ack-race");

        var acks = new AtomicInteger();
        orchestrator.whileSending(RunnerGrpc.getConnectMethod(), (FromRunner message) -> {
            if (!message.hasMsg()) {
                return;
            }
            orchestrator.respondOnThisThread(RunnerGrpc.getConnectMethod(), ack(acks.getAndIncrement()));
        });

        // Repeated: an ordering bug that only shows up now and then would still be
        // caught, and the acknowledgement of chunk n may not linger into chunk n+1.
        for (int i = 0; i < 500; i++) {
            var sending = writer.chunk(ByteString.copyFromUtf8("chunk " + i));
            assertTrue(sending.isDone(), "chunk " + i + " was not completed by the acknowledgement");
            sending.get(5, TimeUnit.SECONDS);
        }

        assertEquals(500, acks.get());
    }

    /**
     * The realistic shape: the acknowledgement is handed to the delivery thread
     * while the send is still running, so it can land before or after chunk()
     * returned. Neither may lose it.
     */
    @Test
    void chunkCompletesWhenTheAckArrivesFromTheDeliveryThread() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var writer = writerOn(orchestrator, "http://example.org/runner/ack-race-threaded");

        var acks = new AtomicInteger();
        orchestrator.whileSending(RunnerGrpc.getConnectMethod(), (FromRunner message) -> {
            if (!message.hasMsg()) {
                return;
            }
            // Not respond(): this hook runs while the runner holds the lock on the
            // outgoing stream, so it may not wait for the delivery.
            orchestrator.respondLater(RunnerGrpc.getConnectMethod(), ack(acks.getAndIncrement()));
        });

        for (int i = 0; i < 200; i++) {
            writer.chunk(ByteString.copyFromUtf8("chunk " + i)).get(5, TimeUnit.SECONDS);
        }

        assertEquals(200, acks.get());
    }

    /** The plain case, an acknowledgement long after the send returned. */
    @Test
    void chunkCompletesWhenTheAckArrivesAfterTheSendReturned() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var writer = writerOn(orchestrator, "http://example.org/runner/ack-race-late");

        var sending = writer.chunk(ByteString.copyFromUtf8("chunk"));
        assertFalse(sending.isDone(), "the chunk completed before it was acknowledged");

        orchestrator.respond(RunnerGrpc.getConnectMethod(), ack(0));
        sending.get(5, TimeUnit.SECONDS);
    }
}
