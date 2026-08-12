package io.github.rdfc.server;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.rdfc.Runner;
import io.github.rdfc.RunnerObserver;
import io.github.rdfc.helpers.Errors;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import rdfc.RunnerGrpc;

/**
 * A runner that is waited for instead of started.
 *
 * Two listeners, and everything in this class hangs off one of them:
 *
 * <ul>
 * <li>an <b>HTTP</b> port serving what an orchestrator has to read before it can
 * use this runner — the index document naming the gRPC address and the
 * processors, the catalogue files themselves, and a dashboard over the live
 * state,</li>
 * <li>a <b>TCP</b> port the orchestrator connects to, writes a runner IRI on and
 * then speaks gRPC on <em>as the server</em>. Every accepted connection becomes
 * one {@link Runner} with a life of its own: it is torn down when its
 * orchestrator goes away and takes neither the other connections nor this
 * process with it.</li>
 * </ul>
 *
 * The composition is the whole job here. {@link Handshake} reads the IRI,
 * {@link SocketBridge} turns the accepted socket into a channel this runner can
 * dial, {@link State} collects what the dashboard shows, {@link IndexGenerator}
 * renders the index, {@link Whitelist} decides what may be served — this class
 * owns their lifetimes, the two thread pools and the shutdown.
 */
public final class RunnerServer implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(RunnerServer.class.getName());

    /**
     * How many orchestrator connections are served at once.
     *
     * Each one costs a thread parked on the connection, two pump threads, a gRPC
     * channel with its own event loop, and whatever the processors it runs use.
     * The refusal is immediate and logged, which is a far better failure than a
     * server that accepts everything and then thrashes.
     */
    static final int MAX_GRPC_CONNECTIONS = 32;

    /** How long {@link #shutdown()} gives the live connections to end. */
    static final long SHUTDOWN_GRACE_MILLIS = 10_000;

    /**
     * How long a connection has to bring its gRPC stream up, counted from the end
     * of the handshake.
     *
     * {@link Handshake#TIMEOUT_MILLIS} covers the IRI line and stops there. Past
     * it the connection is a full slot — a thread, two pumps, a channel — and
     * nothing else was ever going to take it back: the pumps sit in an untimed
     * read, {@link #awaitEnd} waits on futures with no deadline, and a channel
     * that never connects raises nothing. A peer that writes one line and then
     * says nothing held its slot until this process was restarted, and
     * {@link #MAX_GRPC_CONNECTIONS} of those closed the server for business.
     *
     * So the <em>unestablished</em> phase gets a budget of its own, and a
     * connection that has not brought its stream up when it runs out is dropped.
     * Thirty seconds because that is the orchestrator's own budget for connecting
     * back and identifying: anything shorter would cut off orchestrators that are
     * merely slow, anything longer is time this server spends holding a slot for a
     * peer that has already given up.
     *
     * <b>Only the unestablished phase.</b> Once the stream is up there is no
     * deadline at all — pipelines legitimately run for hours — and what is left to
     * notice a peer that dies silently is the socket's keepalive, which
     * {@link SocketBridge} turns on.
     */
    static final long ESTABLISH_MILLIS = 30_000;

    /** What the routing table looks like in the startup line. */
    private static final String ROUTES = "/, /health, /api/state, /dashboard";

    /**
     * Host names accepted from the {@code Host} header for building the index's
     * base IRI.
     *
     * The header is whatever the client felt like sending, and it ends up inside
     * IRIs in a Turtle document — so anything that is not a host name and a port
     * falls back on the configured address instead of being pasted into RDF.
     */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._~\\-\\[\\]:]{1,255}");

    private static final String TURTLE = "text/turtle;charset=utf-8";
    private static final String JSON = "application/json;charset=utf-8";
    private static final String HTML = "text/html;charset=utf-8";
    private static final String TEXT = "text/plain;charset=utf-8";

    private final ServerConfig config;
    private final Set<Path> whitelist;
    private final Path serveRoot;
    private final State state;
    private final IndexGenerator index;
    private final byte[] dashboard;
    private final ObjectMapper mapper = new ObjectMapper();

    private final int grpcBindPort;
    private final int httpBindPort;
    private final int maxConnections;
    private final long establishMillis;

    /** The connections being served, including the ones still in their handshake. */
    private final Set<Connection> live = ConcurrentHashMap.newKeySet();

    private final ExecutorService connections = Executors.newCachedThreadPool(
            daemonThreads("rdfc-connection-"));
    private final ExecutorService requests = Executors.newCachedThreadPool(daemonThreads("rdfc-http-"));

    /**
     * Fires the establishment checks of every connection.
     *
     * One thread for the whole server: a check is a flag read and, in the rare
     * case that it evicts, a {@link SocketBridge#close()} — milliseconds in
     * practice, and a bridge that takes its documented worst case only delays the
     * eviction of another connection that has already been waiting thirty seconds.
     * A thread per connection parked on a timer is the thing this avoids.
     *
     * Built by hand rather than through {@link Executors}: the factory methods
     * hand back a wrapper that hides {@code setRemoveOnCancelPolicy}, and without
     * that policy a cancelled check stays on the queue until its original delay
     * has passed. Every connection that comes up normally cancels its check, so
     * that is one dead task holding a bridge alive for up to
     * {@link #ESTABLISH_MILLIS} per connection ever served.
     */
    private final ScheduledExecutorService deadlines = newDeadlineScheduler();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);

    /**
     * Held while the listeners are being opened, and while they are being closed
     * again.
     *
     * The entrypoint installs its signal hook <em>before</em> it starts this
     * server — a signal arriving in between would otherwise find no hook at all —
     * so a shutdown genuinely can begin on another thread while {@link #start()}
     * is halfway through binding. Without this the two interleave into a server
     * that reports it has stopped and then opens a listener.
     */
    private final Object lifecycle = new Object();

    // Written under the lifecycle lock, read from the accept loop, the request
    // threads and whoever asks which ports were bound
    private volatile ServerSocket listener;
    private volatile HttpServer http;
    private volatile Thread accepter;

    /**
     * Reads a configuration and prepares everything that does not need a port.
     *
     * The catalogues are parsed here rather than per request: the index only
     * depends on the base URL it is asked for, and that base comes from the
     * client-controlled {@code Host} header — a cache miss has to stay cheap.
     *
     * @param config the parsed server configuration
     */
    public RunnerServer(ServerConfig config) {
        this(config, config.grpcPort(), config.httpPort(), MAX_GRPC_CONNECTIONS);
    }

    /**
     * As below, with the real establishment deadline.
     *
     * @param config         the parsed server configuration
     * @param grpcBindPort   port to accept orchestrator connections on, 0 for any
     * @param httpBindPort   port to serve HTTP on, 0 for any
     * @param maxConnections how many connections are served at once
     */
    RunnerServer(ServerConfig config, int grpcBindPort, int httpBindPort, int maxConnections) {
        this(config, grpcBindPort, httpBindPort, maxConnections, ESTABLISH_MILLIS);
    }

    /**
     * Visible for testing: the ports actually bound, the connection cap and the
     * establishment deadline.
     *
     * A test binds ephemeral ports — the configured ones are somebody's real
     * ports and two tests running at once would fight over them — while the
     * configuration stays realistic, because the configured gRPC port is what the
     * index advertises and that is worth asserting. The cap is a parameter for
     * the same reason: proving that the 33rd connection is refused by opening 32
     * of them is a slow way to test a comparison.
     *
     * @param config          the parsed server configuration
     * @param grpcBindPort    port to accept orchestrator connections on, 0 for any
     * @param httpBindPort    port to serve HTTP on, 0 for any
     * @param maxConnections  how many connections are served at once
     * @param establishMillis how long a connection has to bring its stream up, see
     *                        {@link #ESTABLISH_MILLIS}. A test that wants to watch
     *                        a silent peer be evicted would otherwise have to sit
     *                        still for thirty seconds.
     */
    RunnerServer(ServerConfig config, int grpcBindPort, int httpBindPort, int maxConnections, long establishMillis) {
        this.config = config;
        this.grpcBindPort = grpcBindPort;
        this.httpBindPort = httpBindPort;
        this.maxConnections = maxConnections;
        this.establishMillis = establishMillis;

        this.whitelist = Whitelist.build(config.processorConfigs(), LOGGER);
        this.serveRoot = ServeRoot.of(config.configDir(), this.whitelist, LOGGER);
        this.state = new State(config.historySize());
        this.index = new IndexGenerator(config.processorConfigs(), this.serveRoot, config.hostname(),
                config.grpcPort());
        this.dashboard = readDashboard();
    }

    /**
     * Opens both listeners and starts serving.
     *
     * The gRPC listener goes first and is rolled back when the HTTP one cannot be
     * opened, so a start that fails leaves no port bound behind it — and neither
     * of them is published on this object until both are open, so a failed start
     * leaves nothing for a shutdown to find either.
     *
     * A {@link #shutdown()} that got in first wins: this binds nothing and
     * returns. The two hold the same lock, so they cannot interleave into a
     * server that has reported it stopped and then opens a port.
     *
     * @throws ServerStartupException when either port cannot be bound
     */
    public void start() throws ServerStartupException {
        if (!this.started.compareAndSet(false, true)) {
            throw new IllegalStateException("this server was already started");
        }

        synchronized (this.lifecycle) {
            if (this.stopping.get()) {
                // A signal beat us to it, between the entrypoint installing its
                // hook and this call. Nothing is bound, so there is nothing to
                // undo; the shutdown has already completed and awaitShutdown
                // returns at once.
                LOGGER.info("Not starting: this server was stopped before it came up");
                return;
            }

            ServerSocket grpc;
            try {
                // 0.0.0.0: the orchestrator is on another machine, or in another
                // container, which is the entire point of this mode
                grpc = new ServerSocket();
                grpc.setReuseAddress(true);
                grpc.bind(new InetSocketAddress(this.grpcBindPort));
            } catch (IOException e) {
                throw bindError(e, "gRPC", this.grpcBindPort, "grpcPort");
            }

            HttpServer web;
            try {
                web = HttpServer.create(new InetSocketAddress(this.httpBindPort), 0);
            } catch (IOException e) {
                // The gRPC listener is already open; roll it back so a failed
                // start leaks neither a port nor a thread
                closeQuietly(grpc, "gRPC listener");
                throw bindError(e, "HTTP", this.httpBindPort, "httpPort");
            }

            // One context and one handler: the JDK server routes by longest
            // prefix, so separate contexts would make /health also answer
            // /health/anything, and the paths this serves are exact.
            web.createContext("/", this::handle);
            web.setExecutor(this.requests);

            // Published only now that both are open: until this point a
            // concurrent shutdown has nothing to close, and nothing to report
            // having closed
            this.listener = grpc;
            this.http = web;

            web.start();

            Thread accepting = new Thread(this::acceptLoop, "rdfc-accept");
            accepting.setDaemon(true);
            this.accepter = accepting;
            accepting.start();

            LOGGER.info("JVM runner server listening: http://0.0.0.0:" + this.boundHttpPort() + " (" + ROUTES
                    + "), gRPC TCP on port " + this.boundGrpcPort());
            LOGGER.info("Serving " + this.whitelist.size() + " whitelisted file(s) relative to " + this.serveRoot);
        }
    }

    /**
     * Whether this server has ports open right now.
     *
     * For an entrypoint that has to decide whether a stop is worth announcing: a
     * server that never came up, or that has already been stopped, is not
     * something anybody needs to be told is stopping.
     *
     * @return true between a successful {@link #start()} and a
     *         {@link #shutdown()}
     */
    public boolean isServing() {
        return this.listener != null && !this.stopping.get();
    }

    /**
     * Blocks until this server has shut down.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public void awaitShutdown() throws InterruptedException {
        this.stopped.await();
    }

    /** @return the port orchestrator connections are accepted on */
    public int boundGrpcPort() {
        return this.listener == null ? this.grpcBindPort : this.listener.getLocalPort();
    }

    /** @return the port HTTP is served on */
    public int boundHttpPort() {
        return this.http == null ? this.httpBindPort : this.http.getAddress().getPort();
    }

    /** @return what the runners of this server are doing */
    public State state() {
        return this.state;
    }

    /** @return the files the HTTP side may hand out, canonical */
    public Set<Path> whitelist() {
        return this.whitelist;
    }

    /** @return the directory the HTTP root maps onto */
    public Path serveRoot() {
        return this.serveRoot;
    }

    /**
     * @return how many orchestrator connections are being served, the ones still
     *         in their handshake included
     */
    public int activeConnections() {
        return this.live.size();
    }

    // ---------------------------------------------------------------- accepting

    /**
     * Takes connections until the listener is closed.
     *
     * An accept that fails while this server is running is logged and retried:
     * one connection that could not be taken — a descriptor limit, a peer that
     * vanished between the SYN and the accept — is not a reason to stop serving
     * the other thirty-one.
     */
    private void acceptLoop() {
        while (!this.stopping.get()) {
            Socket socket;
            try {
                socket = this.listener.accept();
            } catch (IOException e) {
                if (this.stopping.get() || this.listener.isClosed()) {
                    break;
                }
                LOGGER.log(Level.WARNING, "Could not accept a runner connection", e);
                continue;
            }

            this.dispatch(socket);
        }

        LOGGER.fine("The gRPC listener stopped accepting");
    }

    /**
     * Hands one accepted socket to a connection thread, or refuses it.
     *
     * @param socket the accepted connection
     */
    private void dispatch(Socket socket) {
        String host = hostOf(socket);

        if (this.stopping.get() || this.live.size() >= this.maxConnections) {
            String reason = this.stopping.get() ? "shutting down" : "connection limit reached";
            LOGGER.warning("Refusing runner connection from " + host + ": " + reason);
            closeQuietly(socket, "refused connection");
            return;
        }

        Connection connection = new Connection(socket, host);
        // Registered before it is submitted, so the next accept counts it and a
        // shutdown that starts right now already sees it
        this.live.add(connection);
        try {
            this.connections.execute(connection);
        } catch (RejectedExecutionException e) {
            // Nearly always a pool that is shutting down, but not necessarily —
            // saying so unconditionally would hide whatever else it was
            this.live.remove(connection);
            LOGGER.warning("Refusing runner connection from " + host + ": connection rejected: " + e);
            closeQuietly(socket, "refused connection");
        }
    }

    /**
     * One orchestrator connection, from the handshake to the teardown.
     *
     * The socket is never closed here on the way in: {@link Handshake} does not
     * close it either, so exactly one place closes it — this one on a failed
     * handshake, and {@link SocketBridge#close()} once there is a bridge.
     *
     * @param connection the connection being served
     */
    private void serve(Connection connection) {
        Handshake.Result handshake;
        try {
            handshake = Handshake.read(connection.socket);
        } catch (HandshakeException | IOException e) {
            LOGGER.warning("Handshake failed from " + connection.host + ": " + e.getMessage());
            closeQuietly(connection.socket, "orchestrator socket");
            return;
        }

        String uri = handshake.uri();
        LOGGER.info("Orchestrator connection from " + connection.host + " for runner " + uri);

        // Inside the try from here on: everything below can throw, and there is a
        // socket to close and a registration to undo on the way out
        String id = null;
        SocketBridge bridge = null;
        AtomicReference<ScheduledFuture<?>> check = new AtomicReference<>();
        // Out here because the finally has to resolve it: whichever way this
        // connection ends, the unestablished phase ends with it
        Establishment establishment = new Establishment();
        try {
            id = this.state.registerRunner(connection.host, uri);
            bridge = new SocketBridge(connection.socket, handshake.remainder());
            connection.bridge = bridge;

            // Once, and right here: channel() throws after the bridge is closed,
            // and the shutdown may close it at any moment from another thread
            ManagedChannel channel = bridge.channel();

            // Armed before the channel is watched, so the callback that disarms it
            // always finds it — and before the runner exists, because constructing
            // that is what starts the stream this waits for
            check.set(this.evictUnlessEstablished(bridge, uri, connection.host, establishment));
            this.watchChannel(channel, id, () -> {
                if (establishment.establish()) {
                    // Best-effort only, and that is all it has to be: a check that
                    // is already running has lost the gate above and will not close
                    // anything
                    cancel(check.get());
                }
            });

            // Before the runner exists, not after: constructing it opens the
            // stream, and from that moment the dashboard may be asked what this
            // runner is doing. The py-runner sets it in the same place.
            this.state.setStatus(id, State.Status.RUNNING);
            Runner runner = new Runner(RunnerGrpc.newStub(channel), uri, () -> {
            }, this.observerFor(id), ServedJars.of(this.serveRoot, uri));
            connection.runner = runner;

            this.awaitEnd(runner, bridge);

            this.state.setStatus(id, State.Status.DONE);
            LOGGER.info("Runner " + uri + " completed");
        } catch (InterruptedException e) {
            this.markError(id, "the server shut down while this runner was running");
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // The everyday end of a remote connection: the orchestrator went away
            // before its pipeline was finished. One runner's problem, not this
            // process'.
            this.markError(id, Errors.describe(e.getCause()));
            LOGGER.warning("Runner " + uri + " from " + connection.host + " ended: "
                    + Errors.describe(e.getCause()));
        } catch (Throwable t) {
            this.markError(id, Errors.describe(t));
            LOGGER.log(Level.WARNING, "Runner connection from " + connection.host + " failed", t);
        } finally {
            // The gate first, the timer after. A connection that is over has no
            // deadline left to miss, and claiming the gate is what says so to a
            // check that is already running and therefore past cancelling —
            // cancelling first would leave exactly that check free to evict a
            // connection which had already ended, and to say so in the log.
            establishment.close();
            cancel(check.get());

            // Null when the registration itself was what failed: there is nothing
            // to move into the history, only a socket to let go of
            if (id != null) {
                this.state.deregisterRunner(id);
            }
            if (bridge != null) {
                bridge.close();
            } else {
                closeQuietly(connection.socket, "orchestrator socket");
            }
        }
    }

    /**
     * Which end of a connection's unestablished phase got there first.
     *
     * Three things end that phase and exactly one of them may win it: the channel
     * reaching READY, the deadline running out, and the connection simply being
     * over. Nothing orders them — the READY callback comes off a gRPC thread, the
     * deadline off the scheduler, the end off the connection thread — and the
     * losing sides have to do nothing at all, which is why this is a decision and
     * not a flag.
     *
     * A flag was not enough. The check read it once on the way in and then went on
     * to log and close, so a READY landing anywhere inside that window found a
     * connection that was about to be evicted for not being established, and
     * cancelling the check could not help: {@code cancel(false)} does not stop a
     * task that is already running. Here the check closes nothing unless its own
     * compare-and-set won, so the window has no width.
     *
     * <b>One bit, three doors.</b> Which side won is never asked — the winner is
     * the one holding the answer, and every loser's job is the same nothing — so
     * recording <em>that</em> it was resolved is all the state there is to keep. The
     * three methods exist to let the call sites say what they mean.
     */
    static final class Establishment {
        /** False until one of the three arms below has claimed this phase. */
        private final AtomicBoolean resolved = new AtomicBoolean();

        /**
         * The stream came up.
         *
         * @return true for the caller that got there first, and only then. False
         *         once an eviction is already under way — that connection is being
         *         taken down and a late READY does not call it back — or once the
         *         connection has ended on its own.
         */
        boolean establish() {
            return this.resolve();
        }

        /**
         * The deadline ran out.
         *
         * @return true when this caller may go ahead and drop the connection, false
         *         when the stream came up first or the connection is already over
         */
        boolean evict() {
            return this.resolve();
        }

        /**
         * The connection ended, before it ever established or was evicted.
         *
         * The point of this arm is the check that is <em>already running</em> when a
         * connection ends by itself: it is past cancelling, and without this it
         * would find the phase unclaimed, win the eviction and announce that a
         * connection which had already ended was being dropped for not coming up.
         *
         * @return true for the caller that got there first, false when the stream
         *         had come up or the deadline had already claimed this connection
         */
        boolean close() {
            return this.resolve();
        }

        /** @return true for the one caller that ends the unestablished phase */
        private boolean resolve() {
            return this.resolved.compareAndSet(false, true);
        }
    }

    /**
     * Schedules the one check that ends a connection which never came up.
     *
     * Closing the bridge is all it takes: that completes {@link SocketBridge#done()},
     * which unblocks {@link #awaitEnd} and puts the connection on the same path an
     * orchestrator that went away puts it on — the runner is told, the state gets
     * its history entry and the slot is handed back. Nothing here has to know any
     * of that.
     *
     * A single check rather than a poll: there is exactly one moment worth looking
     * at, and until it arrives there is nothing to see.
     *
     * Nothing is logged before the gate is won either, so the warning is only ever
     * about a connection this check really is dropping — not one that came up a
     * moment earlier, and not one that had already ended by itself, which
     * {@link Establishment#close()} takes off the table.
     *
     * @param bridge        the transport to drop when the deadline passes
     * @param uri           of the runner, for the log
     * @param host          it connected from, for the log
     * @param establishment decides between this check and the stream coming up
     * @return the scheduled check, or null when this server is stopping and every
     *         connection is being ended anyway
     */
    private ScheduledFuture<?> evictUnlessEstablished(SocketBridge bridge, String uri, String host,
            Establishment establishment) {
        try {
            return this.deadlines.schedule(() -> {
                if (!establishment.evict()) {
                    return;
                }
                LOGGER.warning("Runner " + uri + " from " + host + " did not establish its stream within "
                        + this.establishMillis + " ms; dropping the connection");
                bridge.close();
            }, this.establishMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // A shutdown that got here first, and that ends this connection itself
            LOGGER.fine("Not watching the establishment of " + uri + ": this server is stopping");
            return null;
        }
    }

    /**
     * Disarms a scheduled check, if there is one.
     *
     * Best effort by design, and not interrupting: whether the check runs is not
     * what decides anything — {@link Establishment} is — so a check that is already
     * running is left to finish and find that it lost.
     *
     * @param scheduled a scheduled check, may be null when none was ever armed
     */
    private static void cancel(ScheduledFuture<?> scheduled) {
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    /**
     * Marks a runner as failed, if it ever got as far as being registered.
     *
     * @param id      of the runner, null when the registration is what failed
     * @param message what went wrong
     */
    private void markError(String id, String message) {
        if (id != null) {
            this.state.markError(id, message);
        }
    }

    /**
     * Waits for the connection to be over, whichever end comes first.
     *
     * Two things end it and only one of them is an exception. The runner
     * completing is the ordinary end. The <em>bridge</em> completing is the other
     * one: the transport died, both sockets are closed, and the runner has not
     * necessarily noticed — gRPC does report a broken transport, but on its own
     * schedule, and a connection slot may not be held for however long that
     * takes. So the bridge is closed and the runner is told, which is what makes
     * this deterministic instead of a race with the channel's error propagation.
     *
     * @param runner the runner on this connection
     * @param bridge the transport underneath it
     * @throws InterruptedException when the connection is cancelled
     * @throws ExecutionException   when the runner ended badly
     */
    private void awaitEnd(Runner runner, SocketBridge bridge) throws InterruptedException, ExecutionException {
        CompletableFuture<Void> completion = runner.completion();

        try {
            CompletableFuture.anyOf(completion, bridge.done()).get();
        } catch (ExecutionException e) {
            // anyOf reports the first failure, which can only be the runner's
            // (the bridge never completes exceptionally) — handled below by
            // waiting for that very future
            LOGGER.fine("The runner ended with " + Errors.describe(e.getCause()));
        }

        if (!completion.isDone()) {
            // The bridge went first. Close it before telling the runner: the
            // teardown wants to say goodbye on a stream that is already gone, and
            // an open channel would keep retrying a transport that is not coming
            // back.
            bridge.close();
            runner.onError(new IOException("the orchestrator connection ended"));
        }

        // Now that the runner really is finished, its own outcome is the
        // connection's outcome
        completion.get();
    }

    /**
     * Mirrors a channel's connectivity into the state the dashboard reads, and
     * says when it first came up.
     *
     * Re-registered from the callback rather than looped on a thread of its own:
     * a thread per connection parked on a state change is a thread per connection
     * doing nothing.
     *
     * READY is the signal this server has that a connection is <em>established</em>
     * without inventing anything on the wire: it means the orchestrator answered
     * the HTTP/2 preface, so the runner's connect stream is really up. Reported on
     * every observation and not only on the change, because the callback that
     * carries it is registered on the state seen a moment earlier, and the channel
     * may have got there in between.
     *
     * @param channel the channel of one connection
     * @param id      of the runner on it
     * @param onReady run whenever the channel is seen READY; must be cheap and
     *                must not throw
     */
    private void watchChannel(ManagedChannel channel, String id, Runnable onReady) {
        try {
            ConnectivityState current = channel.getState(false);
            this.state.setGrpcState(id, current.name());
            if (current == ConnectivityState.READY) {
                onReady.run();
            }
            if (current == ConnectivityState.SHUTDOWN) {
                return;
            }
            channel.notifyWhenStateChanged(current, () -> this.watchChannel(channel, id, onReady));
        } catch (Throwable t) {
            // A channel that was shut down under us; the dashboard keeps whatever
            // it last saw, which is a great deal better than a callback thread
            // dying on a statistic
            LOGGER.fine("Stopped watching the channel of runner " + id + ": " + t);
        }
    }

    /**
     * The observer that feeds one runner's traffic into the state.
     *
     * @param id of the runner
     * @return the observer
     */
    private RunnerObserver observerFor(String id) {
        return (channelUri, role, bytes) -> this.state.recordMessage(id, channelUri, role, bytes);
    }

    // --------------------------------------------------------------------- HTTP

    /**
     * Answers one request, whatever happens.
     *
     * The executor's threads are shared by every request, so nothing may escape
     * from here: an exception on one of them would be logged by the JDK server
     * and the client would be left with a connection that never answers.
     *
     * @param exchange the request
     */
    private void handle(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = pathOf(exchange);
        int status;

        try {
            if (!"GET".equals(method)) {
                status = respond(exchange, 405, TEXT, "Method Not Allowed");
            } else if ("/health".equals(path)) {
                status = this.serveHealth(exchange);
            } else if ("/api/state".equals(path)) {
                status = respond(exchange, 200, JSON, this.mapper.writeValueAsBytes(this.state.snapshot()));
            } else if ("/dashboard".equals(path)) {
                status = respond(exchange, 200, HTML, this.dashboard);
            } else if ("/".equals(path)) {
                status = respond(exchange, 200, TURTLE, this.serveIndex(exchange));
            } else {
                status = this.serveFile(exchange, path);
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to answer " + method + " " + path, t);
            status = failed(exchange);
        } finally {
            exchange.close();
        }

        LOGGER.fine(method + " " + path + " -> " + status);
    }

    /**
     * Renders the index for the address this request came in on.
     *
     * The base is client-controlled — it is the {@code Host} header — and it ends
     * up inside the IRIs of a Turtle document, so a header this server cannot
     * turn into a base falls back on the configured address rather than becoming
     * a 500. Two lines of defence, because the shape of the header and the shape
     * of a legal IRI are not the same question: the pattern rejects what is
     * obviously not a host, and whatever gets past it has to survive being
     * assembled into a document.
     *
     * @param exchange the request
     * @return the index document
     */
    private String serveIndex(HttpExchange exchange) {
        String configured = "http://" + this.config.hostname() + ":" + this.config.httpPort() + "/";
        String base = baseOf(exchange, configured);

        try {
            return this.index.generate(base);
        } catch (RuntimeException e) {
            if (base.equals(configured)) {
                throw e;
            }
            LOGGER.warning("Cannot build an index for the requested base " + base + " (" + e
                    + "); serving the configured one instead");
            return this.index.generate(configured);
        }
    }

    private int serveHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ok");
        health.put("activeConnections", this.activeConnections());
        return respond(exchange, 200, JSON, this.mapper.writeValueAsBytes(health));
    }

    /**
     * Hands out one of the whitelisted files.
     *
     * The path is resolved and canonicalized, and what it resolves to has to be
     * literally one of the whitelisted files <em>and</em> sit under the serving
     * root — which is what makes {@code ..} segments and symlinks pointing out of
     * the tree moot, rather than a prefix check on strings. A path that resolves
     * to nothing cannot be whitelisted either, so it is refused the same way:
     * whether a file this server does not serve exists is not something it should
     * be answering.
     *
     * Both halves are needed. The whitelist is built by following
     * {@code owl:imports} wherever they point, so it can name files outside the
     * tree the operator chose to expose; those are not this server's to hand out,
     * and the root is where that line is drawn — see {@link ServeRoot}.
     *
     * @param exchange the request
     * @param path     its path, percent-decoded, query string already dropped
     * @return the status served
     */
    private int serveFile(HttpExchange exchange, String path) throws IOException {
        String tail = path;
        while (tail.startsWith("/")) {
            tail = tail.substring(1);
        }

        Path real = null;
        try {
            real = this.serveRoot.resolve(tail).normalize().toRealPath();
        } catch (IOException | InvalidPathException e) {
            LOGGER.fine("Cannot resolve " + path + " under " + this.serveRoot + ": " + e);
        }

        if (real == null || !real.startsWith(this.serveRoot) || !this.whitelist.contains(real)) {
            return respond(exchange, 403, TEXT, "Forbidden");
        }

        byte[] content;
        try {
            content = Files.readAllBytes(real);
        } catch (IOException e) {
            // Whitelisted at startup, unreadable now: it was moved or its
            // permissions changed while this server was running
            LOGGER.warning("Cannot read the whitelisted file " + real + ": " + e);
            return respond(exchange, 404, TEXT, "Not found");
        }

        return respond(exchange, 200, TURTLE, content);
    }

    /**
     * The base every IRI in the index is resolved against.
     *
     * Taken from the request, because a server behind a port mapping, a container
     * or a reverse proxy is addressed under a name it cannot know at startup and
     * an index that named the configured one would send the orchestrator to an
     * address it cannot reach. Plain {@code http}: this listener speaks nothing
     * else, and a TLS terminator in front of it is not something to guess at.
     *
     * @param exchange   the request
     * @param configured the base to use when the request does not name a usable
     *                   one
     * @return an absolute base URL ending in a slash
     */
    private static String baseOf(HttpExchange exchange, String configured) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !HOST.matcher(host).matches()) {
            return configured;
        }

        String base = "http://" + host + "/";
        try {
            // The pattern lets through things that are not IRIs — "::", "]", a
            // bare colon — and those would only fail later, while a document is
            // being assembled out of them
            URI parsed = new URI(base);
            if (parsed.getHost() == null && parsed.getAuthority() == null) {
                return configured;
            }
        } catch (URISyntaxException e) {
            LOGGER.fine("Ignoring the Host header '" + host + "': " + e);
            return configured;
        }

        return base;
    }

    /**
     * The path of a request, percent-decoded and without its query string.
     *
     * @param exchange the request
     * @return the path, never null
     */
    private static String pathOf(HttpExchange exchange) {
        URI uri = exchange.getRequestURI();
        String path = uri == null ? null : uri.getPath();
        return path == null ? "" : path;
    }

    private static int respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        return respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static int respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        return status;
    }

    /**
     * Reports a handler that threw, when there is still a response to be sent.
     *
     * @param exchange the request
     * @return the status that went out, or -1 when nothing could be sent anymore
     */
    private static int failed(HttpExchange exchange) {
        try {
            return respond(exchange, 500, TEXT, "Internal Server Error");
        } catch (IOException e) {
            // The headers were already out, or the client is gone
            LOGGER.fine("Could not report a failed request: " + e);
            return -1;
        }
    }

    // ----------------------------------------------------------------- shutdown

    /**
     * Stops serving and lets go of everything, within
     * {@link #SHUTDOWN_GRACE_MILLIS}.
     *
     * The listeners go first, so nothing new arrives while the live connections
     * are being ended, and the connections are ended <b>in parallel</b>: closing
     * one bridge can take up to eight seconds on its own, so a server closing
     * thirty-two of them one after another would run far past any grace worth
     * having.
     *
     * Idempotent, and a second caller waits for the first one's shutdown rather
     * than returning into a half-stopped server — the runtime hook and a
     * {@code shutdown()} in a test can easily arrive together.
     *
     * Safe before, during and after {@link #start()}. It takes the same lock, so
     * a shutdown that arrives while the listeners are being opened either runs
     * first — and then that start binds nothing — or runs after it and closes
     * what it opened. What cannot happen is the two interleaving into a stopped
     * server with a live port.
     */
    public void shutdown() {
        // Outside the lock: the first caller holds it for as long as the whole
        // shutdown takes, and a second caller waiting on the latch may not be
        // waiting for the lock as well
        if (!this.stopping.compareAndSet(false, true)) {
            try {
                this.stopped.await(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        try {
            synchronized (this.lifecycle) {
                this.stop();
            }
        } finally {
            // In a finally: everyone waiting on this — the entrypoint's main
            // thread, a second caller — is stuck forever if a step above threw
            this.stopped.countDown();
            LOGGER.info("Stopped");
        }
    }

    /**
     * The shutdown itself, with the lifecycle lock held.
     *
     * Every field it touches may be null: a server that was stopped before it
     * ever came up has nothing bound, and that is a perfectly ordinary way for a
     * process that got a signal during its startup to end.
     */
    private void stop() {
        LOGGER.info("Shutting down...");
        long deadline = System.currentTimeMillis() + SHUTDOWN_GRACE_MILLIS;

        closeQuietly(this.listener, "gRPC listener");
        if (this.accepter != null) {
            this.accepter.interrupt();
        }
        if (this.http != null) {
            // Seconds, and it is the in-flight requests that are waited for; the
            // connections are what the grace is really for
            this.http.stop(1);
        }

        this.endConnections(deadline);

        this.connections.shutdownNow();
        this.requests.shutdownNow();
        // Nothing is waited for: every connection it could still be watching has
        // just been ended, so whatever is left to fire has nothing to evict
        this.deadlines.shutdownNow();
        await(this.connections, deadline);
    }

    /** {@link #shutdown()} under another name, so this fits a try-with-resources. */
    @Override
    public void close() {
        this.shutdown();
    }

    /**
     * Ends every live connection at once and waits for them, up to the deadline.
     *
     * @param deadline when to stop waiting, in {@code currentTimeMillis}
     */
    private void endConnections(long deadline) {
        List<Connection> snapshot = new ArrayList<>(this.live);
        if (snapshot.isEmpty()) {
            return;
        }

        LOGGER.info("Ending " + snapshot.size() + " live runner connection(s)");
        ExecutorService closers = Executors.newFixedThreadPool(snapshot.size(), daemonThreads("rdfc-stop-"));
        for (Connection connection : snapshot) {
            closers.execute(connection::cancel);
        }
        closers.shutdown();
        await(closers, deadline);
        closers.shutdownNow();
    }

    /**
     * Waits for a pool to finish, never past the deadline.
     *
     * @param pool     the pool to wait for
     * @param deadline when to give up, in {@code currentTimeMillis}
     */
    private static void await(ExecutorService pool, long deadline) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            return;
        }
        try {
            if (!pool.awaitTermination(left, TimeUnit.MILLISECONDS)) {
                LOGGER.warning("Some connections did not end within the shutdown grace");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One orchestrator connection and the thread serving it.
     *
     * It exists so a shutdown has something to take hold of: the bridge to close
     * — which is what actually unblocks the thread — and the thread to interrupt
     * for the phases where there is no bridge yet.
     */
    private final class Connection implements Runnable {
        private final Socket socket;
        private final String host;
        private volatile SocketBridge bridge;
        private volatile Runner runner;
        private volatile Thread thread;

        Connection(Socket socket, String host) {
            this.socket = socket;
            this.host = host;
        }

        @Override
        public void run() {
            this.thread = Thread.currentThread();
            try {
                RunnerServer.this.serve(this);
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "The connection from " + this.host + " ended unexpectedly", t);
            } finally {
                RunnerServer.this.live.remove(this);
            }
        }

        /**
         * Ends this connection from the outside.
         *
         * <b>The runner first, its transport after.</b> Tearing the runner down is
         * what half-closes its log streams, and half-closing them only works while
         * there is still a channel to do it on: dropping the transport first left
         * every one of those streams to die of its own accord a moment later, one
         * failure line per processor on the way out of a perfectly orderly
         * shutdown. Telling the runner first means it has closed its handlers
         * before the transport goes, and what arrives after that is the routine
         * teardown status a closed handler stays quiet about.
         *
         * Closing the bridge is what actually ends the connection — that takes
         * both sockets down and completes the future the connection thread is
         * parked on. The interrupt is for the window before there is a bridge: a
         * thread sitting in the handshake, whose socket has just been closed
         * underneath it.
         */
        void cancel() {
            Runner current = this.runner;
            if (current != null) {
                // The same shape gRPC would report a moment later anyway, only in
                // time to be useful
                current.onError(new StatusRuntimeException(
                        Status.UNAVAILABLE.withDescription("the runner server is shutting down")));
            }

            SocketBridge transport = this.bridge;
            if (transport != null) {
                transport.close();
            } else {
                closeQuietly(this.socket, "orchestrator socket");
            }

            Thread running = this.thread;
            if (running != null) {
                running.interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Turns a bind failure into something an operator can act on.
     *
     * @param cause    what the socket said
     * @param purpose  which listener it was
     * @param port     the port that could not be bound
     * @param property the configuration property naming that port
     * @return the error to report and exit on
     */
    private static ServerStartupException bindError(IOException cause, String purpose, int port, String property) {
        String detail;
        if (cause instanceof BindException) {
            detail = "port " + port + " is already in use — another JVM runner server (or a different process) "
                    + "is likely still listening on it. Stop it, or set a different rdfc:" + property
                    + " in the server config.";
        } else {
            detail = "could not bind port " + port + ": " + cause;
        }
        return new ServerStartupException("Cannot start the " + purpose + " listener: " + detail, cause);
    }

    /**
     * @param socket an accepted connection
     * @return the address it came from
     */
    private static String hostOf(Socket socket) {
        return socket.getInetAddress() == null ? "unknown" : socket.getInetAddress().getHostAddress();
    }

    private static void closeQuietly(Closeable closeable, String what) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not close the " + what, e);
        }
    }

    /**
     * Daemon threads, so a JVM whose main thread returned is not held open by a
     * pool waiting for work that is not coming.
     *
     * @param prefix name prefix, for the log and for a thread dump
     * @return the factory
     */
    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * The scheduler the establishment checks run on.
     *
     * @return a single daemon thread that drops cancelled checks from its queue
     *         instead of holding them until their delay has passed
     */
    private static ScheduledExecutorService newDeadlineScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                daemonThreads("rdfc-deadline-"));
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * Reads the dashboard out of this jar.
     *
     * @return its bytes, or a stand-in page when it is missing, because a broken
     *         resource is no reason not to serve runners
     */
    private static byte[] readDashboard() {
        try (InputStream in = RunnerServer.class.getResourceAsStream("dashboard.html")) {
            if (in == null) {
                LOGGER.warning("dashboard.html is missing from the runner jar");
                return "<!doctype html><title>dashboard</title><p>The dashboard is missing from this build."
                        .getBytes(StandardCharsets.UTF_8);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot read the bundled dashboard", e);
            return new byte[0];
        }
    }

    /** @return the configuration this server was built from */
    public ServerConfig config() {
        return this.config;
    }

    /** @return the processors this server advertises, for tests and diagnostics */
    public List<IndexGenerator.ProcessorDescription> advertised() {
        return Collections.unmodifiableList(this.index.descriptions());
    }
}
