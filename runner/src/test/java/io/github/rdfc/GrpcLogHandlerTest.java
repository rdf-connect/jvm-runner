package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

import io.grpc.stub.StreamObserver;
import rdfc.Service.LogMessage;

/**
 * The handler used to look its level up in a map, which returned null for
 * custom levels, and the protobuf setter then threw an NPE from inside logging.
 */
class GrpcLogHandlerTest {
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
}
