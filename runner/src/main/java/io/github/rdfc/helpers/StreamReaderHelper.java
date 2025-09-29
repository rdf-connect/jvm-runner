package io.github.rdfc.helpers;

import java.util.function.Consumer;

import com.google.protobuf.ByteString;

import io.github.rdfc.Reader;
import io.github.rdfc.Stream;
import io.grpc.stub.StreamObserver;
import rdfc.Common;
import rdfc.Common.DataChunk;
import rdfc.RunnerGrpc.RunnerStub;
import rdfc.Service.StreamControl;

public class StreamReaderHelper implements StreamObserver<Common.DataChunk>, Consumer<Void> {
    private final Stream<ByteString> consumingStream;
    private final StreamObserver<StreamControl> sendingStream;
    private int at = 0;

    public StreamReaderHelper(Reader reader, RunnerStub stub) {
        this.consumingStream = reader.stream(this);
        this.sendingStream = stub.receiveStreamMessage(this);
    }

    public void identify(int id) {
        var identify = StreamControl.newBuilder();
        identify.setId(id);
        this.sendingStream.onNext(identify.build());
    }

    /**
     * Between each incoming message, sends an acknowledgement message back
     */
    @Override
    public void accept(Void t) {
        StreamControl control = StreamControl.newBuilder()
                .setProcessed(this.at++)
                .build();
        this.sendingStream.onNext(control);
    }

    /**
     * Each incoming chunk is forwarded to the consuming stream.
     */
    @Override
    public void onNext(DataChunk value) {
        this.consumingStream.chunk(value.getData());
    }

    @Override
    public void onError(Throwable t) {
        this.consumingStream.close();
    }

    @Override
    public void onCompleted() {
        this.consumingStream.close();
    }

}
