package io.github.rdfc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.google.protobuf.Empty;

import io.github.rdfc.helpers.StreamObserverWrapper;
import io.grpc.stub.StreamObserver;
import rdfc.RunnerGrpc;
import rdfc.Service.LogMessage;

// Assuming you have a generated gRPC stub: LogServiceGrpc.LogServiceBlockingStub

public class GrpcLogHandler extends Handler
        implements StreamObserver<Empty> {
    public static final Map<Level, String> LEVEL_TO_STRING;

    /**
     * Used when a record carries no level at all, or a level that is not covered by
     * the thresholds below (which should not happen, they cover the full int
     * range).
     */
    static final String DEFAULT_LEVEL = "info";

    static {
        Map<Level, String> map = new HashMap<>();
        map.put(Level.SEVERE, "error");
        map.put(Level.WARNING, "warn");
        map.put(Level.INFO, "info");
        map.put(Level.CONFIG, "debug");
        map.put(Level.FINE, "debug");
        map.put(Level.FINER, "verbose");
        map.put(Level.FINEST, "silly");
        LEVEL_TO_STRING = Map.copyOf(map); // immutable map
    }

    /**
     * Translates a java.util.logging Level into one of the log levels the
     * orchestrator (winston) understands: error, warn, info, http, verbose, debug or
     * silly.
     *
     * Custom levels are not in LEVEL_TO_STRING, so they are mapped by their integer
     * value instead. The thresholds are picked so that the standard levels keep
     * mapping exactly as they always did. (`http` has no natural java.util.logging
     * counterpart, so it is never produced.)
     *
     * <b>Not the exact inverse of {@link Logging#parse}, and cannot be.</b> There,
     * both {@code verbose} and {@code debug} open up the FINE range; here FINE
     * maps back to {@code debug} and FINER to {@code verbose}. Winston counts
     * {@code verbose} as <em>less</em> verbose than {@code debug}, while
     * java.util.logging counts FINER as <em>more</em> verbose than FINE, so no
     * single pair of maps honours both orderings. This side stays as it is: it is
     * what the orchestrator is shown.
     *
     * @param level the level of the record, may be null
     * @return the matching orchestrator level, never null
     */
    static String levelToString(Level level) {
        if (level == null) {
            return DEFAULT_LEVEL;
        }

        var exact = LEVEL_TO_STRING.get(level);
        if (exact != null) {
            return exact;
        }

        var value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "error";
        }
        if (value >= Level.WARNING.intValue()) {
            return "warn";
        }
        if (value >= Level.INFO.intValue()) {
            return "info";
        }
        if (value >= Level.FINE.intValue()) {
            // covers CONFIG as well, which also maps to debug
            return "debug";
        }
        if (value >= Level.FINER.intValue()) {
            return "verbose";
        }
        return "silly";
    }

    private final StreamObserver<LogMessage> stream;
    private final String[] entity;
    private final String uri;

    public GrpcLogHandler(RunnerGrpc.RunnerStub stub, String uri, String... entity) {
        this.stream = StreamObserverWrapper.silent(stub.logStream(this));
        this.uri = uri;
        this.entity = entity;
    }

    /**
     * Constructor taking the outgoing stream directly, so the message construction
     * can be exercised without a gRPC connection.
     *
     * Log records are published from every thread in the runner, so the stream is
     * wrapped to serialize the calls onto it. The wrapper does not log, that would
     * recurse straight back into this handler.
     */
    GrpcLogHandler(StreamObserver<LogMessage> stream, String uri, String... entity) {
        this.stream = StreamObserverWrapper.silent(stream);
        this.uri = uri;
        this.entity = entity;
    }

    /**
     * Set once the log stream is gone: it failed, the orchestrator closed it, or
     * this handler was closed. There is nothing to send on anymore, and every
     * attempt would raise another failure to log.
     */
    private volatile boolean broken = false;

    /** Whether {@link #close()} already ran. Closing twice half-closes twice. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * The URI of whoever logs through this handler — the runner or one of its
     * processors. Visible for testing and for the teardown's log lines.
     *
     * @return the URI this handler was built for
     */
    String uri() {
        return this.uri;
    }

    @Override
    public void publish(LogRecord record) {
        if (this.broken || !isLoggable(record)) {
            return;
        }

        // A record may carry no message at all (a bare Throwable, for instance),
        // and the protobuf setter does not take null
        var text = record.getMessage();

        var msg = LogMessage.newBuilder()
                .setLevel(levelToString(record.getLevel()))
                .setMsg(text != null ? text : "");

        msg.addEntities(uri);
        for (var e : entity) {
            msg.addEntities(e);
        }

        try {
            this.stream.onNext(msg.build()); // RPC call
        } catch (Exception e) {
            // Don’t let logging failures crash the app
            e.printStackTrace();
        }
    }

    @Override
    public void flush() {
        // nothing to do
    }

    /**
     * Ends the log stream this handler ships on.
     *
     * Called from the runner's teardown, once per handler it built: a connection
     * that comes and goes in server mode would otherwise leave one open log RPC
     * behind per processor, forever.
     *
     * Idempotent, and it never throws. Half-closing a call twice, or a call whose
     * channel is already gone, raises — and this runs inside a teardown that may
     * not be stopped by it. Afterwards {@link #publish} is a no-op: whoever still
     * holds this logger keeps logging to the console through the root handler.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        // Read before it is set: a stream the orchestrator already ended is
        // closed on its side too, and half-closing it again only throws
        var gone = this.broken;
        this.broken = true;

        if (gone) {
            return;
        }

        try {
            this.stream.onCompleted();
        } catch (Exception e) {
            // Not through a Logger: this handler is where logging ends up, so that
            // would come straight back in here
            System.err.println("Completing the log stream to the orchestrator failed: " + e);
        }
    }

    /**
     * Whether this handler was closed. Visible for testing.
     *
     * @return true once {@link #close()} ran
     */
    boolean isClosed() {
        return this.closed.get();
    }

    // This is only a sending stream, we don't expect incoming messages.
    //
    // None of these three may throw, and none of them may log through a Logger
    // either: this handler *is* where logging ends up, so that would come straight
    // back in here (and, on a stream that just died, keep failing). They write to
    // stderr, which is the only place left.

    @Override
    public void onNext(Empty value) {
        // Nothing is expected on this stream, but an orchestrator that sends
        // something anyway is not a reason to fall over
    }

    @Override
    public void onError(Throwable t) {
        // The log stream died. Everything logged from here on is dropped, sending
        // it would only raise the same failure again.
        this.broken = true;
        System.err.println("The log stream to the orchestrator failed: " + t);
    }

    @Override
    public void onCompleted() {
        this.broken = true;
    }

    /**
     * Builds the logger that ships its records to the orchestrator through this
     * handler.
     *
     * <b>Anonymous</b>, and that is the point: {@code Logger.getLogger(uri)} looks
     * the logger up in the JVM-wide {@link java.util.logging.LogManager}, so two
     * server connections running the same runner URI at the same time would share
     * one logger, pile their handlers onto it and each ship the other's records.
     * An anonymous logger belongs to whoever holds it.
     *
     * That does mean the LogManager holds no reference to it: an anonymous logger
     * lives exactly as long as somebody keeps it. The runner does — see
     * {@code Runner.loggers} — and so does every processor, in
     * {@code Processor.logger}.
     *
     * Its parent is the root logger and parent handlers stay on, so everything
     * logged here also reaches the console handler {@link Logging} installed. The
     * level is ALL because the filtering belongs at the handlers: the console one
     * has the level {@code LOG_LEVEL} asked for, and the orchestrator does its own
     * filtering on what this handler ships.
     *
     * An anonymous logger has no name, so the record would reach the console
     * without saying who logged it. The filter stamps the URI on, before any
     * handler — this one or the root's — ever sees the record.
     *
     * @param handler ships the records to the orchestrator
     * @param uri     of whoever logs through it
     * @return the logger, held by nothing but the caller
     */
    static Logger loggerFor(GrpcLogHandler handler, String uri) {
        var logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(true);
        logger.setFilter(record -> {
            if (record.getLoggerName() == null) {
                record.setLoggerName(uri);
            }
            return true;
        });
        logger.addHandler(handler);
        return logger;
    }
}
