package org.example;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.example.json.ChannelHandlerModule;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;
import rdfc.Common;
import rdfc.Common.Close;
import rdfc.Common.DataChunk;
import rdfc.Common.Id;
import rdfc.Common.Message;
import rdfc.Common.StreamMessage;
import rdfc.Orchestrator.OrchestratorMessage;
import rdfc.Orchestrator.ProcessorInit;
import rdfc.Runner.RunnerMessage;
import rdfc.RunnerGrpc;
import rdfc.RunnerGrpc.RunnerStub;

/**
 * Runner
 */
public class Runner implements StreamObserver<RunnerMessage> {

    public StreamObserver<OrchestratorMessage> stream;

    protected RunnerGrpc.RunnerStub stub;

    protected HashMap<String, Reader> channels = new HashMap<>();
    protected HashMap<String, Processor<?>> processors = new HashMap<>();

    protected final ObjectMapper mapper;

    public Runner(RunnerGrpc.RunnerStub stub, String uri) {
        this.stream = stub.connect(this);
        this.stub = stub;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new ChannelHandlerModule(this));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.sendIdentify(uri);
    }

    public void setReader(String uri, Reader reader) {
        this.channels.put(uri, reader);
    }

    @Override
    public void onNext(RunnerMessage value) {
        if (value.hasPipeline()) {
            System.out.println("Pipeline message");
            return;
        }
        if (value.hasMsg()) {
            System.out.println("Msg message");
            var msg = value.getMsg();
            var data = msg.getData();
            var reader = this.channels.get(msg.getChannel());
            if (reader != null) {
                reader.msg(data);
            } else {
                System.out.println("Channel " + msg.getChannel() + " not present.");
            }
            return;
        }

        if (value.hasStreamMsg()) {
            System.out.println("Stream Msg message");
            var msg = value.getStreamMsg();
            var id = msg.getId();

            var channel = this.channels.get(msg.getChannel());
            if (channel != null) {
                this.stub.receiveStreamMessage(id, channel.stream());
            } else {
                System.out.println("Channel " + msg.getChannel() + " not present.");
            }

            return;
        }

        if (value.hasClose()) {
            System.out.println("Close message");
            var msg = value.getClose();
            var channel = this.channels.get(msg.getChannel());
            if (channel != null) {
                channel.close();
            } else {
                System.out.println("Channel " + msg.getChannel() + " not present.");
            }

            return;
        }

        if (value.hasStart()) {
            System.out.println("Start message");
            this.processors.forEach((k, v) -> {
                v.produce(st -> {
                    System.out.println("Processor Produced " + k);
                    // TODO: do something
                });
            });
            return;
        }

        if (value.hasProc()) {
            System.out.println("Proc start message");
            var proc = value.getProc();
            var uri = proc.getUri();
            try {
                Processor<?> processor = this.startProc(proc);
                processor.init(_void -> {
                    processor.transform(st -> {
                        System.out.println("Processor transformed " + uri);
                        // TODO: do something
                    });
                    this.sendProcInit(uri, Optional.empty());
                });
            } catch (Exception e) {
                e.printStackTrace();
                this.sendProcInit(uri, Optional.of(e.toString()));
            }
            return;
        }

        System.err.println("Unsupported message " + value.getUnknownFields());
    }

    private Processor<?> startProc(rdfc.Runner.Processor proc) throws Exception {
        var uri = proc.getUri();
        var config = proc.getConfig();
        var params = proc.getArguments();
        System.out.println("Trying to start proc " + uri + " " + config + " " + params);

        var arg = mapper.readValue(config, Config.class);
        var processor = arg.loadClass(this, params);

        this.processors.put(uri, processor);

        return processor;
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

    void sendIdentify(String uri) {
        var builder = OrchestratorMessage.newBuilder();
        builder.setIdentify(rdfc.Orchestrator.Identify.newBuilder().setUri(uri));
        this.stream.onNext(builder.build());
    }

    void sendProcInit(String uri, Optional<String> error) {
        var builder = OrchestratorMessage.newBuilder();
        var initBuilder = ProcessorInit.newBuilder();
        initBuilder.setUri(uri);
        error.ifPresent(st -> initBuilder.setError(rdfc.Common.Error.newBuilder().setCause(st)));

        builder.setInit(initBuilder);
        this.stream.onNext(builder.build());
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

    private static class Config {
        public String jar;
        public String clazz;

        Processor<?> loadClass(Runner runner, String arguments) throws Exception {
            URL jarUrl = new URI(this.jar).toURL();
            try (URLClassLoader loader = new URLClassLoader(new URL[] { jarUrl }, App.class.getClassLoader())) {
                Class<?> clazz = loader.loadClass(this.clazz);

                var mapper = new ObjectMapper();
                mapper.registerModule(new ChannelHandlerModule(runner));
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(loader));
                // Find constructor with one argument
                Constructor<?> constructor = null;
                for (Constructor<?> c : clazz.getConstructors()) {
                    if (c.getParameterCount() == 1) {
                        constructor = c;
                        break;
                    }
                }
                if (constructor == null) {
                    throw new RuntimeException("No single-arg constructor found");
                }

                Class<?> paramType = constructor.getParameterTypes()[0];

                System.out.println("Found type " + paramType.getTypeName());

                // Use Jackson to deserialize JSON into the param type
                Object arg = mapper.readValue(arguments, paramType);

                // Instantiate using default constructor
                constructor.setAccessible(true);
                return (Processor<?>) constructor.newInstance(arg);
            }
        }
    }
}
