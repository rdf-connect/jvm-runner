package io.github.rdfc.helpers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import io.github.rdfc.Reader;
import io.github.rdfc.Stream;
import io.grpc.stub.StreamObserver;
import rdfc.Common;
import rdfc.Common.DataChunk;
import rdfc.Common.SendingStreamControl;
import rdfc.RunnerGrpc.RunnerStub;

public class StreamReaderHelper implements StreamObserver<Common.DataChunk> {
    private final Stream<ByteString> consumingStream;
    private final StreamObserver<SendingStreamControl> sendingStream;
    public final CompletableFuture<Void> endingFuture = new CompletableFuture<>();
    public final Logger logger;

    /**
     * The control messages are sent from whichever thread finished handling a
     * chunk, so the counter has to be atomic.
     */
    private final AtomicInteger at = new AtomicInteger(0);

    public StreamReaderHelper(Reader reader, RunnerStub stub, Logger logger) {
        this.logger = logger;
        this.consumingStream = reader.stream(this::sendStreamControlMessage);
        this.sendingStream = new StreamObserverWrapper<>(stub.receiveStreamMessage(this), "StreamReaderHelper", logger);
    }

    public void identify(int id) {
        var identify = SendingStreamControl.newBuilder();
        identify.setGlobalSequenceNumber(id);
        this.sendingStream.onNext(identify.build());
    }

    /**
     * Between each incoming message, sends an acknowledgement message back
     */
    public void sendStreamControlMessage() {
        SendingStreamControl control = SendingStreamControl.newBuilder()
                .setStreamSequenceNumber(this.at.getAndIncrement())
                .build();
        this.sendingStream.onNext(control);
    }

    /**
     * Each incoming chunk is forwarded to the consuming stream.
     */
    @Override
    public void onNext(DataChunk value) {
        if (this.logger.isLoggable(Level.FINEST)) {
            this.logger.finest("Receiving message StreamReaderHelper : " + value.getAllFields().keySet().toString());
        }
        this.consumingStream.chunk(value.getData());
    }

    /**
     * The stream carrying this message failed.
     *
     * The consumers are still closed, so they see an end of stream instead of
     * waiting for chunks that are not coming, but the ending future carries the
     * failure: the runner turns that future into the acknowledgement for this
     * message, and an acknowledgement without an error tells the orchestrator the
     * message was handled — which it was not.
     */
    @Override
    public void onError(Throwable t) {
        this.logger.severe("Error " + t);
        this.consumingStream.close().whenComplete((_void, e) -> {
            if (e != null) {
                this.logger.severe("Error closing stream after error: " + e);
                e.printStackTrace(System.err);
            }
            this.endingFuture.completeExceptionally(t);
        });
    }

    @Override
    public void onCompleted() {
        this.logger.finest("onCompleted");
        this.consumingStream.close().whenComplete((_void, e) -> {
            if (e != null) {
                this.logger.severe("Error closing stream: " + e);
                e.printStackTrace(System.err);
                // A consumer that failed on the last chunks failed to handle this
                // message, exactly like one that fails on a plain message does
                this.endingFuture.completeExceptionally(e);
                return;
            }
            this.endingFuture.complete(null);
        });
    }
}
