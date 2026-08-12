package io.github.rdfc;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import java.util.logging.*;

import io.github.rdfc.helpers.Errors;
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

    /** How long to wait for a jar server to accept the connection. */
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    /** How long to wait for the next block of a jar that is being downloaded. */
    private static final int READ_TIMEOUT_MS = 60_000;

    public final StreamObserver<FromRunner> stream;

    protected final RunnerGrpc.RunnerStub stub;

    // Channels are registered while processors are constructed, on whatever thread
    // deserializes their arguments, and are looked up from the gRPC callback
    // threads, so all three are concurrent.
    protected final Map<String, Reader> readers = new ConcurrentHashMap<>();
    protected final Map<String, Writer> writers = new ConcurrentHashMap<>();
    protected final Map<String, Processor<?>> processors = new ConcurrentHashMap<>();

    /**
     * Per processor the future that completes when its init() finished and its
     * transform() was started, and that fails when it never got that far. A
     * processor may only produce once this completed, see the `start` message.
     *
     * The entry is put in before init() is even called, so a `start` message that
     * comes back while init is still running always finds the processor.
     */
    private final Map<String, CompletableFuture<Void>> initialized = new ConcurrentHashMap<>();

    /**
     * Every future this runner is still waiting for a processor to complete: the
     * init, transform and produce of each of them.
     *
     * They belong to the processors, so this runner cannot make them finish, but on
     * teardown it can stop waiting for them — see {@link #finish}. Without that, a
     * processor blocked on a channel that will never deliver anything again keeps
     * its continuation, and so this runner, alive forever.
     */
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Per jar URL the promise of the class loader for it, so several processors out
     * of the same jar download and load that jar exactly once.
     *
     * A promise and not the loader itself: the entry is claimed before the download
     * starts, so the second caller waits for the first one's download instead of
     * starting its own, and the teardown can fail an entry that is still being
     * filled without waiting for it — see {@link #classLoaderFor}.
     */
    private final Map<String, CompletableFuture<LoadedJar>> jars = new ConcurrentHashMap<>();

    /**
     * Mapper used to deserialize the config for each processor.
     */
    protected final ObjectMapper mapper;

    private final AtomicInteger awaiting = new AtomicInteger(0);

    /**
     * The first init that failed, or null while none has.
     *
     * A processor claims its two callbacks the moment its `proc` message arrives,
     * so a processor that fails to initialize before its siblings' `proc` messages
     * are in hands both back onto a counter nobody else is holding yet — and that
     * zero is what ends this runner. Without this field that ending is
     * indistinguishable from the orderly one, and a pipeline whose very first
     * processor could not be loaded is reported as a pipeline that ran: the
     * completion future completes normally and, in server mode, the connection is
     * marked DONE.
     *
     * So the zero check asks this first: a run in which an init failed never
     * finished its work, whichever processor's callback happened to land last. The
     * first failure wins, because it is the one that explains the rest.
     */
    private final AtomicReference<Throwable> initFailure = new AtomicReference<>();
    /**
     * Function to call when all processors are finished.
     * This will close the GRPC channel.
     */
    private final Runnable onComplete;
    private final Logger logger;

    /**
     * Every logger this runner built, with the log stream it ships on.
     *
     * Two reasons this list exists. The teardown needs it: each of those loggers
     * has a log RPC of its own open to the orchestrator, and nothing else knows
     * they are there — in server mode a connection that comes and goes would
     * leave one behind per processor. And the loggers are anonymous, so the
     * LogManager does not hold them: this list is what keeps them alive for as
     * long as this runner runs, next to the {@code Processor.logger} field of
     * every processor that got one.
     *
     * It is not cleared on teardown. The handlers are detached and closed there,
     * which is what had to be released; holding the loggers a little longer costs
     * nothing, and this runner is on its way out anyway.
     */
    private final List<LogStream> loggers = new CopyOnWriteArrayList<>();

    /**
     * Completes when this runner is done: normally when every processor ran,
     * exceptionally when a processor never got past its init or when the
     * connection to the orchestrator ended before the work was finished.
     */
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    /**
     * Whether this runner already ended.
     *
     * All three ways of ending — the last processor finishing, a broken
     * connection, an orchestrator that closes the stream — can happen at the same
     * moment, and the two latter ones arrive on gRPC callback threads. Only the
     * caller that flips this tears anything down.
     */
    private final AtomicBoolean finished = new AtomicBoolean(false);

    protected final String uri;

    /**
     * Told about every message this runner carries, for the server's dashboard.
     *
     * {@link RunnerObserver#NOOP} outside server mode, so the CLI pays nothing for
     * statistics nobody looks at.
     */
    private final RunnerObserver observer;

    /**
     * Asked whether a jar is already on this machine before it is downloaded.
     *
     * {@link JarResolver#NONE} outside server mode, so the CLI keeps fetching
     * every jar exactly the way it always did.
     */
    private final JarResolver jarResolver;

    public Runner(RunnerGrpc.RunnerStub stub, String uri, Runnable onComplete) {
        this(stub, uri, onComplete, RunnerObserver.NOOP);
    }

    /**
     * Builds a runner that reports its traffic.
     *
     * @param stub       the connection to the orchestrator
     * @param uri        identifying this runner
     * @param onComplete run when this runner is done and has released everything
     * @param observer   told about every message that goes over a channel
     */
    public Runner(RunnerGrpc.RunnerStub stub, String uri, Runnable onComplete, RunnerObserver observer) {
        this(stub, uri, onComplete, observer, JarResolver.NONE);
    }

    /**
     * Builds a runner that reports its traffic and can load jars off the disk it
     * is already serving them from.
     *
     * @param stub        the connection to the orchestrator
     * @param uri         identifying this runner
     * @param onComplete  run when this runner is done and has released everything
     * @param observer    told about every message that goes over a channel
     * @param jarResolver asked for a local file before a jar is downloaded
     */
    public Runner(RunnerGrpc.RunnerStub stub, String uri, Runnable onComplete, RunnerObserver observer,
            JarResolver jarResolver) {
        this.uri = uri;
        this.onComplete = onComplete;
        this.observer = observer;
        this.jarResolver = jarResolver;
        this.stub = stub;
        this.logger = this.createLogger(uri, "cli");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new ChannelHandlerModule(this, this.logger));
        // The mapper can ignore properties like `@type`, `@context` from JSON-LD
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Last, because this hands `this` to the transport: from here on incoming
        // messages can be delivered, and they may only find fields that are set.
        //
        // What this cannot cover is a subclass: its own fields are initialized after
        // this constructor returns, so a message delivered from inside this call
        // would find them empty. No transport this runner uses delivers anything
        // that early — the connect() only opens the call — so this stays a
        // constructor rather than a factory that would have to be called by hand.
        this.stream = new StreamObserverWrapper<>(stub.connect(this), "main stream", this.logger);

        this.sendIdentify();
        this.logger.info("JVM runner identified");
    }

    /**
     * A logger of this runner and the log stream its records travel to the
     * orchestrator on.
     */
    static final class LogStream {
        final Logger logger;
        final GrpcLogHandler handler;

        LogStream(Logger logger, GrpcLogHandler handler) {
            this.logger = logger;
            this.handler = handler;
        }
    }

    /**
     * Builds a logger that reports to the orchestrator, and remembers it.
     *
     * Every logger in this runner is made here, so the teardown can find the log
     * RPC behind each of them again — see {@link #closeLogStreams}. Private: it is
     * called from the constructor, where an override would run before the
     * subclass is initialized.
     *
     * @param uri      of whoever logs through it, the runner or a processor
     * @param entities what the orchestrator is told the record belongs to, after
     *                 the URI itself
     * @return the logger
     */
    private Logger createLogger(String uri, String... entities) {
        var handler = new GrpcLogHandler(this.stub, uri, entities);
        var logger = GrpcLogHandler.loggerFor(handler, uri);
        this.loggers.add(new LogStream(logger, handler));

        if (this.finished.get()) {
            // Checked after adding, so a processor that arrives while the teardown
            // is running cannot leave its log stream open: either the teardown
            // sees the entry and closes it, or this sees the flag. Both closing it
            // is fine, close() is idempotent.
            logger.removeHandler(handler);
            handler.close();
        }

        return logger;
    }

    /**
     * Ends every log stream this runner opened and takes its handler off the
     * logger it was on.
     *
     * One RPC was opened per logger — one for the runner, one per processor — and
     * before this nothing ever closed them: in server mode every connection that
     * came and went left them all behind. Whoever still holds one of these loggers
     * may keep logging afterwards; those records go to the console and no longer
     * to an orchestrator that is not listening.
     *
     * The list is left as it is, so the loggers stay reachable — see
     * {@link #loggers}.
     */
    private void closeLogStreams() {
        for (var attached : this.loggers) {
            this.quietly("closing the log stream of " + attached.handler.uri(), () -> {
                attached.logger.removeHandler(attached.handler);
                attached.handler.close();
            });
        }
    }

    /**
     * The loggers this runner built, with their log streams. Visible for testing.
     *
     * @return a snapshot of the list
     */
    List<LogStream> logStreams() {
        return List.copyOf(this.loggers);
    }

    /**
     * Completes when this runner is done and has released everything it held.
     *
     * It completes normally when every processor ran to the end, and exceptionally
     * when it did not: a processor that never made it past its init, or an
     * orchestrator connection that ended before the work was finished — both of
     * which are normal events, not reasons to bring the JVM down.
     *
     * @return the future, the same one on every call
     */
    public CompletableFuture<Void> completion() {
        return this.completion;
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
        this.decreaseAndCheckEnd(1);
    }

    /**
     * Hands a number of processor callbacks back and shuts this runner down when
     * none are left.
     *
     * Every path that lowers the counter goes through here: a path that lowers it
     * without checking can drop it onto zero without anyone noticing, and then the
     * runner waits forever for a callback that will never come.
     *
     * @param callbacks how many callbacks are handed back at once
     */
    private void decreaseAndCheckEnd(int callbacks) {
        // One atomic step, so exactly one caller can ever observe the zero
        var v = this.awaiting.addAndGet(-callbacks);
        if (v == 0) {
            // Read after the decrement, and set before it, so whoever observes the
            // zero also observes the failure that got there first — see
            // {@link #initFailure}. Null on the ordinary path, and then this is the
            // orderly completion it has always been.
            //
            // The goodbye on the stream is sent either way: this runner is done
            // with a connection that is still up, and the orchestrator was already
            // told which processor failed, on this very stream, before the callback
            // that ends up here was handed back.
            this.finish(this.initFailure.get(), true);
        }
    }

    /**
     * Ends this runner, exactly once.
     *
     * Every way of ending goes through here: the last processor handing its
     * callback back, a connection that broke, an orchestrator that closed the
     * stream, and the cancellations this very method causes. The first caller does
     * the work, everyone after it returns without touching anything — the runner
     * has already let go of the resources they would want to release.
     *
     * Nothing in here may throw: it runs on gRPC callback threads, where an
     * exception takes the connection down (and in server mode, would take a
     * connection down that is merely one of many).
     *
     * @param error what ended this runner, or null when it simply finished its work
     */
    private void finish(Throwable error) {
        // Only the orderly end says goodbye: on a broken connection there is
        // nothing left to say it on, and half-closing a dead call throws.
        this.finish(error, error == null);
    }

    /**
     * Ends this runner, exactly once, and says whether there is still a stream to
     * take leave on.
     *
     * The two are not the same question. This runner having nothing left to wait
     * for is one thing — and then the stream is up and is half-closed, whether the
     * work ended in a failure or not; the connection having died under it is
     * another, and then there is nothing to send anything on.
     *
     * @param error      what ended this runner, or null when it simply finished its
     *                   work
     * @param sayGoodbye whether to half-close the main stream on the way out
     */
    private void finish(Throwable error, boolean sayGoodbye) {
        if (!this.finished.compareAndSet(false, true)) {
            return;
        }

        if (sayGoodbye) {
            this.quietly("completing the stream", this.stream::onCompleted);
        }

        // The processors' futures are handed a failure, so nobody chained on them
        // keeps waiting for an answer that is not coming anymore
        var reason = error != null ? error : new IllegalStateException("the runner finished");

        this.quietly("failing the pending inits", () -> this.initialized.values()
                .forEach(init -> init.completeExceptionally(reason)));
        this.quietly("cancelling the in-flight phases", () -> this.inFlight.forEach(phase -> phase.cancel(true)));
        this.quietly("failing the pending acknowledgements",
                () -> this.writers.values().forEach(writer -> writer.fail(reason)));
        // Closing the readers ends the iterators the processors consume, so they see
        // an end of stream instead of waiting for data that will never arrive
        this.quietly("closing the readers", () -> this.readers.values().forEach(Reader::close));
        this.quietly("closing the loaded jars", this::closeJars);
        // Late, so every step above it can still report on the log stream — none
        // of them touches the channel — but before the callback: in the CLI that
        // shuts the channel down, and a log stream cannot be half-closed on a
        // channel that is gone.
        //
        // The same holds for whoever calls this from outside. A server that drops
        // a connection's transport has to tear its runner down *first*: these
        // handlers can only be closed quietly while the channel is still there,
        // and a stream that dies before its handler was closed is reported as the
        // fault it would be anywhere else. See RunnerServer's Connection.cancel.
        this.quietly("closing the log streams", this::closeLogStreams);
        this.quietly("running the completion callback", this.onComplete::run);

        if (error != null) {
            this.completion.completeExceptionally(error);
        } else {
            this.completion.complete(null);
        }
    }

    /**
     * Runs one step of the teardown, whatever it does.
     *
     * A step that throws may not stop the steps behind it: the whole point of the
     * teardown is that everything is released, and the very last one of them
     * completes the future somebody is waiting on.
     *
     * @param what a description of the step, for the log
     * @param step the step to run
     */
    private void quietly(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            try {
                this.logger.warning("Tearing this runner down: " + what + " failed: " + t);
            } catch (Throwable ignored) {
                // The logger itself talks to the orchestrator, so it can fail too
                t.printStackTrace(System.err);
            }
        }
    }

    /**
     * How many processor callbacks this runner is still waiting for. Visible for
     * testing.
     *
     * @return the current value of the counter
     */
    int awaiting() {
        return this.awaiting.get();
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
                // Only what a processor actually receives is counted: a message for a
                // channel nobody reads is dropped, and counting it would show traffic
                // on a channel that has no reader.
                this.observe(msg.getChannel(), RunnerObserver.Role.READER, msg.getData().size());

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
            // All processors are allowed to produce data, but only once their own
            // init() completed: producing before that is a protocol violation.
            this.initialized.forEach(this::produceWhenInitialized);

            return;
        }

        if (value.hasProc()) {
            this.startProcessor(value.getProc());

            return;
        }

        this.logger.severe("Unsupported message " + value.getUnknownFields());
    }

    /**
     * Sends a processed message to the orchestrator indicating that a message has
     * been handled.
     * This is either a _normal_ message or a streaming message
     * 
     * Both the plain and the streaming path end up here, so both report their
     * failures the same way: unwrapped, because the orchestrator shows this string
     * to a user and "java.util.concurrent.CompletionException: ..." tells that user
     * nothing.
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
            processed.setError(Errors.describe(error));
        }
        var orchestratorMessage = FromRunner.newBuilder().setProcessed(processed.build());
        this.stream.onNext(orchestratorMessage.build());
    }

    /**
     * Constructs a processor and initializes it.
     *
     * The processor claims two callbacks (a transform and a produce) before its
     * init is even started, so a `start` message arriving while init is still
     * pending cannot drop the counter past zero.
     *
     * @param proc the processor the orchestrator sent
     */
    private void startProcessor(rdfc.Service.Processor proc) {
        var uri = proc.getUri();
        var procLogger = this.createLogger(uri, this.uri);

        // One is decreased when the transform is finished,
        // one is decreased when the produce is finished.
        this.awaiting.addAndGet(2);

        // The future the `start` message chains the produce on. It exists before
        // init() is called, because init can complete on this very thread and the
        // ProcessorInitialized that its continuation sends can bring the `start`
        // straight back in: by then the `start` handler has to find this processor.
        var initialized = new CompletableFuture<Void>();

        try {
            Processor<?> processor = this.startProc(proc, procLogger);
            this.initialized.put(uri, initialized);

            this.awaited(processor.init()).thenAccept(_void -> {
                // Reported before transform is started: from the moment transform runs
                // its completion decreases the counter, so a failure after that point
                // may no longer hand the two callbacks back.
                this.sendProcInit(uri, Optional.empty());

                // Guarded, so a transform that throws instead of returning a failed
                // future takes the normal transform-failure path. Letting it escape
                // here would fail the init future and report this processor a second
                // time, contradicting the ProcessorInitialized just sent.
                this.awaited(phase(processor::transform)).whenComplete((output, e) -> {
                    if (e != null) {
                        this.logger.severe("Processor " + uri + " transform exception: " + e);
                        e.printStackTrace(System.err);
                    }
                    this.decreaseAndCheckEnd();
                });
            }).whenComplete((_void, e) -> {
                if (e == null) {
                    // Init is awaited and transform is started, this processor may produce
                    initialized.complete(null);
                    return;
                }

                e.printStackTrace();
                this.failedToInitialize(uri, initialized, e);
            });
        } catch (Exception e) {
            e.printStackTrace();
            this.failedToInitialize(uri, initialized, e);
        }
    }

    /**
     * A processor never made it past its init: report it and hand its two
     * callbacks back.
     *
     * @param uri         of the processor
     * @param initialized the future the `start` message chains its produce on
     * @param error       what went wrong
     */
    private void failedToInitialize(String uri, CompletableFuture<Void> initialized, Throwable error) {
        // Neither transform nor produce runs for this processor, so the `start`
        // message may not chain a produce on it either
        initialized.completeExceptionally(error);

        // Before the callbacks are handed back, because handing them back can be
        // what ends this runner and whoever ends it reads this to decide whether
        // the run was a success — see {@link #initFailure}
        this.initFailure.compareAndSet(null, Errors.unwrap(error));

        try {
            // Both before the callbacks are handed back: that hand-back can be what
            // ends this runner, and then the stream is completed and nothing can be
            // sent on it anymore — neither the report nor a log record.
            // The root cause, like the acknowledgements: the orchestrator shows this
            // string to a user
            this.logger.severe("Processor " + uri + " failed to initialize: " + Errors.describe(error));
            this.sendProcInit(uri, Optional.of(Errors.describe(error)));
        } catch (Exception e) {
            this.logger.severe("Could not report the failed init of " + uri + ": " + e);
        } finally {
            // In a finally: a counter that is never handed back keeps this runner
            // alive forever.
            this.decreaseAndCheckEnd(2);
        }
    }

    /**
     * Lets a processor produce data, but not before it finished initializing.
     *
     * When init failed, produce is never called and the counter is left alone: the
     * two units this processor claimed were already handed back when the init
     * failed.
     *
     * @param uri  of the processor
     * @param init future that completes when this processor is initialized
     */
    private void produceWhenInitialized(String uri, CompletableFuture<Void> init) {
        init.thenRun(() -> this.awaited(phase(() -> this.processors.get(uri).produce())).whenComplete((output, e) -> {
            if (e != null) {
                this.logger.severe("Processor " + uri + " produce exception: " + e);
                e.printStackTrace(System.err);
            }
            // After the production is finished, check for end
            this.decreaseAndCheckEnd();
        }));
    }

    /**
     * Remembers a processor's future for as long as this runner waits for it, so a
     * teardown can stop waiting for it.
     *
     * @param <T>   what the phase hands back
     * @param phase the future to wait for
     * @return that very same future
     */
    private <T> CompletableFuture<T> awaited(CompletableFuture<T> phase) {
        this.inFlight.add(phase);
        // Kept short: a pipeline that runs for hours would otherwise collect every
        // phase that ever ran
        phase.whenComplete((output, e) -> this.inFlight.remove(phase));
        return phase;
    }

    /**
     * Calls one of a processor's phases and hands back a future that always
     * exists, whatever the processor does.
     *
     * A phase is written by whoever wrote the processor, so it may well throw
     * instead of returning a failed future, or hand back null. Both are turned
     * into an ordinary future here, so every caller has exactly one failure path
     * and a callback the runner is waiting for can never go missing. A phase that
     * returns null is taken to have finished right away.
     *
     * @param phase the phase to call
     * @return the phase's future, a failed one when it threw, a completed one when
     *         it returned null
     */
    private static CompletableFuture<?> phase(Supplier<CompletableFuture<?>> phase) {
        try {
            var out = phase.get();
            return out != null ? out : CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    protected Processor<?> startProc(rdfc.Service.Processor proc, Logger logger) throws Exception {
        var uri = proc.getUri();
        var config = proc.getConfig();
        var params = proc.getArguments();

        var arg = mapper.readValue(config, Config.class);
        var processor = arg.loadClass(this, params, logger);

        this.processors.put(uri, processor);

        return processor;
    }

    /**
     * The connection to the orchestrator broke.
     *
     * In server mode this is an everyday event — an orchestrator went away — so it
     * tears this one runner down and leaves the JVM alone.
     */
    @Override
    public void onError(Throwable t) {
        if (this.finished.get()) {
            // The ordinary end: this runner finished, closed the channel itself, and
            // the call it was closing is reported back as cancelled
            this.logger.fine("The connection to the orchestrator ended: " + Errors.describe(t));
            return;
        }

        // Warning, not severe: losing the connection is expected, and severe would
        // try to report it over the very connection that just died
        this.logger.warning("The connection to the orchestrator failed: " + Errors.describe(t));
        this.finish(t);
    }

    /**
     * The orchestrator closed the stream.
     *
     * When this runner already finished its work, this is just the orderly end of
     * the connection and the completion future is long since completed. When it
     * has not, the orchestrator went away before this runner was done, and that is
     * a failure — the same choice the js-runner makes. Whoever waits on
     * {@link #completion()} has to be able to tell "ran everything" apart from
     * "was cut short", and the counter says the work was not finished.
     */
    @Override
    public void onCompleted() {
        if (this.finished.get()) {
            this.logger.fine("The orchestrator closed the stream");
            return;
        }

        this.logger.warning("The orchestrator closed the stream before this runner completed");
        this.finish(new IllegalStateException("stream ended before runner completed"));
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

        // After the send, not before: a message the transport refused never went
        // anywhere and has no business in the statistics
        this.observe(channel, RunnerObserver.Role.WRITER, data.size());
    }

    /**
     * Tells the observer about a message, whatever it does with that.
     *
     * Guarded, because this runs on the gRPC callback threads and on whichever
     * thread a processor produces on: an observer that throws would take a
     * connection down over a counter, and in server mode that would be one
     * orchestrator's pipeline killed by another one's dashboard.
     *
     * @param channel the message went over
     * @param role    whether this runner read it or wrote it
     * @param bytes   the size of its payload
     */
    private void observe(String channel, RunnerObserver.Role role, int bytes) {
        try {
            this.observer.onMessage(channel, role, bytes);
        } catch (Throwable t) {
            this.logger.fine("The runner observer failed on " + role.wire() + " " + channel + ": " + t);
        }
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
     * The class loader for a jar, and the copy of that jar to clean up afterwards.
     */
    private static final class LoadedJar {
        /** Loads the classes out of the jar. */
        final URLClassLoader loader;
        /**
         * The temporary copy that was downloaded for it, null when the jar was
         * already on this machine and so is not ours to delete.
         */
        final Path downloaded;

        LoadedJar(URLClassLoader loader, Path downloaded) {
            this.loader = loader;
            this.downloaded = downloaded;
        }
    }

    /**
     * The class loader for a jar, downloading and building it the first time this
     * runner is asked for it.
     *
     * A pipeline usually runs several processors out of the same jar. Loading that
     * jar once per processor downloads the same file again and again and hands
     * every processor its own copies of the same classes, so the answer is cached
     * per runner — per runner, and not statically, because the loaders and the
     * downloads are released when this runner is torn down.
     *
     * What is cached is the <em>promise</em> of a loader, not the loader: the
     * download is done outside every lock this class holds. Downloading under a
     * lock the teardown also wants means a jar server that accepts the connection
     * and then says nothing parks the teardown behind it — and the teardown runs on
     * a gRPC callback thread, so that would be a stuck connection, an `onComplete`
     * that never runs and a {@link #completion()} that never completes. A stalled
     * download may cost the processor that wants that jar; it may not cost the
     * runner its ending.
     *
     * @param jar    URL of the jar
     * @param logger to report the download on
     * @return the loader for that jar
     * @throws Exception when the jar cannot be reached or read, or when this runner
     *                   was torn down while it was being loaded
     */
    URLClassLoader classLoaderFor(String jar, Logger logger) throws Exception {
        var promise = new CompletableFuture<LoadedJar>();
        var running = this.jars.putIfAbsent(jar, promise);

        if (running != null) {
            // Somebody is already loading this jar, or already has: wait for that
            // one instead of downloading the same file a second time
            logger.fine("Reusing the class loader for " + jar);
            return running.get().loader;
        }

        if (this.finished.get()) {
            // Checked after claiming the entry, so this cannot slip past a teardown
            // that is running right now: either it sees our entry and fails it, or
            // we see its flag here.
            this.jars.remove(jar, promise);
            throw new IllegalStateException("this runner was torn down, " + jar + " is not loaded anymore");
        }

        LoadedJar loaded;
        try {
            loaded = this.load(jar, logger);
        } catch (Throwable t) {
            // Taken out again, so a later processor out of the same jar may try once
            // more rather than inherit this failure forever
            this.jars.remove(jar, promise);
            promise.completeExceptionally(t);
            throw t;
        }

        if (!promise.complete(loaded)) {
            // The teardown failed this entry while the download was running, so it
            // never saw this loader and will never close it. Nobody else can reach
            // it either, so it is released right here.
            this.release(jar, loaded);
            throw new IllegalStateException("this runner was torn down while " + jar + " was being loaded");
        }

        return loaded.loader;
    }

    /**
     * Fetches a jar and builds the loader for it. Does the talking to the network,
     * so it is called without holding anything.
     *
     * @param jar    URL of the jar
     * @param logger to report the download on
     * @return the loader, and the copy that was downloaded for it
     * @throws Exception when the jar cannot be reached or read
     */
    private LoadedJar load(String jar, Logger logger) throws Exception {
        var local = this.localCopyOf(jar, logger);
        if (local != null) {
            // Not ours to delete: it was on this machine before this runner existed
            logger.info("Loading " + jar + " from " + local);
            return new LoadedJar(new URLClassLoader(new URL[] { local.toUri().toURL() }), null);
        }

        URL jarUrl = new URI(jar).toURL();

        Path jarPath;
        Path downloaded = null;
        // if the jar is still remote, download the entire Jar to a temporary location
        // Otherwise, just point to the physical jar location.
        if (jarUrl.getProtocol().equalsIgnoreCase("http") || jarUrl.getProtocol().equalsIgnoreCase("https")) {
            // Remote JAR, download it
            logger.info("Downloading JAR from " + jar);
            // Download JAR to temp dir
            jarPath = Files.createTempFile("remote-lib", ".jar");
            // Belt and braces: the teardown deletes this, and should the runner
            // never be torn down the JVM still cleans it up on the way out
            jarPath.toFile().deleteOnExit();
            downloaded = jarPath;

            // With timeouts, because the default is to wait forever: a jar server
            // that accepts the connection and then goes quiet would otherwise hold
            // this processor's init open for as long as the pipeline runs
            var connection = jarUrl.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, jarPath, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("End download");
        } else {
            // Local JAR, use it directly
            jarPath = Path.of(jarUrl.toURI());
        }

        // Use local URLClassLoader
        return new LoadedJar(new URLClassLoader(new URL[] { jarPath.toUri().toURL() }), downloaded);
    }

    /**
     * Asks the resolver whether this jar is already on this machine.
     *
     * Guarded: a resolver that throws may cost this processor a shortcut, it may
     * not cost it its jar. Whatever went wrong, the download path is still there
     * and is what the CLI has always done.
     *
     * @param jar    URL of the jar
     * @param logger to report a resolver that misbehaved on
     * @return the local file, or null when there is none to use
     */
    private Path localCopyOf(String jar, Logger logger) {
        try {
            return this.jarResolver.resolve(jar).orElse(null);
        } catch (Throwable t) {
            logger.warning("Could not map " + jar + " onto a local file, downloading it instead: " + t);
            return null;
        }
    }

    /**
     * Closes every class loader this runner built and deletes the jars it
     * downloaded for them.
     *
     * A loader keeps the jar file open, which on Windows means the file cannot be
     * deleted, and in server mode a runner that comes and goes would otherwise
     * leave both behind on every connection.
     *
     * A download that is still running is not waited for. Its entry is failed, so
     * whoever waits on it gives up now instead of after a network timeout, and the
     * loader that download may still produce is released by the thread that
     * produces it — see {@link #classLoaderFor}.
     */
    private void closeJars() {
        for (var entry : this.jars.entrySet()) {
            var jar = entry.getKey();
            var promise = entry.getValue();

            // A no-op for everything that already loaded
            promise.completeExceptionally(
                    new IllegalStateException("this runner was torn down while " + jar + " was being loaded"));
            // And this one is a no-op for everything that did not
            promise.thenAccept(loaded -> this.release(jar, loaded));
        }

        this.jars.clear();
    }

    /**
     * Lets go of one loaded jar: closes the loader and deletes the copy that was
     * downloaded for it, if any.
     *
     * @param jar    URL it was loaded from, for the log
     * @param loaded what to release
     */
    private void release(String jar, LoadedJar loaded) {
        try {
            loaded.loader.close();
        } catch (Exception e) {
            this.logger.warning("Could not close the class loader for " + jar + ": " + e);
        }

        if (loaded.downloaded == null) {
            return;
        }

        try {
            Files.deleteIfExists(loaded.downloaded);
        } catch (Exception e) {
            this.logger.warning("Could not delete the downloaded jar " + loaded.downloaded + ": " + e);
        }
    }

    /**
     * The copy this runner downloaded for a jar, or null when it did not download
     * one. Visible for testing.
     *
     * @param jar URL of the jar
     * @return the temporary copy, or null
     */
    Path downloadedCopyOf(String jar) {
        var promise = this.jars.get(jar);
        if (promise == null || !promise.isDone() || promise.isCompletedExceptionally()) {
            return null;
        }
        return promise.join().downloaded;
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
            // Shared with every other processor out of this same jar
            URLClassLoader loader = runner.classLoaderFor(this.jar, logger);

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
