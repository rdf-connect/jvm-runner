package org.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;
import rdfc.Orchestrator.OrchestratorMessage;
import rdfc.Runner.RunnerMessage;
import rdfc.RunnerGrpc.RunnerStub;
import rdfc.RunnerGrpc;
import rdfc.Common;
import rdfc.Common.Close;
import rdfc.Common.DataChunk;
import rdfc.Common.Id;
import rdfc.Common.Message;
import rdfc.Common.StreamMessage;

/**
 * Runner
 */
public class Runner implements StreamObserver<RunnerMessage> {

    public StreamObserver<OrchestratorMessage> stream;

    protected RunnerGrpc.RunnerStub stub;

    protected HashMap<String, Reader> channels = new HashMap<>();

    Runner(RunnerGrpc.RunnerStub stub) {
        this.stream = stub.connect(this);
        this.stub = stub;
    }

    @Override
    public void onNext(RunnerMessage value) {
        if (value.hasMsg()) {
            var msg = value.getMsg();
            var channel = msg.getChannel();
            var data = msg.getData();
        }

        if (value.hasStreamMsg()) {
            var msg = value.getStreamMsg();
            var id = msg.getId();

            var channel = this.channels.get(msg.getChannel());
            if (channel != null) {
                this.stub.receiveStreamMessage(id, channel.stream());
            }
        }

        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onNext'");
    }

    @Override
    public void onError(Throwable t) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onError'");
    }

    @Override
    public void onCompleted() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onCompleted'");
    }

    void sendMessage(String channel, ByteString data) {
        var builder = OrchestratorMessage.newBuilder();
        builder.setMsg(Message.newBuilder().setChannel(channel).setData(data));
        this.stream.onNext(builder.build());
    }

    void closeChannel(String channel) {
        var builder = OrchestratorMessage.newBuilder();
        builder.setClose(Close.newBuilder().setChannel(channel));
        this.stream.onNext(builder.build());
    }

    Consumer<Optional<ByteString>> sendStreamMessage(String channel) {
        return new StreamingMessage(this.stream, channel, this.stub);
    }

    private static class StreamingMessage implements StreamObserver<Common.Id>, Consumer<Optional<ByteString>> {
        private boolean idSet = false;
        private String channel;
        private StreamObserver<OrchestratorMessage> stream;
        private StreamObserver<DataChunk> dataStream;
        private List<Optional<ByteString>> buffer = new ArrayList<>();

        StreamingMessage(StreamObserver<OrchestratorMessage> stream, String channel, RunnerStub stub) {
            this.stream = stream;
            this.channel = channel;
            this.dataStream = stub.sendStreamMessage(this);
        }

        @Override
        public void accept(Optional<ByteString> t) {
            if (idSet) {
                t.ifPresentOrElse(
                        chunk -> {
                            this.dataStream.onNext(DataChunk.newBuilder().setData(chunk).build());
                        }, () -> {
                            this.dataStream.onCompleted();
                        });
            } else {
                this.buffer.add(t);
            }
        }

        @Override
        public void onNext(Id value) {
            // Send this id as a stream message
            var builder = OrchestratorMessage.newBuilder();
            builder.setStreamMsg(StreamMessage.newBuilder().setId(value).setChannel(this.channel));
            this.stream.onNext(builder.build());

            this.buffer.forEach(this::accept);
            idSet = true;
        }

        @Override
        public void onError(Throwable t) {
            throw new UnsupportedOperationException("Unimplemented method 'onError'");
        }

        @Override
        public void onCompleted() {
            throw new UnsupportedOperationException("Unimplemented method 'onCompleted'");
        }

    }
}
