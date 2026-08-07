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

        // Back to the baseline. Nothing is left to wait for, so the runner ends —
        // and it has to notice that, the give-back goes through the zero check.
        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get());

        var reported = initialized(orchestrator);
        assertEquals(1, reported.size());
        assertTrue(reported.get(0).hasError());
        assertTrue(reported.get(0).getError().getCause().contains("init blew up"),
                "the failure was not reported: " + reported.get(0).getError().getCause());

        // A processor that never initialized may not produce
        runner.onNext(start());
        assertEquals(0, processor.produceCalls.get());
        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get(), "the runner has to finish exactly once");
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

    /**
     * The other order: the failing processor is the last one left. Handing its two
     * callbacks back is then what drops the counter onto zero, so that give-back
     * has to end the runner instead of silently leaving it at zero forever.
     */
    @Test
    void aFailedInitThatLandsLastStillEndsTheRunner() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-failure-last");
        var working = runner.register(PROC, new StubProcessor());
        var broken = runner.register(PROC + "/other", new StubProcessor());

        runner.onNext(proc(PROC));
        runner.onNext(proc(PROC + "/other"));
        assertEquals(4, runner.awaiting());

        runner.onNext(start());

        // The working processor runs to completion first
        working.init.complete(null);
        working.transform.complete(null);
        working.produce.complete(null);
        assertEquals(2, runner.awaiting());
        assertEquals(0, runner.completions.get());

        // and only then does the other one fail to initialize
        broken.init.completeExceptionally(new RuntimeException("init blew up"));

        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get(), "the runner never noticed it had nothing left to wait for");
        assertEquals(0, broken.produceCalls.get());
    }

    /**
     * An orchestrator that answers the ProcessorInitialized with the start on the
     * very same thread reaches the start handler while the proc handler has not
     * returned yet. It still has to find the processor.
     */
    @Test
    void aStartArrivingWhileInitIsStillRunningStillProduces() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-reentrant-start");
        var processor = runner.register(PROC, new StubProcessor());

        // Init resolves the moment it is called, and its ProcessorInitialized brings
        // the start straight back in on this thread.
        processor.init.complete(null);
        orchestrator.whileSending(RunnerGrpc.getConnectMethod(), (FromRunner message) -> {
            if (message.hasInitialized()) {
                orchestrator.respondOnThisThread(RunnerGrpc.getConnectMethod(), start());
            }
        });

        runner.onNext(proc(PROC));

        assertEquals(1, processor.transformCalls.get());
        assertEquals(1, processor.produceCalls.get(), "the re-entrant start did not find the processor");

        processor.transform.complete(null);
        processor.produce.complete(null);
        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get());
    }

    /**
     * Reporting the processor as initialized can fail on its own, for instance on a
     * stream that is being torn down. That failure fails the init future, which
     * hands the two callbacks back — so it may not have started transform yet,
     * because transform's completion hands one of them back a second time.
     */
    @Test
    void aFailingInitReportDoesNotHandTheCallbacksBackTwice() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/lifecycle-report-fails");
        var processor = runner.register(PROC, new StubProcessor());

        orchestrator.whileSending(RunnerGrpc.getConnectMethod(), (FromRunner message) -> {
            if (message.hasInitialized() && !message.getInitialized().hasError()) {
                throw new IllegalStateException("stream is already closed");
            }
        });

        runner.onNext(proc(PROC));
        processor.init.complete(null);

        assertEquals(0, processor.transformCalls.get(),
                "transform was started even though reporting the processor as initialized failed");
        assertEquals(0, runner.awaiting());
        assertEquals(1, runner.completions.get());
        assertTrue(initialized(orchestrator).stream().anyMatch(ProcessorInitialized::hasError),
                "the failure was never reported");
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
