package io.github.rdfc;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import java.util.logging.*;
import io.github.rdfc.json.ChannelHandlerModule;

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

    private final AtomicInteger awaiting = new AtomicInteger(0);
    private final Runnable onComplete;
    private final Logger logger;

    protected final String uri;

    public Runner(RunnerGrpc.RunnerStub stub, String uri, Runnable onComplete) {
        this.uri = uri;
        this.stream = stub.connect(this);
        this.logger = GrpcLogHandler.createLogger(stub, uri, "cli");

        this.onComplete = onComplete;
        this.stub = stub;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new ChannelHandlerModule(this));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.logger.info("Hello from the runner!");
        this.sendIdentify(uri);
    }

    public void setReader(String uri, Reader reader) {
        this.channels.put(uri, reader);
    }

    private void decreaseAndCheckEnd() {
        var v = this.awaiting.decrementAndGet();
        if (v == 0) {
            this.stream.onCompleted();
            this.onComplete.run();
        }
    }

    @Override
    public void onNext(RunnerMessage value) {

        System.out.println("Got message " + value.getAllFields().keySet().toString());

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
                    this.decreaseAndCheckEnd();
                });
            });

            System.out.println("Start happened");
            return;
        }

        if (value.hasProc()) {
            System.out.println("Proc start message");
            var proc = value.getProc();
            var uri = proc.getUri();

            var procLogger = GrpcLogHandler.createLogger(stub, uri, this.uri);
            try {
                var latch = new CountDownLatch(1);
                Processor<?> processor = this.startProc(proc, procLogger);
                processor.init(_void -> {
                    this.awaiting.updateAndGet(x -> x + 2);
                    processor.transform(st -> {
                        System.out.println("Processor transformed " + uri);
                        this.decreaseAndCheckEnd();
                    });
                    latch.countDown();
                });
                latch.await();

                System.out.println("Sending proc init " + uri);
                this.sendProcInit(uri, Optional.empty());
            } catch (Exception e) {
                e.printStackTrace();
                this.sendProcInit(uri, Optional.of(e.toString()));
            }

            return;
        }
        System.err.println("Unsupported message " + value.getUnknownFields());
    }

    private Processor<?> startProc(rdfc.Runner.Processor proc, Logger logger) throws Exception {
        var uri = proc.getUri();
        var config = proc.getConfig();
        var params = proc.getArguments();
        System.out.println("Trying to start proc " + uri + " " + config + " " + params);

        var arg = mapper.readValue(config, Config.class);
        var processor = arg.loadClass(this, params, logger);

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

        Processor<?> loadClass(Runner runner, String arguments, Logger logger) throws Exception {
            URL jarUrl = new URI(this.jar).toURL();
            URLClassLoader loader = new URLClassLoader(new URL[] { jarUrl }, Rdfc.class.getClassLoader());
            Class<?> clazz = loader.loadClass(this.clazz);

            var mapper = new ObjectMapper();
            mapper.registerModule(new ChannelHandlerModule(runner));
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(loader));

            // Find constructor with one argument
            Constructor<?> constructor = null;
            for (Constructor<?> c : clazz.getConstructors()) {
                if (c.getParameterCount() == 2) {
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
            return (Processor<?>) constructor.newInstance(arg, logger);
        }
    }
}
