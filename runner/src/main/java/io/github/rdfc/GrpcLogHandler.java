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
import rdfc.Log.LogMessage;

// Assuming you have a generated gRPC stub: LogServiceGrpc.LogServiceBlockingStub

public class GrpcLogHandler extends Handler
        implements StreamObserver<Empty> {
    public static final Map<Level, String> LEVEL_TO_STRING;

    static {
        Map<Level, String> map = new HashMap<>();
        map.put(Level.SEVERE, "error");
        map.put(Level.WARNING, "warn");
        map.put(Level.INFO, "info");
        map.put(Level.CONFIG, "debug");
        map.put(Level.FINE, "debug");
        map.put(Level.FINER, "debug");
        map.put(Level.FINEST, "trace");
        LEVEL_TO_STRING = Map.copyOf(map); // immutable map
    }

    private final StreamObserver<LogMessage> stream;
    private final String[] entity;
    private final String uri;

    public GrpcLogHandler(RunnerGrpc.RunnerStub stub, String uri, String... entity) {
        this.stream = stub.logStream(this);
        this.uri = uri;
        this.entity = entity;
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }

        var msg = LogMessage.newBuilder()
                .setLevel(LEVEL_TO_STRING.get(record.getLevel()))
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
