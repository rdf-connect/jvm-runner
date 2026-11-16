package io.github.rdfc;

import java.util.logging.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.ByteString;

import io.github.rdfc.helpers.StreamWriterHelper;

/**
 * Writer
 */
public class Writer extends IWriter {
    private Runner runner;
    public String id;

    /**
     * The future that the next incoming acknowledgement completes, or null when no
     * acknowledgement is expected.
     *
     * Written from the producing threads (chunk/stream) and read and cleared from
     * the gRPC callback thread that delivers the acknowledgement, so the accesses
     * go through an AtomicReference.
     */
    private final AtomicReference<CompletableFuture<Void>> nextProcessed = new AtomicReference<>();
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
        // Install the future _before_ sending: the orchestrator can acknowledge the
        // message on a gRPC thread before this method returns, and that
        // acknowledgement would be dropped if it found no future to complete,
        // leaving the producer waiting forever.
        this.warnWhenPending(this.nextProcessed.getAndSet(out),
                "Writer " + this.id + ": don't send a new chunk when the previous chunk is not yet awaited.");

        this.runner.sendMessage(this.id, chunk);
        return out;
    }

    @Override
    public CompletableFuture<Stream<ByteString>> stream() {
        var acknowledged = new CompletableFuture<Void>();
        // Same as in chunk: installed before StreamWriterHelper.build sends anything.
        this.warnWhenPending(this.nextProcessed.getAndSet(acknowledged),
                "Writer " + this.id + ": don't start a new stream when the previous chunk is not yet awaited.");

        return StreamWriterHelper.build(runner.stub, this.id, this.runner.uri, acknowledged, this.logger)
                // Type fixing
                .thenApply(st -> st);
    }

    /**
     * Warns when a previously installed acknowledgement future was replaced before
     * it ever completed, that one will never complete now.
     *
     * @param previous the replaced future, may be null
     * @param message  what to warn about
     */
    private void warnWhenPending(CompletableFuture<Void> previous, String message) {
        if (previous != null && !previous.isDone()) {
            this.logger.warning(message);
        }
    }

    public CompletableFuture<Void> close() {
        this.runner.closeChannel(this.id);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * No acknowledgement is coming anymore, because the runner is being torn down.
     *
     * Whoever is producing on this channel is waiting for the future that the
     * acknowledgement would have completed, so it is failed instead: a producer
     * that keeps waiting keeps its thread and the runner's teardown pointless.
     *
     * @param error what ended the runner
     */
    void fail(Throwable error) {
        var pending = this.nextProcessed.getAndSet(null);
        if (pending != null) {
            pending.completeExceptionally(error);
        }
    }

    /**
     * A processed message was received, let the previous sending chunk complete.
     *
     * @param sequenceNumber that is processed
     */
    public void processed(int sequenceNumber) {
        // Take the future out and complete it in one step, a concurrent chunk() can
        // only ever install a new one.
        var fut = this.nextProcessed.getAndSet(null);
        if (fut != null) {
            fut.complete(null);
        } else {
            this.logger.warning("Writer " + this.id + " didn't expect a processed message.");
        }
    }
}
