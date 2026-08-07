package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;

import rdfc.Common.GlobalAck;
import rdfc.Common.ReceivingMessage;
import rdfc.RunnerGrpc;
import rdfc.Service.FromRunner;
import rdfc.Service.ProcessorInitialized;
import rdfc.Service.ToRunner;

/**
 * A processor may only produce once its init finished, and the counter that
 * decides when this runner is done has to survive a start that arrives while an
 * init is still pending.
 */
class RunnerLifecycleTest {
    private static final String PROC = "http://example.org/processor/1";

    private static ToRunner proc(String uri) {
        return ToRunner.newBuilder()
                .setProc(rdfc.Service.Processor.newBuilder().setUri(uri).setConfig("{}").setArguments("{}"))
                .build();
    }

    private static ToRunner start() {
        return ToRunner.newBuilder().setStart(Empty.getDefaultInstance()).build();
    }

    private static List<FromRunner> sent(FakeOrchestrator orchestrator) {
        return orchestrator.sentOn(RunnerGrpc.getConnectMethod());
    }

    private static List<ProcessorInitialized> initialized(FakeOrchestrator orchestrator) {
        return sent(orchestrator).stream()
                .filter(FromRunner::hasInitialized)
                .map(FromRunner::getInitialized)
                .collect(Collectors.toList());
    }

    private static List<GlobalAck> acks(FakeOrchestrator orchestrator) {
        return sent(orchestrator).stream()
                .filter(FromRunner::hasProcessed)
                .map(FromRunner::getProcessed)
                .collect(Collectors.toList());
    }

    @Test
    void produceWaitsForAStillPendingInit() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle");
        var processor = runner.register(PROC, new StubProcessor());

        runner.onNext(proc(PROC));

        // Both callbacks are claimed the moment the processor is received, not only
        // when its init finished.
        assertEquals(2, runner.awaiting());
        assertEquals(1, processor.initCalls.get());
        assertEquals(0, processor.transformCalls.get());

        // The start arrives while init is still pending
        runner.onNext(start());
        assertEquals(0, processor.produceCalls.get(), "produce ran before init finished");
        assertEquals(2, runner.awaiting());
        assertEquals(0, runner.completions.get());

        // Releasing init starts transform, reports the processor as initialized and
        // only then lets it produce.
        processor.init.complete(null);
        assertEquals(1, processor.transformCalls.get());
        assertEquals(1, processor.produceCalls.get());
        assertEquals(List.of(PROC),
                initialized(orchestrator).stream().map(ProcessorInitialized::getUri).collect(Collectors.toList()));
        assertFalse(initialized(orchestrator).get(0).hasError());

        assertEquals(2, runner.awaiting());
        assertEquals(0, runner.completions.get());

        processor.transform.complete(null);
        assertEquals(1, runner.awaiting());
        assertEquals(0, runner.completions.get());

        processor.produce.complete(null);
        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get(), "the runner has to finish exactly once");
    }

    @Test
    void produceRunsRightAwayWhenInitAlreadyFinished() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-late-start");
        var processor = runner.register(PROC, new StubProcessor());

        runner.onNext(proc(PROC));
        processor.init.complete(null);
        assertEquals(0, processor.produceCalls.get());

        runner.onNext(start());
        assertEquals(1, processor.produceCalls.get());

        processor.transform.complete(null);
        processor.produce.complete(null);
        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get());
    }

    @Test
    void aFailedInitHandsBothCallbacksBack() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-failing-init");
        var processor = runner.register(PROC, new StubProcessor());

        runner.onNext(proc(PROC));
        assertEquals(2, runner.awaiting());

        processor.init.completeExceptionally(new RuntimeException("init blew up"));

        // Back to the baseline, and the runner did not decide it was finished
        assertEquals(0, runner.awaiting());
        assertEquals(0, runner.completions.get());

        var reported = initialized(orchestrator);
        assertEquals(1, reported.size());
        assertTrue(reported.get(0).hasError());
        assertTrue(reported.get(0).getError().getCause().contains("init blew up"),
                "the failure was not reported: " + reported.get(0).getError().getCause());

        // A processor that never initialized may not produce
        runner.onNext(start());
        assertEquals(0, processor.produceCalls.get());
        assertEquals(0, runner.awaiting());
        assertEquals(0, runner.completions.get());
    }

    @Test
    void aFailedInitDoesNotStrandItsSiblings() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-siblings");
        var broken = runner.register(PROC, new StubProcessor());
        var working = runner.register(PROC + "/other", new StubProcessor());

        runner.onNext(proc(PROC));
        runner.onNext(proc(PROC + "/other"));
        assertEquals(4, runner.awaiting());

        broken.init.completeExceptionally(new RuntimeException("init blew up"));
        assertEquals(2, runner.awaiting());

        runner.onNext(start());
        working.init.complete(null);
        working.transform.complete(null);
        working.produce.complete(null);

        assertEquals(0, broken.produceCalls.get());
        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get());
    }

    @Test
    void theAckOfAFailedMessageCarriesTheError() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-error-ack");

        var channel = "http://example.org/channel/in";
        var reader = new Reader(channel, Logger.getLogger(RunnerLifecycleTest.class.getName()));
        runner.setReader(channel, reader);
        reader.buffers().on(buffer -> {
            var failed = new CompletableFuture<Object>();
            failed.completeExceptionally(new RuntimeException("consumer blew up"));
            return failed;
        });

        runner.onNext(ToRunner.newBuilder()
                .setMsg(ReceivingMessage.newBuilder()
                        .setChannel(channel)
                        .setGlobalSequenceNumber(7)
                        .setData(ByteString.copyFromUtf8("payload")))
                .build());

        var acknowledgements = acks(orchestrator);
        assertEquals(1, acknowledgements.size());
        var ack = acknowledgements.get(0);
        assertEquals(channel, ack.getChannel());
        assertEquals(7, ack.getGlobalSequenceNumber());
        assertTrue(ack.hasError());
        assertTrue(ack.getError().contains("consumer blew up"), "the ack did not carry the error: " + ack.getError());
    }

    @Test
    void theAckOfAHandledMessageCarriesNoError() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-ok-ack");

        var channel = "http://example.org/channel/in";
        var reader = new Reader(channel, Logger.getLogger(RunnerLifecycleTest.class.getName()));
        runner.setReader(channel, reader);
        reader.buffers().on(buffer -> {
            return CompletableFuture.completedFuture(null);
        });

        runner.onNext(ToRunner.newBuilder()
                .setMsg(ReceivingMessage.newBuilder()
                        .setChannel(channel)
                        .setGlobalSequenceNumber(3)
                        .setData(ByteString.copyFromUtf8("payload")))
                .build());

        var acknowledgements = acks(orchestrator);
        assertEquals(1, acknowledgements.size());
        assertFalse(acknowledgements.get(0).hasError());
    }
}
