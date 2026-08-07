package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Console logging, which the runner used to switch off outright: its
 * constructor took every handler off the root logger, so nothing a processor
 * logged ever reached a terminal.
 *
 * These tests reach into the root logger, which is shared by the whole JVM, so
 * they put it back the way they found it afterwards.
 */
class LoggingTest {
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

    private List<Handler> consoles() {
        return Arrays.stream(this.root.getHandlers())
                .filter(handler -> handler instanceof ConsoleHandler)
                .collect(Collectors.toList());
    }

    /** A handler that is not a console, so nothing here is allowed to touch it. */
    private static final class Recording extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            this.records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void initInstallsOneConsoleHandler() {
        Logging.init("info");

        assertEquals(1, this.consoles().size());
        assertEquals(Level.INFO, this.root.getLevel());
        assertEquals(Level.INFO, this.consoles().get(0).getLevel());
        assertNotNull(this.consoles().get(0).getFormatter());
    }

    /**
     * Server mode can start several things inside one JVM, and a second handler
     * would print every line twice.
     */
    @Test
    void initTwiceDoesNotStackHandlers() {
        Logging.init("info");
        var first = this.consoles().get(0);

        Logging.init("debug");

        assertEquals(1, this.consoles().size(), "a second console handler was stacked on the root logger");
        assertSame(first, this.consoles().get(0), "the console handler was replaced instead of reused");
        // The second call still has its say about the level
        assertEquals(Level.FINE, this.root.getLevel());
        assertEquals(Level.FINE, first.getLevel());
    }

    /** The JDK's own configuration installs one, and it prints the same records. */
    @Test
    void initTakesOverFromAnotherConsoleHandler() {
        var jdkDefault = new ConsoleHandler();
        this.root.addHandler(jdkDefault);

        Logging.init("info");

        assertEquals(1, this.consoles().size());
        assertTrue(this.consoles().get(0) != jdkDefault, "the pre-existing console handler prints everything twice");
    }

    /** Whoever installed those wants them, and they are not consoles. */
    @Test
    void initLeavesOtherHandlersAlone() {
        var mine = new Recording();
        this.root.addHandler(mine);

        Logging.init("info");

        assertTrue(Arrays.asList(this.root.getHandlers()).contains(mine), "a handler somebody else installed was removed");
    }

    /**
     * Asking for debug is asking what the runner is doing, and netty answers with
     * hundreds of lines about unsafe memory access before the first processor is
     * even built.
     */
    @Test
    void theNoisyLibrariesStayAtInfoBelowInfo() {
        var netty = Logger.getLogger("io.netty");
        var was = netty.getLevel();
        try {
            netty.setLevel(null);

            Logging.init("debug");
            assertEquals(Level.INFO, netty.getLevel());

            // At info and up they are quiet by themselves, and pinning them would
            // take away a level somebody set by hand
            netty.setLevel(Level.FINEST);
            Logging.init("info");
            assertEquals(Level.FINEST, netty.getLevel());
        } finally {
            netty.setLevel(was);
        }
    }

    @Test
    void everyLevelTheOrchestratorUsesIsUnderstood() {
        assertEquals(Level.SEVERE, Logging.parse("error"));
        assertEquals(Level.WARNING, Logging.parse("warn"));
        assertEquals(Level.WARNING, Logging.parse("warning"));
        assertEquals(Level.INFO, Logging.parse("info"));
        assertEquals(Level.INFO, Logging.parse("http"));
        assertEquals(Level.FINE, Logging.parse("verbose"));
        assertEquals(Level.FINE, Logging.parse("debug"));
        assertEquals(Level.FINEST, Logging.parse("silly"));
    }

    @Test
    void theLevelNameIsNotCaseSensitiveAndMayBePadded() {
        assertEquals(Level.SEVERE, Logging.parse("ERROR"));
        assertEquals(Level.FINEST, Logging.parse("  Silly "));
    }

    @Test
    void anAbsentLevelIsInfo() {
        assertEquals(Logging.DEFAULT_LEVEL, Logging.parse(null));
        assertEquals(Logging.DEFAULT_LEVEL, Logging.parse(""));
        assertEquals(Logging.DEFAULT_LEVEL, Logging.parse("   "));
    }

    @Test
    void anUnknownLevelIsReportedAndFallsBackOnInfo() {
        assertNull(Logging.parse("chatty"), "a level nobody knows was silently accepted");

        var complaints = new Recording();
        this.root.addHandler(complaints);

        Logging.init("chatty");

        assertEquals(Level.INFO, this.root.getLevel());
        assertTrue(complaints.records.stream().anyMatch(record -> record.getMessage().contains("chatty")),
                "an unusable LOG_LEVEL was not reported");
    }

    /** One record, one line, and it says who logged it. */
    @Test
    void theFormatterPutsARecordOnOneLine() {
        var formatter = new Logging.SingleLineFormatter();

        var record = new LogRecord(Level.WARNING, "something happened");
        record.setLoggerName("http://example.org/processor/1");

        var line = formatter.format(record);

        assertEquals(1, line.split(System.lineSeparator(), -1).length - 1, "the record did not fit on one line: " + line);
        assertTrue(line.contains("WARNING"), line);
        assertTrue(line.contains("http://example.org/processor/1"), line);
        assertTrue(line.contains("something happened"), line);
    }

    @Test
    void theFormatterNamesARecordFromAnAnonymousLogger() {
        var formatter = new Logging.SingleLineFormatter();

        var line = formatter.format(new LogRecord(Level.INFO, "no logger name here"));

        assertTrue(line.contains(Logging.SingleLineFormatter.UNNAMED), line);
    }

    @Test
    void theFormatterKeepsTheThrowable() {
        var formatter = new Logging.SingleLineFormatter();

        var record = new LogRecord(Level.SEVERE, "it broke");
        record.setThrown(new IllegalStateException("the cause"));

        var line = formatter.format(record);

        assertTrue(line.contains("IllegalStateException"), line);
        assertTrue(line.contains("the cause"), line);
    }
}
