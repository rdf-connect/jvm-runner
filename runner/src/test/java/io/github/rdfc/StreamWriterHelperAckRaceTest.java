package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import io.github.rdfc.helpers.StreamWriterHelper;
import rdfc.Common.ReceivingStreamControl;
import rdfc.Common.StreamChunk;
import rdfc.RunnerGrpc;

/**
 * The same race as in the Writer, but for a streaming message: the helper sent
 * the chunk before remembering which future the answering
 * ReceivingStreamControl completes.
 */
class StreamWriterHelperAckRaceTest {
    private static final String CHANNEL = "http://example.org/channel/stream";
    private static final String RUNNER = "http://example.org/runner/stream";
    private static final Logger LOGGER = Logger.getLogger(StreamWriterHelperAckRaceTest.class.getName());

    @Test
    void chunkCompletesWhenTheControlArrivesFromInsideTheSend() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var controls = new AtomicInteger();

        // Answer every outgoing chunk from inside the send itself, so the control
        // lands before chunk() returns
        orchestrator.whileSending(RunnerGrpc.getSendStreamMessageMethod(), (StreamChunk chunk) -> {
            var control = ReceivingStreamControl.newBuilder().setStreamSequenceNumber(controls.getAndIncrement());
            orchestrator.respondOnThisThread(RunnerGrpc.getSendStreamMessageMethod(), control.build());
        });

        var acknowledged = new CompletableFuture<Void>();
        var building = StreamWriterHelper.build(FakeOrchestrator.stub(orchestrator), CHANNEL, RUNNER, acknowledged,
                LOGGER);

        // The control answering the identify arrived before build() returned
        assertTrue(building.isDone(), "the stream was not built by the answer to its identify");
        var stream = building.get(5, TimeUnit.SECONDS);

        for (int i = 0; i < 500; i++) {
            var sending = stream.chunk(ByteString.copyFromUtf8("chunk " + i));
            assertTrue(sending.isDone(), "chunk " + i + " was not completed by its stream control");
            sending.get(5, TimeUnit.SECONDS);
        }

        // one identify plus every chunk
        assertEquals(501, orchestrator.sentOn(RunnerGrpc.getSendStreamMessageMethod()).size());
        assertEquals(501, controls.get());
    }

    @Test
    void closeCompletesOnTheAcknowledgement() throws Exception {
        var orchestrator = new FakeOrchestrator();
        // From the delivery thread, the way a real transport answers
        orchestrator.whileSending(RunnerGrpc.getSendStreamMessageMethod(), (StreamChunk chunk) -> {
            orchestrator.respondLater(RunnerGrpc.getSendStreamMessageMethod(),
                    ReceivingStreamControl.newBuilder().build());
        });

        var acknowledged = new CompletableFuture<Void>();
        var stream = StreamWriterHelper
                .build(FakeOrchestrator.stub(orchestrator), CHANNEL, RUNNER, acknowledged, LOGGER)
                .get(5, TimeUnit.SECONDS);

        var closing = stream.close();
        assertFalse(closing.isDone(), "close may only complete once the message itself is acknowledged");

        acknowledged.complete(null);
        closing.get(5, TimeUnit.SECONDS);
    }

    /**
     * A stream that breaks leaves a producer waiting for a control message that is
     * never coming, and a close waiting for an acknowledgement that is never
     * coming. Both have to be failed instead — and the failure may not escape the
     * gRPC callback, which used to raise an UnsupportedOperationException.
     */
    @Test
    void aBrokenStreamFailsEverybodyWaitingOnIt() throws Exception {
        var orchestrator = new FakeOrchestrator();
        // Answer the identify only, so the chunk below stays outstanding
        orchestrator.whileSending(RunnerGrpc.getSendStreamMessageMethod(), (StreamChunk chunk) -> {
            if (chunk.hasId()) {
                orchestrator.respondLater(RunnerGrpc.getSendStreamMessageMethod(),
                        ReceivingStreamControl.newBuilder().build());
            }
        });

        var acknowledged = new CompletableFuture<Void>();
        var stream = StreamWriterHelper
                .build(FakeOrchestrator.stub(orchestrator), CHANNEL, RUNNER, acknowledged, LOGGER)
                .get(5, TimeUnit.SECONDS);

        var sending = stream.chunk(ByteString.copyFromUtf8("payload"));
        var closing = stream.close();
        assertFalse(sending.isDone());
        assertFalse(closing.isDone());

        assertDoesNotThrow(
                () -> orchestrator.fail(RunnerGrpc.getSendStreamMessageMethod(), "the stream message dropped"));

        var failed = assertThrows(ExecutionException.class, () -> sending.get(5, TimeUnit.SECONDS),
                "the producer is still waiting for a control message on a dead stream");
        assertTrue(failed.getCause().getMessage().contains("the stream message dropped"),
                "the chunk did not fail with the cause: " + failed.getCause());

        assertThrows(ExecutionException.class, () -> closing.get(5, TimeUnit.SECONDS),
                "the close is still waiting for an acknowledgement that is not coming");
    }
}
