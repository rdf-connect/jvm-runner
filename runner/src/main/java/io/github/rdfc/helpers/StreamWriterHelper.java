package io.github.rdfc.helpers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
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

    /**
     * The future that the next incoming ReceivingStreamControl completes.
     *
     * Chunks are sent from producing threads while the control messages arrive on
     * gRPC callback threads, so the accesses go through an AtomicReference. The
     * first one is completed by the control message answering the identify.
     */
    private final AtomicReference<CompletableFuture<Void>> nextProcessed = new AtomicReference<>(
            new CompletableFuture<>());

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
        // Grab the future before identifying: the answering control message can
        // arrive before identify returns.
        var identified = self.nextProcessed.get();
        self.identify(channel, runner);
        return identified.thenApply(_ignored -> self);
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
        if (this.logger.isLoggable(Level.FINEST)) {
            this.logger.finest("Receiving message StreamWriterHelper : " + value.getAllFields().keySet().toString());
        }
        // complete returns false when this future already completed, which means the
        // control message has no chunk waiting for it.
        if (!this.nextProcessed.get().complete(null)) {
            this.logger
                    .severe("Expected a waiting nextProcessed future, has this been already handled? : "
                            + value.getAllFields().keySet().toString());
        }
    }

    /**
     * The stream carrying this message failed.
     *
     * Two futures can have somebody waiting on them: the one the next
     * ReceivingStreamControl would complete, which is what {@link #chunk} handed to
     * the producer, and the one {@link #close} waits on. Both are failed — a
     * producer that keeps waiting for a control message on a dead stream never
     * finishes, and the phase it belongs to never ends.
     */
    @Override
    public void onError(Throwable t) {
        this.logger.severe("The stream message failed: " + Errors.describe(t));

        // Both are no-ops when nothing was waiting on them
        this.nextProcessed.get().completeExceptionally(t);
        this.acknowledged.completeExceptionally(t);
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

        var out = new CompletableFuture<Void>();
        // Install the future _before_ sending: the ReceivingStreamControl answering
        // this chunk can arrive on a gRPC thread before this method returns and
        // would otherwise be dropped, leaving the producer waiting forever.
        var previous = this.nextProcessed.getAndSet(out);
        if (!previous.isDone()) {
            this.logger.severe("Next processed is still not done, are chunks sent too fast?");
        }

        this.sendingStream.onNext(builder.build());
        return out;
    }

    @Override
    public CompletableFuture<Void> close() {
        this.logger.finest("Streaming message close");
        this.sendingStream.onCompleted();
        return this.acknowledged;
    }
}
