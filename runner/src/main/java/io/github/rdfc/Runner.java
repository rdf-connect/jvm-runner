package io.github.rdfc;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;

import java.util.logging.*;

import io.github.rdfc.helpers.StreamReaderHelper;
import io.github.rdfc.json.ChannelHandlerModule;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;
import rdfc.Common.Close;
import rdfc.Common.Message;
import rdfc.Orchestrator.OrchestratorMessage;
import rdfc.Orchestrator.ProcessorInit;
import rdfc.Runner.RunnerMessage;
import rdfc.RunnerGrpc;

/**
 * Runner
 */
public class Runner implements StreamObserver<RunnerMessage> {

    public StreamObserver<OrchestratorMessage> stream;

    protected RunnerGrpc.RunnerStub stub;

    protected HashMap<String, Reader> readers = new HashMap<>();
    protected HashMap<String, Writer> writers = new HashMap<>();
    protected HashMap<String, Processor<?>> processors = new HashMap<>();

    protected final ObjectMapper mapper;

    private final AtomicInteger awaiting = new AtomicInteger(0);
    private final Runnable onComplete;
    private final Logger logger;

    protected final String uri;

    public Runner(RunnerGrpc.RunnerStub stub, String uri, Runnable onComplete) {

        var logger = Logger.getLogger("");
        logger.setLevel(Level.ALL);
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
        }

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
        this.readers.put(uri, reader);
    }

    public void setWriter(String uri, Writer writer) {
        this.writers.put(uri, writer);
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

        this.logger.fine("Got message " + value.getAllFields().keySet().toString());

        if (value.hasPipeline()) {
            return;
        }

        if (value.hasMsg()) {
            var msg = value.getMsg();
            var data = msg.getData();
            var reader = this.readers.get(msg.getChannel());
            if (reader != null) {
                reader.msg(data);
            } else {
                this.logger.finest("Channel " + msg.getChannel() + " not present.");
            }
            return;
        }

        if (value.hasProcessed()) {
            var processed = value.getProcessed();
            var channel = processed.getChannel();
            var tick = processed.getTick();
            var writer = this.writers.get(channel);
            if (writer != null) {
                writer.processed(tick);

            } else {
                this.logger.finest("Channel " + channel + " not present.");
            }
        }

        if (value.hasStreamMsg()) {
            var msg = value.getStreamMsg();
            var id = msg.getId();

            var reader = this.readers.get(msg.getChannel());
            if (reader != null) {
                var helper = new StreamReaderHelper(reader, this.stub);
                helper.identify(id);
            } else {
                this.logger.finest("Channel " + msg.getChannel() + " not present.");
            }

            return;
        }

        if (value.hasClose()) {
            var msg = value.getClose();

            var channel = this.readers.get(msg.getChannel());
            if (channel != null) {
                channel.close();
            } else {
                this.logger.finest("Channel " + msg.getChannel() + " not present.");
            }

            return;
        }

        if (value.hasStart()) {
            this.processors.forEach((k, v) -> {
                v.produce().thenAccept(st -> {
                    this.logger.fine("Processor " + k + " finished producing.");
                    this.decreaseAndCheckEnd();
                });
            });

            return;
        }

        if (value.hasProc()) {
            var proc = value.getProc();
            var uri = proc.getUri();

            var procLogger = GrpcLogHandler.createLogger(stub, uri, this.uri);
            try {
                var latch = new CountDownLatch(1);
                Processor<?> processor = this.startProc(proc, procLogger);
                processor.init().thenAccept(_void -> {
                    this.awaiting.updateAndGet(x -> x + 2);
                    processor.transform().thenAccept(st -> {
                        this.logger.fine("Processor " + uri + " finished transforming.");
                        this.decreaseAndCheckEnd();
                    });
                    latch.countDown();
                });
                latch.await();

                this.sendProcInit(uri, Optional.empty());
            } catch (Exception e) {
                e.printStackTrace();
                this.sendProcInit(uri, Optional.of(e.toString()));
            }

            return;
        }
        System.err.println("Unsupported message " + value.getUnknownFields());
        this.logger.severe("Unsupported message " + value.getUnknownFields());
    }

    private Processor<?> startProc(rdfc.Runner.Processor proc, Logger logger) throws Exception {
        var uri = proc.getUri();
        var config = proc.getConfig();
        var params = proc.getArguments();

        var arg = mapper.readValue(config, Config.class);
        var processor = arg.loadClass(this, params, logger);

        this.processors.put(uri, processor);

        return processor;
    }

    @Override
    public void onError(Throwable t) {
        throw new UnsupportedOperationException("Unimplemented method 'onError'");
    }

    @Override
    public void onCompleted() {
        System.err.println("onCompleted maybe I should do something");
        this.logger.severe("onCompleted maybe I should do something");
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

    private static class Config {
        public String jar;
        public String clazz;

        Processor<?> loadClass(Runner runner, String arguments, Logger logger) throws Exception {
            URL jarUrl = new URI(this.jar).toURL();

            Path jarPath;
            if (jarUrl.getProtocol().equalsIgnoreCase("http") || jarUrl.getProtocol().equalsIgnoreCase("https")) {
                // Remote JAR, download it
                logger.info("Downloading JAR from " + this.jar);
                // Download JAR to temp dir
                jarPath = Files.createTempFile("remote-lib", ".jar");
                try (InputStream in = jarUrl.openStream()) {
                    Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
                }
                logger.info("End download");
            } else {
                // Local JAR, use it directly
                jarPath = Path.of(jarUrl.toURI());
            }

            // Use local URLClassLoader
            URLClassLoader loader = new URLClassLoader(new URL[] { jarPath.toUri().toURL() });

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

            // Use Jackson to deserialize JSON into the param type
            Object arg = mapper.readValue(arguments, paramType);

            // Instantiate using default constructor
            constructor.setAccessible(true);
            return (Processor<?>) constructor.newInstance(arg, logger);
        }
    }
}
