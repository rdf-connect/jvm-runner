package io.github.rdfc.helpers;

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
    private final Logger logger;
    private final CompletableFuture<Void> acknowledged;

    private CompletableFuture<Void> nextProcessed = new CompletableFuture<>();;

    private StreamWriterHelper(RunnerStub stub, CompletableFuture<Void> acknowledged, Logger logger) {
        this.logger = logger;
        this.acknowledged = acknowledged;
        this.sendingStream = new StreamObserverWrapper<>(stub.sendStreamMessage(this), "StreamWriterHelper", logger);
    }

    /**
     * Creates a StreamWriterHelper that is ready to receive the first stream
     * acknowledged
     * message chunk.
     * 
     * @param stub         connecting to the orchestrator
     * @param channel      of the stream message
     * @param runner       URI
     * @param awknowledged CompletableFuture that resolves when a processed message
     *                     is received
     * @param logger       the logger
     * @return future that resolves when the globalSequenceNumber is received
     */
    public static CompletableFuture<StreamWriterHelper> build(RunnerStub stub, String channel, String runner,
            CompletableFuture<Void> awknowledged, Logger logger) {
        var self = new StreamWriterHelper(stub, awknowledged, logger);
        self.identify(channel, runner);
        return self.nextProcessed.thenApply(_ignored -> self);
    }

    /**
     * The first message that should be sent to the orchestrator is a message
     * identifying this stream.
     * 
     * @param channel of the stream message
     * @param runner  URI of the runner
     */
    private void identify(String channel, String runner) {
        var idMsg = StreamIdentify.newBuilder().setChannel(channel).setRunner(runner).build();
        var builder = StreamChunk.newBuilder().setId(idMsg).build();
        this.sendingStream.onNext(builder);
    }

    /**
     * Handles incoming ReceivingStreamControl value, this indicates that a new part
     * of the streaming message can be generated.
     * There is a future called `nextProcessed` that is resolved for each incoming
     * message.
     * 
     * @param value not really used, contains the streamSequenceNumber
     */
    @Override
    public void onNext(ReceivingStreamControl value) {
        this.logger.finest("Receiving message StreamWriterHelper : " + value.getAllFields().keySet().toString());
        if (!this.nextProcessed.isDone()) {
            this.nextProcessed.complete(null);
        } else {
            this.logger
                    .severe("Expected a waiting nextProcessed future, has this been already handled? : "
                            + value.getAllFields().keySet().toString());
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

    /**
     * Sends the next stream chunk, await
     * 
     * @param chunk of the stream message
     * @return a promise that is fulfilled with the next ReceivingStreamControl is
     *         received
     */
    @Override
    public CompletableFuture<Void> chunk(ByteString chunk) {
        var builder = StreamChunk.newBuilder();
        var chunkMsg = DataChunk.newBuilder().setData(chunk).build();
        builder.setData(chunkMsg);

        this.sendingStream.onNext(builder.build());
        if (!this.nextProcessed.isDone()) {
            this.logger.severe("Next processed is still not done, are chunks sent too fast?");
        }

        var out = new CompletableFuture<Void>();
        this.nextProcessed = out;
        return out;
    }

    @Override
    public CompletableFuture<Void> close() {
        this.logger.finest("Streaming message close");
        this.sendingStream.onCompleted();
        return this.acknowledged;
    }
}
