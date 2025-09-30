package io.github.rdfc;

import java.util.logging.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.protobuf.ByteString;

import io.github.rdfc.helpers.StreamWriterHelper;

/**
 * Writer
 */
public class Writer extends IWriter {
    private Runner runner;
    public String id;

    private Optional<CompletableFuture<Void>> nextProcessed = Optional.empty();
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

    @Override
    public CompletableFuture<Void> chunk(ByteString chunk) {
        this.runner.sendMessage(this.id, chunk);

        if (this.nextProcessed.isPresent()) {
            this.logger.warning(
                    "Writer " + this.id + ": don't send a new chunk when the previous chunk is not yet awaited.");
        }

        var out = new CompletableFuture<Void>();
        this.nextProcessed = Optional.of(out);
        return out;
    }

    @Override
    public CompletableFuture<Stream<ByteString>> stream() {
        return StreamWriterHelper.build(runner.stub, this.id, this.runner.uri, this.logger).thenApply(st -> st);
    }

    public CompletableFuture<Void> close() {
        this.runner.closeChannel(this.id);
        return CompletableFuture.completedFuture(null);
    }

    public void processed(int sequenceNumber) {
        if (this.nextProcessed.isPresent()) {
            var fut = this.nextProcessed.get();
            fut.complete(null);
            this.nextProcessed = Optional.empty();
        } else {
            this.logger.warning("Writer " + this.id + " didn't expect a processed message.");
        }
    }
}
