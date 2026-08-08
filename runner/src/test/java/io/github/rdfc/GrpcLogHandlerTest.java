package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Empty;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import rdfc.Service.LogMessage;

/**
 * The handler used to look its level up in a map, which returned null for
 * custom levels, and the protobuf setter then threw an NPE from inside logging.
 */
class GrpcLogHandlerTest {
    /** What the handler says when a log stream dies and that is worth knowing. */
    private static final String FAILURE_LINE = "The log stream to the orchestrator failed";

    /**
     * Where the handler says it. Named, and no handler of the class under test is
     * ever on it — which is what makes it safe for that class to log at all.
     */
    private static final Logger HANDLER_LOGGER = Logger.getLogger(GrpcLogHandler.class.getName());

    /** The levels the orchestrator (winston) accepts. */
    private static final Set<String> WINSTON_LEVELS = Set.of("error", "warn", "info", "http", "verbose", "debug",
            "silly");

    /** A level that is not one of the java.util.logging constants. */
    private static final class CustomLevel extends Level {
        private static final long serialVersionUID = 1L;

        CustomLevel(String name, int value) {
            super(name, value);
        }
    }

    private static final class CapturingStream implements StreamObserver<LogMessage> {
        final List<LogMessage> sent = new ArrayList<>();

        @Override
        public void onNext(LogMessage value) {
            this.sent.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    @Test
    void standardLevelsKeepTheirMapping() {
        assertEquals("error", GrpcLogHandler.levelToString(Level.SEVERE));
        assertEquals("warn", GrpcLogHandler.levelToString(Level.WARNING));
        assertEquals("info", GrpcLogHandler.levelToString(Level.INFO));
        assertEquals("debug", GrpcLogHandler.levelToString(Level.CONFIG));
        assertEquals("debug", GrpcLogHandler.levelToString(Level.FINE));
        assertEquals("verbose", GrpcLogHandler.levelToString(Level.FINER));
        assertEquals("silly", GrpcLogHandler.levelToString(Level.FINEST));
    }

    @Test
    void unmappedLevelsFallBackOnTheirIntValue() {
        assertEquals("error", GrpcLogHandler.levelToString(new CustomLevel("FATAL", 1200)));
        assertEquals("error", GrpcLogHandler.levelToString(new CustomLevel("NOTICE", 1000)));
        assertEquals("warn", GrpcLogHandler.levelToString(new CustomLevel("ALERT", 950)));
        assertEquals("info", GrpcLogHandler.levelToString(new CustomLevel("NOTICE", 850)));
        assertEquals("debug", GrpcLogHandler.levelToString(new CustomLevel("TRACE", 600)));
        assertEquals("verbose", GrpcLogHandler.levelToString(new CustomLevel("DETAIL", 450)));
        assertEquals("silly", GrpcLogHandler.levelToString(new CustomLevel("SPAM", 100)));
        assertEquals("silly", GrpcLogHandler.levelToString(Level.ALL));
    }

    @Test
    void everyLevelMapsToALevelTheOrchestratorKnows() {
        for (var value : new int[] { Integer.MIN_VALUE, 0, 100, 300, 400, 500, 700, 800, 900, 1000, Integer.MAX_VALUE }) {
            var mapped = GrpcLogHandler.levelToString(new CustomLevel("L" + value, value));
            assertTrue(WINSTON_LEVELS.contains(mapped), "unexpected level string " + mapped + " for " + value);
        }
    }

    @Test
    void nullLevelFallsBackOnTheDefault() {
        assertEquals(GrpcLogHandler.DEFAULT_LEVEL, GrpcLogHandler.levelToString(null));
    }

    @Test
    void publishingAnUnmappedLevelDoesNotThrow() {
        var stream = new CapturingStream();
        var handler = new GrpcLogHandler(stream, "http://example.org/runner", "cli");

        var record = new LogRecord(new CustomLevel("AUDIT", 850), "something happened");

        assertDoesNotThrow(() -> handler.publish(record));

        assertEquals(1, stream.sent.size());
        var sent = stream.sent.get(0);
        assertEquals("info", sent.getLevel());
        assertEquals("something happened", sent.getMsg());
        assertEquals(List.of("http://example.org/runner", "cli"), sent.getEntitiesList());
    }

    @Test
    void publishingAStandardLevelStillWorks() {
        var stream = new CapturingStream();
        var handler = new GrpcLogHandler(stream, "http://example.org/runner");

        handler.publish(new LogRecord(Level.SEVERE, "boom"));

        assertEquals(1, stream.sent.size());
        assertEquals("error", stream.sent.get(0).getLevel());
    }

    /**
     * A record does not have to carry a message — logging a bare Throwable makes
     * one that does not — and the protobuf setter does not take null.
     */
    @Test
    void publishingARecordWithoutAMessageDoesNotThrow() {
        var stream = new CapturingStream();
        var handler = new GrpcLogHandler(stream, "http://example.org/runner");

        var record = new LogRecord(Level.WARNING, null);

        assertDoesNotThrow(() -> handler.publish(record));

        assertEquals(1, stream.sent.size());
        assertEquals("", stream.sent.get(0).getMsg());
    }

    /**
     * This is the response side of the log stream. None of it may throw — it runs
     * on a gRPC callback thread, where an exception takes the connection down —
     * and once the stream is gone there is nothing left to log on.
     */
    @Test
    void theResponseSideNeverThrows() {
        var stream = new CapturingStream();
        var handler = new GrpcLogHandler(stream, "http://example.org/runner");

        assertDoesNotThrow(() -> handler.onNext(Empty.getDefaultInstance()));
        assertDoesNotThrow(() -> handler.onError(new IllegalStateException("the log stream dropped")));
        assertDoesNotThrow(() -> handler.onCompleted());

        handler.publish(new LogRecord(Level.SEVERE, "boom"));
        assertEquals(0, stream.sent.size(), "kept sending on a log stream that is gone");
    }

    /**
     * A stream that fails on a handler that is still open is a fault, and it is
     * reported loudly — through the named logger, not past every handler onto raw
     * stderr.
     */
    @Test
    void aStreamThatFailsWhileOpenIsReportedLoudly() {
        var handler = new GrpcLogHandler(new CapturingStream(), "http://example.org/runner");
        var failure = unavailable();

        var records = whileCapturingRecords(() -> handler.onError(failure));

        var warnings = at(records, Level.WARNING);
        assertEquals(1, warnings.size(), "a live log stream failed without a word");
        assertTrue(warnings.get(0).getMessage().contains(FAILURE_LINE), warnings.get(0).getMessage());
        assertTrue(warnings.get(0).getMessage().contains("UNAVAILABLE"),
                "the failure was reported without saying what it was: " + warnings.get(0).getMessage());
    }

    /**
     * The teardown half-closes the log stream and then drops the channel, and gRPC
     * answers that with an onError carrying UNAVAILABLE — once per handler. That is
     * the shutdown finishing, not a fault, and it used to print a wall of failure
     * lines after every clean run.
     */
    @Test
    void aStreamThatFailsAfterAnIntentionalCloseIsQuiet() {
        var handler = new GrpcLogHandler(new CapturingStream(), "http://example.org/runner");
        handler.close();
        var failure = unavailable();

        var printed = new StringBuilder();
        var records = whileCapturingRecords(
                () -> printed.append(whileCapturingStderr(() -> handler.onError(failure))));

        assertEquals(0, at(records, Level.WARNING).size(), "a closed log stream still complained");
        assertEquals(1, at(records, Level.FINE).size(), "the routine end was not even noted at FINE");
        // And nothing reached the console either: the FINE record does not clear
        // the level the console handler runs at, and nothing here goes past it
        assertFalse(printed.toString().contains(FAILURE_LINE),
                "a closed log stream still complained on stderr: " + printed);
        assertEquals("", printed.toString(), "a routine teardown printed something after all: " + printed);
    }

    /** CANCELLED is the other status a teardown produces by itself. */
    @Test
    void aCancelledStreamAfterACloseIsQuietToo() {
        var handler = new GrpcLogHandler(new CapturingStream(), "http://example.org/runner");
        handler.close();

        var records = whileCapturingRecords(
                () -> handler.onError(new StatusRuntimeException(Status.CANCELLED)));

        assertEquals(0, at(records, Level.WARNING).size(), "a cancelled log stream complained after a close");
    }

    /**
     * Being closed is not a licence to swallow everything. A close explains
     * UNAVAILABLE and CANCELLED; it explains nothing about an INTERNAL, and
     * demoting that to FINE would hide a real fault behind a clean shutdown.
     */
    @Test
    void aRealFaultAfterACloseIsStillReported() {
        var handler = new GrpcLogHandler(new CapturingStream(), "http://example.org/runner");
        handler.close();
        var failure = new StatusRuntimeException(Status.INTERNAL.withDescription("frame size exceeded"));

        var records = whileCapturingRecords(() -> handler.onError(failure));

        var warnings = at(records, Level.WARNING);
        assertEquals(1, warnings.size(), "a genuine fault was demoted because the handler happened to be closed");
        assertTrue(warnings.get(0).getMessage().contains("INTERNAL"), warnings.get(0).getMessage());
    }

    /** The failure gRPC reports when a channel is dropped under an open call. */
    private static StatusRuntimeException unavailable() {
        return new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Channel shutdownNow invoked"));
    }

    private static List<LogRecord> at(List<LogRecord> records, Level level) {
        var out = new ArrayList<LogRecord>();
        for (var record : records) {
            if (record.getLevel().equals(level)) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * Runs the action with everything the handler's own logger says collected.
     *
     * The logger is opened up to ALL for the duration: the FINE line is the whole
     * point of one of these tests, and at the default level a Logger drops it
     * before any handler sees it.
     */
    private static List<LogRecord> whileCapturingRecords(Runnable action) {
        var records = new ArrayList<LogRecord>();
        var collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        var level = HANDLER_LOGGER.getLevel();
        HANDLER_LOGGER.setLevel(Level.ALL);
        HANDLER_LOGGER.addHandler(collector);
        try {
            action.run();
        } finally {
            HANDLER_LOGGER.removeHandler(collector);
            HANDLER_LOGGER.setLevel(level);
        }
        return records;
    }

    /**
     * Runs the action with stderr redirected, and hands back what it wrote.
     *
     * The root logger's handlers are built first, on purpose: a ConsoleHandler
     * takes hold of whatever {@code System.err} is when it is constructed, so one
     * that happens to be built inside this window would keep writing into a buffer
     * this method is about to throw away — for the rest of the JVM, and so for
     * every test after this one.
     */
    private static String whileCapturingStderr(Runnable action) {
        Logger.getLogger("").getHandlers();

        var original = System.err;
        var captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
            System.err.flush();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
