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
import java.util.concurrent.atomic.*;

import java.util.logging.*;

import io.github.rdfc.helpers.StreamObserverWrapper;
import io.github.rdfc.helpers.StreamReaderHelper;
import io.github.rdfc.json.ChannelHandlerModule;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;
import rdfc.Common.Close;
import rdfc.Common.GlobalAck;
import rdfc.Common.SendingMessage;
import rdfc.RunnerGrpc;
import rdfc.Service.FromRunner;
import rdfc.Service.ProcessorInitialized;
import rdfc.Service.ToRunner;

/**
 * Runner
 */
public class Runner implements StreamObserver<ToRunner> {

    public final StreamObserver<FromRunner> stream;

    protected final RunnerGrpc.RunnerStub stub;

    protected final HashMap<String, Reader> readers = new HashMap<>();
    protected final HashMap<String, Writer> writers = new HashMap<>();
    protected final HashMap<String, Processor<?>> processors = new HashMap<>();

    /**
     * Mapper used to deserialize the config for each processor.
     */
    protected final ObjectMapper mapper;

    private final AtomicInteger awaiting = new AtomicInteger(0);
    /**
     * Function to call when all processors are finished.
     * This will close the GRPC channel.
     */
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
        this.logger = GrpcLogHandler.createLogger(stub, uri, "cli");
        this.stream = new StreamObserverWrapper<>(stub.connect(this), "main stream", this.logger);

        this.onComplete = onComplete;
        this.stub = stub;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new ChannelHandlerModule(this, this.logger));
        // The mapper can ignore properties like `@type`, `@context` from JSON-LD
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.sendIdentify();
        this.logger.info("JVM runner identified");
    }

    public void setReader(String uri, Reader reader) {
        this.readers.put(uri, reader);
    }

    public void setWriter(String uri, Writer writer) {
        this.writers.put(uri, writer);
    }

    /**
     * A processor is finished, if all processors are finished, we chan shut this
     * runner down.
     */
    private void decreaseAndCheckEnd() {
        var v = this.awaiting.decrementAndGet();
        if (v == 0) {
            this.stream.onCompleted();
            this.onComplete.run();
        }
    }

    @Override
    public void onNext(ToRunner value) {
        if (this.logger.isLoggable(Level.FINE)) {
            this.logger.fine("Got message " + value.getAllFields().keySet().toString());
        }

        if (value.hasPipeline()) {
            return;
        }

        if (value.hasMsg()) {
            var msg = value.getMsg();
            var reader = this.readers.get(msg.getChannel());
            if (reader != null) {
                reader.msg(msg.getData()).whenComplete((_void, e) -> {
                    if (e != null) {
                        this.logger.severe("Error handling message on channel " + msg.getChannel() + ": " + e);
                        e.printStackTrace(System.err);
                    }
                    // when the message has been handled, send an acknowledgement
                    // carrying the failure, if any
                    sendProcessed(msg.getChannel(), msg.getGlobalSequenceNumber(), e);
                });
            } else {
                this.logger.warning("Channel " + msg.getChannel() + " not present.");
            }
            return;
        }

        if (value.hasProcessed()) {
            var processed = value.getProcessed();
            var writer = this.writers.get(processed.getChannel());
            if (writer != null) {
                writer.processed(processed.getLocalSequenceNumber());

            } else {
                this.logger.warning("Channel " + processed.getChannel() + " not present.");
            }
            return;
        }

        if (value.hasStreamMsg()) {
            var msg = value.getStreamMsg();
            var globalSequenceNumber = msg.getGlobalSequenceNumber();
            var channel = msg.getChannel();

            var reader = this.readers.get(channel);
            if (reader != null) {
                // When a stream message comes in the StreamReaderHelper will connect to the
                // orchestrator and handle everything related to this stream message
                var helper = new StreamReaderHelper(reader, this.stub, this.logger);
                helper.identify(globalSequenceNumber);
                helper.endingFuture.whenComplete((_void, e) -> {
                    if (e != null) {
                        this.logger.severe("Error handling stream message on channel " + channel + ": " + e);
                        e.printStackTrace(System.err);
                    }
                    // When it is finished, we send an acknowledgement
                    // carrying the failure, if any
                    this.sendProcessed(channel, globalSequenceNumber, e);
                });
            } else {
                this.logger.warning("Channel " + channel + " not present.");
            }

            return;
        }

        if (value.hasClose()) {
            var msg = value.getClose();

            var channel = this.readers.get(msg.getChannel());
            if (channel != null) {
                channel.close();
            } else {
                this.logger.warning("Channel " + msg.getChannel() + " not present.");
            }

            return;
        }

        if (value.hasStart()) {
            this.processors.forEach((k, v) -> {
                // All processors are allowed to produce data
                // After the production is finished, check for end
                // (when starting the processor we added 2: one for transform and one for
                // produce)
                v.produce().whenComplete((output, e) -> {
                    if (e != null) {
                        this.logger.severe("Processor " + k + " produce exception: " + e);
                        e.printStackTrace(System.err);
                    }
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
                Processor<?> processor = this.startProc(proc, procLogger);
                processor.init().thenAccept(_void -> {
                    // one is decreased when the transform is finished
                    // one is decreased when the produce is finished
                    this.awaiting.updateAndGet(x -> x + 2);
                    processor.transform().whenComplete((output, e) -> {
                        if (e != null) {
                            this.logger.severe("Processor " + uri + " transform exception: " + e);
                            e.printStackTrace(System.err);
                        }
                        this.decreaseAndCheckEnd();
                    });
                    // This processor is initialized: init is awaited and transform is started
                    this.sendProcInit(uri, Optional.empty());
                }).exceptionally(e -> {
                    e.printStackTrace();
                    this.sendProcInit(uri, Optional.of(e.toString()));
                    return null;
                });
            } catch (Exception e) {
                e.printStackTrace();
                this.sendProcInit(uri, Optional.of(e.toString()));
            }

            return;
        }

        this.logger.severe("Unsupported message " + value.getUnknownFields());
    }

    /**
     * Sends a processed message to the orchestrator indicating that a message has
     * been handled.
     * This is either a _normal_ message or a streaming message
     * 
     * @param channel              that carried the message
     * @param globalSequenceNumber identifier of the message (per channel)
     * @param error                the exception that made handling the message
     *                             fail, or null when it was handled successfully
     */
    private void sendProcessed(String channel, int globalSequenceNumber, Throwable error) {
        var processed = GlobalAck.newBuilder();
        processed.setGlobalSequenceNumber(globalSequenceNumber);
        processed.setChannel(channel);
        if (error != null) {
            // Some exceptions carry no message, then fall back on the type name
            var cause = error.getMessage();
            processed.setError(cause != null ? cause : error.toString());
        }
        var orchestratorMessage = FromRunner.newBuilder().setProcessed(processed.build());
        this.stream.onNext(orchestratorMessage.build());
    }

    private Processor<?> startProc(rdfc.Service.Processor proc, Logger logger) throws Exception {
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
        this.logger.severe("onCompleted maybe I should do something");
    }

    /**
     * The runner is set up and the orchestrator is allowed to send processors to
     * this runner.
     */
    void sendIdentify() {
        var builder = FromRunner.newBuilder();
        builder.setIdentify(rdfc.Service.RunnerIdentify.newBuilder().setUri(this.uri));
        this.stream.onNext(builder.build());
    }

    /**
     * Initializing the processor is done
     * 
     * @param uri   of the processor
     * @param error potential error that was raised when starting the processor
     */
    void sendProcInit(String uri, Optional<String> error) {
        var builder = FromRunner.newBuilder();
        var initBuilder = ProcessorInitialized.newBuilder();
        initBuilder.setUri(uri);
        error.ifPresent(st -> initBuilder.setError(rdfc.Common.Error.newBuilder().setCause(st)));

        builder.setInitialized(initBuilder);
        this.stream.onNext(builder.build());
    }

    /**
     * Send a message to the default channel
     * 
     * @param channel to send the message to
     * @param data    data to send
     */
    void sendMessage(String channel, ByteString data) {
        var builder = FromRunner.newBuilder();
        builder.setMsg(SendingMessage.newBuilder().setChannel(channel).setData(data));
        this.stream.onNext(builder.build());
    }

    /**
     * Send a close msg to the default channel
     * 
     * @param channel to close
     */
    void closeChannel(String channel) {
        var builder = FromRunner.newBuilder();
        builder.setClose(Close.newBuilder().setChannel(channel));
        this.stream.onNext(builder.build());
    }

    /**
     * Configuration class that each processor implements.
     * It contains the jar location of the processor and the class that implements
     * the processor _in_ this Jar.
     */
    private static class Config {
        public String jar;
        public String clazz;

        Processor<?> loadClass(Runner runner, String arguments, Logger logger) throws Exception {
            URL jarUrl = new URI(this.jar).toURL();

            Path jarPath;
            // if the jar is still remote, download the entire Jar to a temporary location
            // Otherwise, just point to the physical jar location.
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

            // Create a mapper to deserialize the arguments to jvm objects
            // This mapper also instantiates the channels (readers and writers)
            var mapper = new ObjectMapper();
            mapper.registerModule(new ChannelHandlerModule(runner, logger));
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(loader));

            // Find correct constructor with two argument (arguments and logger)
            Constructor<?> constructor = null;
            for (Constructor<?> c : clazz.getConstructors()) {
                if (c.getParameterCount() == 2) {
                    constructor = c;
                    break;
                }
            }

            if (constructor == null) {
                throw new RuntimeException("No two-arg constructor found");
            }

            Class<?> paramType = constructor.getParameterTypes()[0];

            // Use Jackson to deserialize JSON into the param type
            Object arg = mapper.readValue(arguments, paramType);

            // Instantiate using the constructor
            constructor.setAccessible(true);
            return (Processor<?>) constructor.newInstance(arg, logger);
        }
    }
}
