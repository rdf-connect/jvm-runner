package io.github.rdfc;

import java.util.logging.*;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.protobuf.ByteString;

import io.github.rdfc.helpers.StreamWriterHelper;

/**
 * Writer
 */
public class Writer extends IWriter {
    private Runner runner;
    public String id;

    /**
     * Message-level acknowledgements, matched in FIFO order rather than by the
     * protocol's localSequenceNumber -- same as the js-runner's
     * awaitingProcessed, and the two should agree.
     */
    private final Queue<CompletableFuture<Void>> awaitingProcessed = new ConcurrentLinkedQueue<>();

    /** A late acknowledgement after a close is a race, not a fault. */
    private volatile boolean closed = false;

    private final Logger logger;

    public Writer(String id, Runner runner, Logger logger) {
        this.id = id;
        this.runner = runner;
        this.logger = logger;
    }

    @Override
    public String id() {
        return this.id;
    }

    /**
     * Send a message and only complete when a processed message is received
     */
    @Override
    public CompletableFuture<Void> chunk(ByteString chunk) {
        var out = new CompletableFuture<Void>();

        // Enqueue before sending: the ack arrives on a gRPC thread and can beat
        // this method's return.
        this.awaitingProcessed.add(out);
        this.runner.sendMessage(this.id, chunk);

        return out;
    }

    @Override
    public CompletableFuture<Stream<ByteString>> stream() {
        var acknowledged = new CompletableFuture<Void>();

        // Queued before sending, as in chunk().
        this.awaitingProcessed.add(acknowledged);
        return StreamWriterHelper.build(runner.stub, this.id, this.runner.uri, acknowledged, this.logger)
                // Type fixing
                .thenApply(st -> st);
    }

    public CompletableFuture<Void> close() {
        this.closed = true;
        this.runner.closeChannel(this.id);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * A processed message was received, let the previous sending chunk complete.
     * 
     * @param sequenceNumber that is processed
     */
    public void processed(int sequenceNumber) {
        var fut = this.awaitingProcessed.poll();
        if (fut != null) {
            fut.complete(null);
        } else if (this.closed) {
            // Nothing left to complete.
        } else {
            this.logger.warning("Writer " + this.id + " didn't expect a processed message.");
        }
    }
}
