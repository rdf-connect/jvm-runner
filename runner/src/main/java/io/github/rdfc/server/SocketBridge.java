package io.github.rdfc.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

/**
 * Turns a socket this runner <em>accepted</em> into a gRPC channel it dials.
 *
 * The roles on a remote connection are upside down: the orchestrator opens the
 * TCP connection but then speaks HTTP/2 as the gRPC <b>server</b>, so this
 * runner has to be the gRPC <b>client</b> on a socket it did not connect. There
 * is no way to hand grpc-java an already accepted socket, so the bytes take a
 * detour:
 *
 * <pre>
 *   orchestrator socket  ⇄  [in/out pump threads]  ⇄  loopback socket  ⇄  ManagedChannel
 * </pre>
 *
 * A throwaway {@link ServerSocket} is bound on the loopback address, the
 * channel dials it, and two threads copy bytes in both directions. The
 * orchestrator→gRPC direction writes the handshake remainder — everything
 * {@link Handshake} over-read past the IRI newline, which is normally the start
 * of the HTTP/2 client preface — before anything else, so the transport sees an
 * unbroken stream.
 *
 * The listener is <b>single-use</b>: exactly one connection is accepted and it
 * is closed at once. A second connect is then refused by the operating system,
 * which is what we want — a stray client cannot get itself pumped into an
 * orchestrator's connection, and a gRPC reconnect after this bridge broke fails
 * loudly instead of silently attaching to nothing.
 *
 * This class is transport only. It knows nothing about the runner protocol and
 * calls no runner code.
 */
public final class SocketBridge implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(SocketBridge.class.getName());

    /** Copy buffer, per direction. */
    private static final int BUFFER = 64 * 1024;

    /** How long {@link #close()} lets gRPC finish before it pulls the socket. */
    private static final long SHUTDOWN_SECONDS = 5;

    /** How long {@link #close()} waits for one thread to notice. */
    private static final long JOIN_MILLIS = 2000;

    /** Only for thread names, so several bridges can be told apart in a log. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final int id = COUNTER.incrementAndGet();
    private final Socket orchestrator;
    private final byte[] remainder;
    private final ServerSocket listener;
    private final int port;
    private final Thread accepter;

    /** Everything below is guarded by this, including {@link #closed}. */
    private final Object lock = new Object();
    private final List<Thread> pumps = new ArrayList<>();
    private Socket grpc;
    private ManagedChannel channel;
    private boolean closed;

    /**
     * Binds the loopback listener and starts waiting for the channel to dial in.
     *
     * @param orchestrator the accepted orchestrator connection, past its
     *                     handshake
     * @param remainder    the bytes {@link Handshake} read past the IRI
     *                     newline, to be replayed into the transport before
     *                     anything else; may be null or empty
     * @throws IOException when the loopback listener cannot be bound
     */
    public SocketBridge(Socket orchestrator, byte[] remainder) throws IOException {
        this.orchestrator = orchestrator;
        this.remainder = remainder == null ? new byte[0] : remainder;

        // Backlog 1: one connection is all this will ever serve
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.port = this.listener.getLocalPort();

        this.accepter = new Thread(this::accept, "rdfc-bridge-" + this.id + "-accept");
        this.accepter.setDaemon(true);
        this.accepter.start();
    }

    /**
     * @return the loopback port the channel dials
     */
    public int port() {
        return this.port;
    }

    /**
     * The channel that speaks to the orchestrator through this bridge.
     *
     * Built on the first call and the same one from then on, so every stub over
     * this connection shares one transport — and so {@link #close()} has a
     * single thing to shut down.
     *
     * @return the channel over this bridge
     * @throws IllegalStateException when the bridge is already closed
     */
    public ManagedChannel channel() {
        synchronized (this.lock) {
            if (this.closed) {
                throw new IllegalStateException("bridge " + this.id + " is closed");
            }
            if (this.channel == null) {
                this.channel = ManagedChannelBuilder.forTarget("127.0.0.1:" + this.port)
                        .usePlaintext()
                        .build();
            }
            return this.channel;
        }
    }

    /**
     * Accepts the one connection the channel makes, then gets the pumps going.
     *
     * The listener is closed the moment it has its connection — before the
     * pumps even start — so the window in which a second client could be queued
     * is as small as it can be made.
     */
    private void accept() {
        Socket accepted;
        try {
            accepted = this.listener.accept();
        } catch (IOException e) {
            // Ordinary: this is also how close() wakes this thread up
            LOGGER.log(Level.FINE, "bridge " + this.id + " stopped listening before it was dialled", e);
            return;
        } finally {
            closeQuietly(this.listener, "listener");
        }

        try {
            // The pumps write small frames constantly; Nagle would sit on them
            // and hand gRPC its own round trips back as latency
            accepted.setTcpNoDelay(true);
            this.orchestrator.setTcpNoDelay(true);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "bridge " + this.id + " could not configure its sockets", e);
            closeQuietly(accepted, "loopback socket");
            closeQuietly(this.orchestrator, "orchestrator socket");
            return;
        }

        synchronized (this.lock) {
            if (this.closed) {
                // Raced with close(): nobody is going to take this one down
                // anymore, so it is ours to close
                closeQuietly(accepted, "loopback socket");
                return;
            }

            this.grpc = accepted;
            this.pumps.add(pump("in", this.orchestrator, accepted, this.remainder));
            this.pumps.add(pump("out", accepted, this.orchestrator, new byte[0]));
        }
    }

    /**
     * Starts one direction.
     *
     * @param direction  {@code in} for orchestrator→gRPC, {@code out} for the
     *                   way back
     * @param source     read from
     * @param destination write to, and half-close when the source ends
     * @param prefix     written before the first copied byte
     */
    private Thread pump(String direction, Socket source, Socket destination, byte[] prefix) {
        var name = "rdfc-bridge-" + this.id + "-" + direction;
        var thread = new Thread(() -> copy(name, source, destination, prefix), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void copy(String name, Socket source, Socket destination, byte[] prefix) {
        try {
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();

            if (prefix.length > 0) {
                out.write(prefix);
                out.flush();
            }

            var buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                    // HTTP/2 is a conversation: a frame that sits in a buffer
                    // waiting for company is a stall on both ends
                    out.flush();
                }
            }

            // The source is done talking. Passing the half-close on is what
            // lets the other side see a clean end of stream — a GOAWAY that is
            // never followed by a FIN leaves the peer waiting.
            try {
                destination.shutdownOutput();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, name + " could not pass the end of stream on", e);
            }
        } catch (IOException e) {
            // Routine at teardown: whichever side went first breaks the other
            // read or write, and both sockets are of no use without the pump
            LOGGER.log(Level.FINE, name + " stopped: " + e.getMessage(), e);
            closeSockets();
        }
    }

    /**
     * Shuts the channel down, then the sockets, then waits for the threads.
     *
     * That order matters: gRPC is given its five seconds to send a GOAWAY and
     * drain, and the pumps are still running to carry it, before anything is
     * pulled out from under it.
     *
     * Idempotent, and safe at any point in this bridge's life — including
     * before anything ever dialled in, when it returns as fast as the accept
     * thread can be woken.
     */
    @Override
    public void close() {
        ManagedChannel toShutDown;
        Socket loopback;
        List<Thread> toJoin;

        synchronized (this.lock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            toShutDown = this.channel;
            loopback = this.grpc;
            toJoin = new ArrayList<>(this.pumps);
        }

        if (toShutDown != null) {
            toShutDown.shutdown();
            try {
                if (!toShutDown.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                    toShutDown.shutdownNow();
                }
            } catch (InterruptedException e) {
                toShutDown.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Closing these is what wakes the accept thread and the pumps
        closeQuietly(this.listener, "listener");
        closeQuietly(this.orchestrator, "orchestrator socket");
        closeQuietly(loopback, "loopback socket");

        join(this.accepter);
        for (Thread pump : toJoin) {
            join(pump);
        }
    }

    /**
     * Takes both sides down from inside a pump.
     *
     * Deliberately not {@link #close()}: a pump calling that would end up
     * joining itself, and the channel is not this thread's to shut down.
     */
    private void closeSockets() {
        Socket loopback;
        synchronized (this.lock) {
            loopback = this.grpc;
        }
        closeQuietly(this.orchestrator, "orchestrator socket");
        closeQuietly(loopback, "loopback socket");
    }

    private void join(Thread thread) {
        try {
            thread.join(JOIN_MILLIS);
            if (thread.isAlive()) {
                LOGGER.log(Level.FINE, "bridge " + this.id + ": " + thread.getName() + " did not stop in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(Closeable closeable, String what) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "bridge " + this.id + " could not close its " + what, e);
        }
    }
}
