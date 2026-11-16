package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;

import rdfc.Common.GlobalAck;
import rdfc.Common.ReceivingStreamMessage;
import rdfc.RunnerGrpc;
import rdfc.Service.FromRunner;
import rdfc.Service.ToRunner;

/**
 * What has to happen when the orchestrator goes away.
 *
 * In server mode a dropped connection is an everyday event: it ends one runner
 * and has to leave the JVM, and every other connection in it, alone. So nothing
 * may throw out of a gRPC callback, everything the runner held has to be
 * released, and whoever runs that connection has to be told — that is what
 * {@link Runner#completion()} is for.
 */
class RunnerTeardownTest {
    private static final String PROC = "http://example.org/processor/1";
    private static final Logger LOGGER = Logger.getLogger(RunnerTeardownTest.class.getName());

    private static ToRunner proc(String uri) {
        return ToRunner.newBuilder()
                .setProc(rdfc.Service.Processor.newBuilder().setUri(uri).setConfig("{}").setArguments("{}"))
                .build();
    }

    private static ToRunner start() {
        return ToRunner.newBuilder().setStart(Empty.getDefaultInstance()).build();
    }

    private static List<GlobalAck> acks(FakeOrchestrator orchestrator) {
        return orchestrator.sentOn(RunnerGrpc.getConnectMethod()).stream()
                .filter(FromRunner::hasProcessed)
                .map(FromRunner::getProcessed)
                .collect(Collectors.toList());
    }

    /**
     * The failure the completion future carries, or a test failure when it
     * completed normally or not at all.
     */
    private static Throwable failureOf(Runner runner) {
        var failure = assertThrows(ExecutionException.class,
                () -> runner.completion().get(5, TimeUnit.SECONDS),
                "the runner was never told its connection ended");
        return failure.getCause();
    }

    /**
     * A runner halfway through its work: one processor initialized, its transform
     * and produce still running.
     */
    private static StubProcessor runningProcessor(FakeOrchestrator orchestrator, TestRunner runner) {
        var processor = runner.register(PROC, new StubProcessor());

        runner.onNext(proc(PROC));
        runner.onNext(start());
        processor.init.complete(null);

        assertEquals(1, processor.transformCalls.get());
        assertEquals(1, processor.produceCalls.get());
        assertEquals(2, runner.awaiting());

        return processor;
    }

    @Test
    void aBrokenConnectionTearsTheRunnerDown() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-error");
        var processor = runningProcessor(orchestrator, runner);

        // A gRPC callback that throws takes the whole connection (and in the CLI,
        // the JVM) down with it — this used to be an UnsupportedOperationException
        assertDoesNotThrow(() -> orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped"));

        var failure = failureOf(runner);
        assertTrue(failure.getMessage().contains("connection dropped"),
                "the completion did not carry the connection failure: " + failure);

        assertEquals(1, runner.completions.get(), "the runner has to be cleaned up exactly once");

        // Nothing is waiting for these two anymore: without that, a processor
        // blocked on a channel that is never going to deliver keeps this runner
        // alive forever
        assertTrue(processor.transform.isCancelled(), "the pending transform was left in flight");
        assertTrue(processor.produce.isCancelled(), "the pending produce was left in flight");
    }

    /** An init that never finished may not be left pending either. */
    @Test
    void aBrokenConnectionFailsAPendingInit() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-pending-init");
        var processor = runner.register(PROC, new StubProcessor());

        runner.onNext(proc(PROC));
        assertEquals(1, processor.initCalls.get());
        assertEquals(0, processor.transformCalls.get());

        orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped");

        failureOf(runner);
        assertTrue(processor.init.isCancelled(), "the pending init was left in flight");
        assertEquals(1, runner.completions.get());
    }

    /**
     * The consumers a processor registered have to see an end of stream, otherwise
     * a processor waiting for the end of its input never returns.
     */
    @Test
    void theTeardownClosesTheReaders() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-readers");

        var channel = "http://example.org/channel/in";
        var reader = new Reader(channel, LOGGER);
        runner.setReader(channel, reader);
        var ended = reader.buffers().on(buffer -> {
        });

        assertFalse(ended.isDone());

        orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped");

        assertTrue(ended.isDone(), "the reader was not closed, its consumer waits for data that is never coming");
    }

    /**
     * A producer waiting for the acknowledgement of the chunk it just sent is
     * waiting for a message from an orchestrator that is gone.
     */
    @Test
    void theTeardownFailsAWaitingProducer() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-writers");

        var channel = "http://example.org/channel/out";
        var writer = new Writer(channel, runner, LOGGER);
        runner.setWriter(channel, writer);

        var sending = writer.chunk(ByteString.copyFromUtf8("payload"));
        assertFalse(sending.isDone(), "a chunk is only handled once it is acknowledged");

        orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped");

        assertTrue(sending.isCompletedExceptionally(), "the producer is still waiting for an acknowledgement");
    }

    /**
     * The orchestrator closing the stream before this runner finished means the
     * work was cut short. That is a failure, not a clean end: whoever waits on the
     * completion has to be able to tell those two apart, and the js-runner draws
     * the same line.
     */
    @Test
    void anOrderlyCloseBeforeTheEndIsAFailure() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-early-close");
        var processor = runningProcessor(orchestrator, runner);

        assertDoesNotThrow(() -> orchestrator.complete(RunnerGrpc.getConnectMethod()));

        var failure = failureOf(runner);
        assertTrue(failure.getMessage().contains("stream ended before runner completed"),
                "the completion did not say why it was cut short: " + failure);

        assertEquals(1, runner.completions.get());
        assertTrue(processor.transform.isCancelled());
    }

    @Test
    void theNormalEndCompletesTheCompletion() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-normal-end");
        var processor = runningProcessor(orchestrator, runner);

        processor.transform.complete(null);
        assertFalse(runner.completion().isDone(), "the runner is not done while a processor is still producing");

        processor.produce.complete(null);

        assertNull(runner.completion().get(5, TimeUnit.SECONDS));
        assertEquals(1, runner.completions.get());
    }

    /**
     * The orchestrator normally closes the stream right after the runner did, so
     * this is the ordinary shutdown order and it may not turn a finished run into a
     * failed one.
     */
    @Test
    void anOrderlyCloseAfterTheEndChangesNothing() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-late-close");
        var processor = runningProcessor(orchestrator, runner);

        processor.transform.complete(null);
        processor.produce.complete(null);
        assertNull(runner.completion().get(5, TimeUnit.SECONDS));

        assertDoesNotThrow(() -> orchestrator.complete(RunnerGrpc.getConnectMethod()));

        assertFalse(runner.completion().isCompletedExceptionally());
        assertEquals(1, runner.completions.get());
    }

    /** A connection that breaks after the work is done tears nothing down twice. */
    @Test
    void aSecondTeardownIsANoOp() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-twice");
        var processor = runningProcessor(orchestrator, runner);

        processor.transform.complete(null);
        processor.produce.complete(null);
        assertNull(runner.completion().get(5, TimeUnit.SECONDS));

        assertDoesNotThrow(() -> orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped"));

        assertFalse(runner.completion().isCompletedExceptionally(),
                "a connection dropping after the work was done turned a finished run into a failed one");
        assertEquals(1, runner.completions.get(), "the runner was cleaned up twice");
    }

    /**
     * The stream carrying a streaming message can break on its own, and then that
     * message was not handled: its acknowledgement has to say so, otherwise the
     * orchestrator writes it off as delivered.
     */
    @Test
    void aBrokenStreamMessageIsAcknowledgedWithItsCause() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/teardown-stream-error");

        var channel = "http://example.org/channel/in";
        var reader = new Reader(channel, LOGGER);
        runner.setReader(channel, reader);

        runner.onNext(ToRunner.newBuilder()
                .setStreamMsg(ReceivingStreamMessage.newBuilder()
                        .setChannel(channel)
                        .setGlobalSequenceNumber(9))
                .build());

        assertDoesNotThrow(
                () -> orchestrator.fail(RunnerGrpc.getReceiveStreamMessageMethod(), "the stream message dropped"));

        var acknowledgements = acks(orchestrator);
        assertEquals(1, acknowledgements.size());
        var ack = acknowledgements.get(0);
        assertEquals(channel, ack.getChannel());
        assertEquals(9, ack.getGlobalSequenceNumber());
        assertTrue(ack.hasError(), "a streaming message that failed was acknowledged as handled");
        assertTrue(ack.getError().contains("the stream message dropped"),
                "the acknowledgement did not carry the cause: " + ack.getError());
    }
}
