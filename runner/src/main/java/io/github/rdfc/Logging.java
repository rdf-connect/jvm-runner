package io.github.rdfc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Console logging for this runner.
 *
 * Every entrypoint calls {@link #init()} before it does anything else. It puts
 * exactly one {@link ConsoleHandler} on the root logger, at the level the
 * {@code LOG_LEVEL} environment variable asks for, so everything the runner and
 * the processors log ends up on stderr — including the records that travel to
 * the orchestrator, which reach the root handler through their logger's parent.
 *
 * It is safe to call more than once: the second call re-reads the level and
 * leaves the handler it installed the first time in place, rather than stacking
 * a second one that would print every line twice. That matters for server mode,
 * where several things can be started inside one JVM.
 */
public final class Logging {
    /** The environment variable the js- and py-runners read as well. */
    static final String LEVEL_VARIABLE = "LOG_LEVEL";

    /** Used when {@code LOG_LEVEL} is unset, empty or not a level we know. */
    static final Level DEFAULT_LEVEL = Level.INFO;

    /**
     * Libraries that log a great deal below INFO.
     *
     * {@code LOG_LEVEL=debug} is a request to see what the <em>runner and its
     * processors</em> are doing. Lowering the root level lowers it for these two
     * as well, and they answer with hundreds of lines about unsafe memory access
     * and channel providers before the first processor is even built — the
     * interesting lines are then simply not findable. In the js- and py-runners
     * the question does not arise: their level reaches winston/logging, not the
     * transport underneath it.
     */
    private static final String[] NOISY = { "io.grpc", "io.netty" };

    /**
     * Holds the loggers of {@link #NOISY}.
     *
     * The LogManager only holds loggers weakly, and this runs before netty or
     * gRPC has loaded a single class, so at that moment nothing else holds them
     * at all: collected and re-created, they would come back with no level and
     * inherit the root's again.
     */
    private static final List<Logger> pinned = new ArrayList<>();

    private Logging() {
    }

    /**
     * Sets console logging up, taking the level from the {@code LOG_LEVEL}
     * environment variable.
     */
    public static void init() {
        init(System.getenv(LEVEL_VARIABLE));
    }

    /**
     * Sets console logging up at a given level.
     *
     * @param level one of the names the orchestrator uses — {@code error},
     *              {@code warn}, {@code info}, {@code http}, {@code verbose},
     *              {@code debug}, {@code silly}, case-insensitive. Null, empty or
     *              unknown falls back on {@code info}, and an unknown one is
     *              reported.
     */
    public static synchronized void init(String level) {
        var parsed = parse(level);
        var effective = parsed != null ? parsed : DEFAULT_LEVEL;

        var root = Logger.getLogger("");
        root.setLevel(effective);

        // Only ever raised, never lowered: at info and above these two are quiet
        // anyway, and pinning them then would take away a level somebody set by
        // hand in a logging.properties
        if (effective.intValue() < Level.INFO.intValue()) {
            for (String noisy : NOISY) {
                var logger = Logger.getLogger(noisy);
                logger.setLevel(Level.INFO);
                pinned.add(logger);
            }
        }

        // Any console handler that is not ours prints the same records a second
        // time — the JDK's default configuration installs one. Handlers that are
        // not consoles belong to whoever installed them and are left alone.
        RunnerConsoleHandler ours = null;
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof RunnerConsoleHandler) {
                ours = (RunnerConsoleHandler) handler;
            } else if (handler instanceof ConsoleHandler) {
                root.removeHandler(handler);
            }
        }

        if (ours == null) {
            ours = new RunnerConsoleHandler();
            root.addHandler(ours);
        }

        // Both, because a record has to pass the logger's level and then the
        // handler's own
        ours.setLevel(effective);

        if (parsed == null) {
            // Reported through the handler that was just installed, so it is
            // actually seen — and only ever for a name that was really given,
            // because an unset variable parses to the default
            Logger.getLogger(Logging.class.getName())
                    .warning("Unknown " + LEVEL_VARIABLE + " '" + level + "', falling back on info");
        }
    }

    /**
     * Translates one of the orchestrator's level names into a java.util.logging
     * level.
     *
     * The names are winston's, so they are the same ones the js- and py-runners
     * accept. {@code http} has no counterpart here and is treated as
     * {@code info}; {@code verbose} and {@code debug} both open up the FINE
     * range, which is where {@link GrpcLogHandler} maps them back from.
     *
     * @param name the level name, may be null or empty
     * @return the level, or null when the name is not one we know. Null and empty
     *         give the default level rather than null.
     */
    static Level parse(String name) {
        if (name == null || name.trim().isEmpty()) {
            return DEFAULT_LEVEL;
        }

        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "error":
                return Level.SEVERE;
            case "warn":
            case "warning":
                return Level.WARNING;
            case "info":
            case "http":
                return Level.INFO;
            case "verbose":
            case "debug":
                return Level.FINE;
            case "silly":
                return Level.FINEST;
            default:
                return null;
        }
    }

    /**
     * The console handler this class installs.
     *
     * A type of its own, so a second {@link #init} can tell the handler it put
     * there itself apart from any other console handler and does not end up
     * printing everything twice.
     */
    private static final class RunnerConsoleHandler extends ConsoleHandler {
        RunnerConsoleHandler() {
            // ConsoleHandler already writes to stderr, only the layout is ours
            this.setFormatter(new SingleLineFormatter());
        }
    }

    /**
     * One record, one line: when it happened, how bad it is, who said it, and
     * what they said. A throwable, if there is one, follows on the lines after
     * it.
     */
    static final class SingleLineFormatter extends Formatter {
        private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault());

        /**
         * Shown for a record whose logger has no name. The loggers this runner
         * builds are anonymous — see {@link GrpcLogHandler#loggerFor} — and stamp
         * their URI onto the record, so this is only reached by records from
         * somewhere else entirely.
         */
        static final String UNNAMED = "rdfc";

        @Override
        public String format(LogRecord record) {
            var name = record.getLoggerName();

            var line = new StringBuilder()
                    .append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(' ')
                    .append(record.getLevel().getName())
                    .append(" [")
                    .append(name != null && !name.isEmpty() ? name : UNNAMED)
                    .append("] ")
                    .append(this.formatMessage(record))
                    .append(System.lineSeparator());

            var thrown = record.getThrown();
            if (thrown != null) {
                var trace = new StringWriter();
                try (var writer = new PrintWriter(trace)) {
                    thrown.printStackTrace(writer);
                }
                line.append(trace);
            }

            return line.toString();
        }
    }
}
