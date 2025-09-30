
package io.github.rdfc.helpers;

import java.util.logging.Logger;

import com.google.protobuf.GeneratedMessage.ExtendableMessage;

import io.grpc.stub.StreamObserver;

public class StreamObserverWrapper<T extends ExtendableMessage<?>> implements StreamObserver<T> {

    private final StreamObserver<T> wrapped;
    private final String tag;
    private final Logger logger;

    public StreamObserverWrapper(StreamObserver<T> wrapped, String tag, Logger logger) {
        this.wrapped = wrapped;
        this.tag = tag;
        this.logger = logger;
    }

    public StreamObserverWrapper(StreamObserver<T> wrapped, Logger logger) {
        this(wrapped, "unknown", logger);
    }

    @Override
    public void onNext(T value) {
        this.logger.finest("Sending message " + this.tag + ": " + value.getAllFields().keySet().toString());
        this.wrapped.onNext(value);

    }

    @Override
    public void onError(Throwable t) {
        this.wrapped.onError(t);
    }

    @Override
    public void onCompleted() {
        this.wrapped.onCompleted();
    }

}
