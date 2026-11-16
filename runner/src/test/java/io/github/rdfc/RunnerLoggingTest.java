package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.Empty;

import rdfc.RunnerGrpc;
import rdfc.Service.LogMessage;
import rdfc.Service.ToRunner;

/**
 * The loggers a runner hands out, and the log streams behind them.
 *
 * Two things used to be wrong here. The loggers came out of
 * {@code Logger.getLogger(uri)}, which is the JVM-wide registry: two server
 * connections running the same runner URI would have found the same logger and
 * piled their handlers onto it, each shipping the other's records. And nothing
 * ever closed the log RPC each of those handlers opened — one per processor,
 * left open for the life of the JVM.
 */
class RunnerLoggingTest {
    private static final String URI = "http://example.org/runner/logging";
    private static final String PROC = "http://example.org/processor/1";

    private final Logger root = Logger.getLogger("");
    private Handler[] handlers;
    private Level level;

    @BeforeEach
    void remember() {
        this.handlers = this.root.getHandlers();
        this.level = this.root.getLevel();
    }

    @AfterEach
    void restore() {
        for (Handler handler : this.root.getHandlers()) {
            this.root.removeHandler(handler);
        }
        for (Handler handler : this.handlers) {
            this.root.addHandler(handler);
        }
        this.root.setLevel(this.level);
    }

    /** Catches whatever reaches the root logger, the way the console handler does. */
    private static final class Recording extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            this.records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static ToRunner proc(String uri) {
        return ToRunner.newBuilder()
                .setProc(rdfc.Service.Processor.newBuilder().setUri(uri).setConfig("{}").setArguments("{}"))
                .build();
    }

    private static List<LogMessage> logs(FakeOrchestrator orchestrator) {
        return orchestrator.sentOn(RunnerGrpc.getLogStreamMethod());
    }

    private static List<GrpcLogHandler> grpcHandlersOf(Logger logger) {
        var found = new ArrayList<GrpcLogHandler>();
        for (Handler handler : logger.getHandlers()) {
            if (handler instanceof GrpcLogHandler) {
                found.add((GrpcLogHandler) handler);
            }
        }
        return found;
    }

    /**
     * The registry bug: with {@code Logger.getLogger(uri)} these two would have
     * been the very same object, with both handlers on it.
     */
    @Test
    void twoRunnersWithTheSameUriDoNotShareALogger() {
        var first = new FakeOrchestrator();
        var second = new FakeOrchestrator();

        var one = TestRunner.create(first, URI);
        var two = TestRunner.create(second, URI);

        assertEquals(1, one.logStreams().size());
        assertEquals(1, two.logStreams().size());

        var oneLogger = one.logStreams().get(0).logger;
        var twoLogger = two.logStreams().get(0).logger;

        assertNotSame(oneLogger, twoLogger, "both runners got the same Logger object");
        assertEquals(List.of(one.logStreams().get(0).handler), grpcHandlersOf(oneLogger),
                "the first runner's logger carries a handler that is not its own");
        assertEquals(List.of(two.logStreams().get(0).handler), grpcHandlersOf(twoLogger),
                "the second runner's logger carries a handler that is not its own");

        // And so what one of them logs goes to its own orchestrator and to no other
        oneLogger.info("only the first runner said this");

        assertTrue(said(first, "only the first runner said this"), "the record did not reach its own orchestrator");
        assertFalse(said(second, "only the first runner said this"),
                "a record leaked into the other runner's log stream");
    }

    private static boolean said(FakeOrchestrator orchestrator, String message) {
        return logs(orchestrator).stream().anyMatch(log -> message.equals(log.getMsg()));
    }

    /** They are anonymous, so nothing in the JVM-wide registry knows about them. */
    @Test
    void theLoggersAreNotInTheGlobalRegistry() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);

        assertNull(runner.logStreams().get(0).logger.getName(), "the logger was registered under its URI after all");
        assertEquals(List.of(), grpcHandlersOf(Logger.getLogger(URI)),
                "a handler was left on the registry's logger for this URI");
    }

    /**
     * The runner may not switch the console off — it used to strip every handler
     * off the root logger — and an anonymous logger has no name, so the record has
     * to say who logged it by itself.
     */
    @Test
    void whatAProcessorLogsAlsoReachesTheRootHandlers() {
        var console = new Recording();
        this.root.addHandler(console);
        this.root.setLevel(Level.ALL);

        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);
        runner.register(PROC, new StubProcessor());
        runner.onNext(proc(PROC));

        var processorLogger = runner.logStreams().get(1).logger;
        processorLogger.info("the processor said something");

        var seen = console.records.stream()
                .filter(record -> "the processor said something".equals(record.getMessage()))
                .findFirst();

        assertTrue(seen.isPresent(), "nothing a processor logs reaches the console anymore");
        assertEquals(PROC, seen.get().getLoggerName(), "the console line does not say who logged it");
    }

    /**
     * Every logger opens a log RPC of its own — one for the runner, one per
     * processor — and before this nothing ever closed them.
     */
    @Test
    void theTeardownClosesEveryLogStream() throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);
        var processor = runner.register(PROC, new StubProcessor());

        runner.onNext(proc(PROC));
        runner.onNext(ToRunner.newBuilder().setStart(Empty.getDefaultInstance()).build());
        processor.init.complete(null);

        var streams = runner.logStreams();
        assertEquals(2, streams.size(), "the runner and its processor each get a logger");
        assertEquals(0, orchestrator.goodbyesOn(RunnerGrpc.getLogStreamMethod()));

        processor.transform.complete(null);
        processor.produce.complete(null);
        assertNull(runner.completion().get(5, TimeUnit.SECONDS));

        for (var stream : streams) {
            assertTrue(stream.handler.isClosed(), "the log stream of " + stream.handler.uri() + " was left open");
            assertEquals(List.of(), grpcHandlersOf(stream.logger),
                    "the handler of " + stream.handler.uri() + " is still on its logger");
        }

        assertEquals(2, orchestrator.goodbyesOn(RunnerGrpc.getLogStreamMethod()),
                "not every log RPC was completed");
    }

    /**
     * A processor that keeps logging after its runner ended may not blow up, and
     * may not talk to an orchestrator that is not listening: those records belong
     * on the console.
     */
    @Test
    void loggingAfterTheTeardownIsHarmless() throws Exception {
        var console = new Recording();
        this.root.addHandler(console);
        this.root.setLevel(Level.ALL);

        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);
        var processor = runner.register(PROC, new StubProcessor());

        runner.onNext(proc(PROC));
        runner.onNext(ToRunner.newBuilder().setStart(Empty.getDefaultInstance()).build());
        processor.init.complete(null);
        var processorLogger = runner.logStreams().get(1).logger;

        processor.transform.complete(null);
        processor.produce.complete(null);
        assertNull(runner.completion().get(5, TimeUnit.SECONDS));

        assertDoesNotThrow(() -> processorLogger.warning("a late word"));

        assertFalse(said(orchestrator, "a late word"), "a record was pushed onto a log stream that was closed");
        assertTrue(console.records.stream().anyMatch(record -> "a late word".equals(record.getMessage())),
                "the record did not even reach the console");
    }

    /**
     * A processor can arrive while the teardown is already running — the two
     * reach this runner on different threads — and its log stream would then be
     * opened after the teardown walked the list.
     */
    @Test
    void aProcessorThatArrivesDuringTheTeardownLeavesNoStreamOpen() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);
        runner.register(PROC, new StubProcessor());

        orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped");

        assertDoesNotThrow(() -> runner.onNext(proc(PROC)));

        var streams = runner.logStreams();
        assertEquals(2, streams.size());
        assertTrue(streams.get(1).handler.isClosed(), "a log stream opened during the teardown was left open");
        assertEquals(List.of(), grpcHandlersOf(streams.get(1).logger));
    }

    /** The teardown runs on gRPC callback threads and may not be stopped twice. */
    @Test
    void closingALogStreamTwiceIsANoOp() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);
        var handler = runner.logStreams().get(0).handler;

        handler.close();
        assertDoesNotThrow(handler::close);

        assertEquals(1, orchestrator.goodbyesOn(RunnerGrpc.getLogStreamMethod()),
                "the log RPC was half-closed twice");
    }

    /** A stream the orchestrator already ended may not be half-closed on top. */
    @Test
    void closingALogStreamThatAlreadyDiedIsANoOp() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);
        var handler = runner.logStreams().get(0).handler;

        handler.onError(new IllegalStateException("the log stream dropped"));

        assertDoesNotThrow(handler::close);
        assertEquals(0, orchestrator.goodbyesOn(RunnerGrpc.getLogStreamMethod()));
        assertTrue(handler.isClosed());
    }

    /** Sanity: the runner's own logger is one of the tracked ones. */
    @Test
    void theRunnersOwnLoggerIsTracked() {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, URI);

        assertEquals(List.of(URI),
                runner.logStreams().stream().map(stream -> stream.handler.uri()).collect(Collectors.toList()));
    }
}
