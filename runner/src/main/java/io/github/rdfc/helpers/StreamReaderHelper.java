package io.github.rdfc.helpers;

import java.util.concurrent.CompletableFuture;
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
    private int at = 0;

    public final CompletableFuture<Void> endingFuture = new CompletableFuture<>();
    public final Logger logger;

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
                .setStreamSequenceNumber(this.at++)
                .build();
        this.sendingStream.onNext(control);
    }

    /**
     * Each incoming chunk is forwarded to the consuming stream.
     */
    @Override
    public void onNext(DataChunk value) {
        this.logger.finest("Receiving message StreamReaderHelper : " + value.getAllFields().keySet().toString());
        this.consumingStream.chunk(value.getData());
    }

    @Override
    public void onError(Throwable t) {
        this.logger.severe("Error " + t);
        this.consumingStream.close().thenAccept(_void -> {
            this.endingFuture.complete(null);
        });
    }

    @Override
    public void onCompleted() {
        this.logger.finest("onCompleted");
        this.consumingStream.close().thenAccept(_void -> {
            this.endingFuture.complete(null);
        });
    }
}
