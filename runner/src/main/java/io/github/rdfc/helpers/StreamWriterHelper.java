package io.github.rdfc.helpers;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.protobuf.ByteString;

import io.github.rdfc.Stream;
import io.grpc.stub.StreamObserver;
import rdfc.Common.DataChunk;
import rdfc.Common.StreamChunk;
import rdfc.Common.StreamIdentify;
import rdfc.RunnerGrpc.RunnerStub;
import rdfc.Service.StreamControl;

public class StreamWriterHelper extends Stream<ByteString>
        implements StreamObserver<StreamControl> {

    private final StreamObserver<StreamChunk> sendingStream;
    private final CompletableFuture<Void> awaitId = new CompletableFuture<>();

    private Optional<CompletableFuture<Void>> nextProcessed = Optional.empty();

    private StreamWriterHelper(RunnerStub stub) {
        this.sendingStream = stub.sendStreamMessage(this);
    }

    public static CompletableFuture<StreamWriterHelper> build(RunnerStub stub, String id) {
        var self = new StreamWriterHelper(stub);
        self.identify(id);
        return self.awaitId.thenApply(_ignored -> self);
    }

    private void identify(String id) {
        var idMsg = StreamIdentify.newBuilder().setChannel(id).build();
        var builder = StreamChunk.newBuilder().setId(idMsg).build();
        this.sendingStream.onNext(builder);
    }

    @Override
    public void onNext(StreamControl value) {
        if (value.hasId()) {
            if (this.awaitId.isDone()) {
                // Log error message
            }

            this.awaitId.complete(null);
        }
        if (value.hasProcessed()) {
            if (this.nextProcessed.isPresent()) {
                var fut = this.nextProcessed.get();
                fut.complete(null);
                this.nextProcessed = Optional.empty();
            } else {
                // Log error
            }
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
        }

        var out = new CompletableFuture<Void>();
        this.nextProcessed = Optional.of(out);
        return out;
    }

    @Override
    public CompletableFuture<Void> close() {
        this.sendingStream.onCompleted();
        return CompletableFuture.completedFuture(null);
    }
}
