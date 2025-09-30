package io.github.rdfc.helpers;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import io.github.rdfc.Stream;
import io.grpc.stub.StreamObserver;
import rdfc.Common.DataChunk;
import rdfc.Common.ReceivingStreamControl;
import rdfc.Common.StreamChunk;
import rdfc.Common.StreamIdentify;
import rdfc.RunnerGrpc.RunnerStub;

public class StreamWriterHelper extends Stream<ByteString>
        implements StreamObserver<ReceivingStreamControl> {

    private final StreamObserver<StreamChunk> sendingStream;
    private final CompletableFuture<Void> awaitId = new CompletableFuture<>();
    private final Logger logger;

    private Optional<CompletableFuture<Void>> nextProcessed = Optional.empty();

    private StreamWriterHelper(RunnerStub stub, Logger logger) {
        this.logger = logger;
        this.sendingStream = new StreamObserverWrapper<>(stub.sendStreamMessage(this), "StreamWriterHelper", logger);
    }

    public static CompletableFuture<StreamWriterHelper> build(RunnerStub stub, String channel, String runner,
            Logger logger) {
        var self = new StreamWriterHelper(stub, logger);
        self.identify(channel, runner);
        return self.awaitId.thenApply(_ignored -> self);
    }

    private void identify(String channel, String runner) {
        var idMsg = StreamIdentify.newBuilder().setChannel(channel).setRunner(runner).build();
        var builder = StreamChunk.newBuilder().setId(idMsg).build();
        this.sendingStream.onNext(builder);
    }

    @Override
    public void onNext(ReceivingStreamControl value) {
        this.logger.finest("Receiving message StreamWriterHelper : " + value.getAllFields().keySet().toString());
        if (this.awaitId.isDone()) {
            if (this.nextProcessed.isPresent()) {
                var fut = this.nextProcessed.get();
                this.nextProcessed = Optional.empty();
                fut.complete(null);
            } else {
                this.logger
                        .severe("Expected a waiting nextProcessed future, has this been already handled? : "
                                + value.getAllFields().keySet().toString());
            }
        } else {
            this.awaitId.complete(null);
        }
    }

    @Override
    public void onError(Throwable t) {
        throw new UnsupportedOperationException("Unimplemented method 'onError'");
    }

    @Override
    public void onCompleted() {
        this.sendingStream.onCompleted();
    }

    @Override
    public CompletableFuture<Void> chunk(ByteString chunk) {
        var builder = StreamChunk.newBuilder();
        var chunkMsg = DataChunk.newBuilder().setData(chunk).build();
        builder.setData(chunkMsg);

        this.sendingStream.onNext(builder.build());
        if (this.nextProcessed.isPresent()) {
            // Log error
            this.logger.severe("Next processed is already set (streaming message)");
        }

        var out = new CompletableFuture<Void>();
        this.nextProcessed = Optional.of(out);
        return out;
    }

    @Override
    public CompletableFuture<Void> close() {
        this.logger.finest("Streaming message close");
        this.sendingStream.onCompleted();
        return CompletableFuture.completedFuture(null);
    }
}
