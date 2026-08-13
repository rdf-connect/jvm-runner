
package io.github.rdfc.helpers;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.protobuf.MessageOrBuilder;

import io.grpc.stub.StreamObserver;

/**
 * Simple wrapper for StreamObservers logging all outgoing messages.
 *
 * gRPC request observers are <em>not</em> thread-safe: the calls onto a single
 * observer have to be serialized by the caller. This runner produces outgoing
 * messages from processor threads, from CompletableFuture completion threads
 * and from the gRPC callback threads, so every outgoing observer is wrapped in
 * one of these and all three methods are synchronized on this wrapper.
 *
 * The lock is a leaf, but only under an <em>asynchronous</em> transport: nothing
 * is called while holding it except the wrapped observer, and the assumption is
 * that handing a message to the transport never delivers an incoming message on
 * that same thread. That holds for the Netty transport this runner uses, where
 * inbound messages arrive on the channel's event loop.
 *
 * It does <em>not</em> hold for a transport with a direct executor, which can
 * deliver a response from inside the send. Two threads sending on two different
 * streams could then each be waiting for the other's lock:
 *
 * <pre>
 * A: holds L(connect)   -&gt; ack delivered inline -&gt; continuation sends a stream chunk -&gt; wants L(stream)
 * B: holds L(stream)    -&gt; control delivered inline -&gt; continuation sends a message  -&gt; wants L(connect)
 * </pre>
 *
 * So do not configure a direct executor on the channel. Delivering responses on
 * the sending thread is something only the tests do, and only on a single
 * stream, where the lock is simply re-entered.
 */
public class StreamObserverWrapper<T> implements StreamObserver<T> {

    private final StreamObserver<T> wrapped;
    private final String tag;
    /**
     * May be null, in which case nothing is logged. See {@link #silent}.
     */
    private final Logger logger;

    public StreamObserverWrapper(StreamObserver<T> wrapped, String tag, Logger logger) {
        this.wrapped = wrapped;
        this.tag = tag;
        this.logger = logger;
    }

    public StreamObserverWrapper(StreamObserver<T> wrapped, Logger logger) {
        this(wrapped, "unknown", logger);
    }

    /**
     * A wrapper that serializes the calls but logs nothing.
     *
     * This is what the log stream itself uses: logging an outgoing log message
     * would publish another log record on the very same stream and recurse.
     *
     * @param <T>     type of the outgoing messages
     * @param wrapped the observer to serialize the calls on
     * @return the wrapping observer
     */
    public static <T> StreamObserverWrapper<T> silent(StreamObserver<T> wrapped) {
        return new StreamObserverWrapper<>(wrapped, "silent", null);
    }

    @Override
    public void onNext(T value) {
        // Logging goes through another stream (and so through another one of these
        // wrappers), so it happens before taking this lock: that keeps this lock a
        // leaf and rules out a lock cycle between the log stream and this one.
        this.log(value);

        synchronized (this) {
            this.wrapped.onNext(value);
        }
    }

    private void log(T value) {
        if (this.logger == null || !this.logger.isLoggable(Level.FINEST)) {
            return;
        }

        // Only protobuf messages can describe which fields they carry, anything else
        // is logged by its tag alone.
        if (value instanceof MessageOrBuilder) {
            this.logger.finest("Sending message " + this.tag + ": "
                    + ((MessageOrBuilder) value).getAllFields().keySet().toString());
        } else {
            this.logger.finest("Sending message " + this.tag);
        }
    }

    @Override
    public synchronized void onError(Throwable t) {
        this.wrapped.onError(t);
    }

    @Override
    public synchronized void onCompleted() {
        this.wrapped.onCompleted();
    }

}
