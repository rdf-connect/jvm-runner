package io.github.rdfc;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.google.protobuf.Empty;

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
        this.stream = stub.logStream(this);
        this.uri = uri;
        this.entity = entity;
    }

    /**
     * Constructor taking the outgoing stream directly, so the message construction
     * can be exercised without a gRPC connection.
     */
    GrpcLogHandler(StreamObserver<LogMessage> stream, String uri, String... entity) {
        this.stream = stream;
        this.uri = uri;
        this.entity = entity;
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }

        var msg = LogMessage.newBuilder()
                .setLevel(levelToString(record.getLevel()))
                .setMsg(record.getMessage());

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

    @Override
    public void close() {
        // close channel if needed
        this.stream.onCompleted();
    }

    // This is only a sending stream, we don't expect incoming messages
    @Override
    public void onNext(Empty value) {
        throw new UnsupportedOperationException("Unimplemented method 'onNext'");
    }

    @Override
    public void onError(Throwable t) {
        throw new UnsupportedOperationException("Unimplemented method 'onError'");
    }

    @Override
    public void onCompleted() {
        throw new UnsupportedOperationException("Unimplemented method 'onCompleted'");
    }

    public static Logger createLogger(RunnerGrpc.RunnerStub stub, String uri, String... entities) {
        var logger = Logger.getLogger(uri);
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
        }
        logger.addHandler(new GrpcLogHandler(stub, uri, entities));
        return logger;
    }
}
