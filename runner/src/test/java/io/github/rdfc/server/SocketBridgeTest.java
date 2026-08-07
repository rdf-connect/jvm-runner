package io.github.rdfc.server;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The detour a remote connection's bytes take.
 *
 * The bridge is transport and nothing else, so it is tested as transport: two
 * ends of a real connection, a real client on the loopback port standing in for
 * the {@code ManagedChannel}, and byte-for-byte comparisons in both directions.
 * gRPC actually running over this belongs to the server task that assembles it.
 */
@Timeout(30)
class SocketBridgeTest {
    /** What the orchestrator sends the moment the handshake line is out. */
    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(US_ASCII);

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** The connection this test's bridge serves. */
    private Connection connection;

    /** The orchestrator's end of the connection. */
    private Socket orchestrator;

    /** The end this runner accepted, and hands to the bridge. */
    private Socket accepted;

    private SocketBridge bridge;

    /** Stands in for the gRPC channel dialling the bridge. */
    private Socket grpc;

    /** A real TCP connection, both ends of it. */
    private static final class Connection implements AutoCloseable {
        private final ServerSocket listener;

        /** The end the orchestrator writes on. */
        final Socket client;

        /** The end the runner accepted. */
        final Socket accepted;

        Connection() throws IOException {
            this.listener = new ServerSocket(0, 1, LOOPBACK);
            this.client = new Socket(LOOPBACK, this.listener.getLocalPort());
            this.client.setSoTimeout(10_000);
            this.accepted = this.listener.accept();
        }

        @Override
        public void close() {
            closeQuietly(this.client);
            closeQuietly(this.accepted);
            closeQuietly(this.listener);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // in these tests half of them are closed by the bridge itself
        }
    }

    @BeforeEach
    void connect() throws IOException {
        this.connection = new Connection();
        this.orchestrator = this.connection.client;
        this.accepted = this.connection.accepted;
    }

    @AfterEach
    void disconnect() {
        if (this.bridge != null) {
            this.bridge.close();
        }
        closeQuietly(this.grpc);
        this.connection.close();
    }

    /** Puts the bridge up and dials it, as the channel would. */
    private void bridge(byte[] remainder) throws IOException {
        this.bridge = new SocketBridge(this.accepted, remainder);
        this.grpc = new Socket(LOOPBACK, this.bridge.port());
        this.grpc.setSoTimeout(10_000);
    }

    private static byte[] sweep(int offset) {
        var bytes = new byte[256];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + offset);
        }
        return bytes;
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static void send(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    /** Reads exactly that many bytes, or fails saying how far it got. */
    private static byte[] receive(Socket socket, int count) throws IOException {
        var bytes = new byte[count];
        var filled = 0;
        while (filled < count) {
            var read = socket.getInputStream().read(bytes, filled, count - filled);
            if (read < 0) {
                fail("the stream ended after " + filled + " of " + count + " bytes");
            }
            filled += read;
        }
        return bytes;
    }

    /**
     * The handshake remainder goes in first and unchanged, and everything the
     * orchestrator says afterwards follows it in order.
     */
    @Test
    void theRemainderIsReplayedBeforeTheRest() throws Exception {
        var remainder = concat(PREFACE, sweep(0));
        bridge(remainder);

        var afterwards = sweep(128);
        send(this.orchestrator, afterwards);

        assertArrayEquals(concat(remainder, afterwards), receive(this.grpc, remainder.length + afterwards.length),
                "the bytes reaching the gRPC side are not the ones the orchestrator sent");
    }

    /** A caller that has no remainder to replay may say so with a null. */
    @Test
    void aNullRemainderIsTreatedAsNothingToReplay() throws Exception {
        bridge(null);

        var sent = sweep(0);
        send(this.orchestrator, sent);

        assertArrayEquals(sent, receive(this.grpc, sent.length));
    }

    /** More than one turn through the copy loop, and its buffer is 64 KiB. */
    @Test
    void aPayloadLargerThanTheCopyBufferArrivesIntact() throws Exception {
        bridge(new byte[0]);

        var payload = new byte[200_000];
        new Random(20260807).nextBytes(payload);

        // From a thread of its own: 200 KB is more than the sockets along the
        // way will hold, so the writer has to be able to block while this test
        // drains the other end
        var writer = new Thread(() -> {
            try {
                send(this.orchestrator, payload);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();

        assertArrayEquals(payload, receive(this.grpc, payload.length));
        writer.join();
    }

    /** And the way back is a plain copy. */
    @Test
    void theGrpcSideReachesTheOrchestratorUnchanged() throws Exception {
        bridge(new byte[0]);

        var sent = concat(sweep(0), sweep(64));
        send(this.grpc, sent);

        assertArrayEquals(sent, receive(this.orchestrator, sent.length));
    }

    /** Both directions at once, since they are two independent threads. */
    @Test
    void bothDirectionsCarryAtTheSameTime() throws Exception {
        bridge(PREFACE);

        var up = sweep(0);
        var down = sweep(3);
        send(this.orchestrator, up);
        send(this.grpc, down);

        assertArrayEquals(concat(PREFACE, up), receive(this.grpc, PREFACE.length + up.length));
        assertArrayEquals(down, receive(this.orchestrator, down.length));
    }

    /**
     * A half-close is how a peer says it is done talking; swallowing it leaves
     * the other end waiting for a message that is never coming.
     */
    @Test
    void theOrchestratorsHalfCloseReachesTheGrpcSide() throws Exception {
        bridge(new byte[0]);

        var sent = sweep(0);
        send(this.orchestrator, sent);
        this.orchestrator.shutdownOutput();

        assertArrayEquals(sent, receive(this.grpc, sent.length));
        assertEquals(-1, this.grpc.getInputStream().read(), "the end of the orchestrator's stream was not passed on");
    }

    @Test
    void theGrpcSidesHalfCloseReachesTheOrchestrator() throws Exception {
        bridge(new byte[0]);

        var sent = sweep(7);
        send(this.grpc, sent);
        this.grpc.shutdownOutput();

        assertArrayEquals(sent, receive(this.orchestrator, sent.length));
        assertEquals(-1, this.orchestrator.getInputStream().read(), "the end of the gRPC stream was not passed on");
    }

    /**
     * The end nothing else reports.
     *
     * Both directions half-close, both pumps return normally, no exception is
     * thrown anywhere — and without the completion count that is a bridge
     * holding two file descriptors open for a conversation that is over, with
     * nobody told.
     */
    @Test
    void aCleanEndOnBothSidesTakesTheBridgeDown() throws Exception {
        bridge(new byte[0]);

        send(this.orchestrator, new byte[] { 42 });
        assertArrayEquals(new byte[] { 42 }, receive(this.grpc, 1));

        this.orchestrator.shutdownOutput();
        assertEquals(-1, this.grpc.getInputStream().read());
        this.grpc.shutdownOutput();
        assertEquals(-1, this.orchestrator.getInputStream().read());

        this.bridge.done().get(5, TimeUnit.SECONDS);
        assertTrue(this.accepted.isClosed(), "the orchestrator socket was left open on a finished bridge");
    }

    /**
     * One connection, ever. Anything else could get itself pumped into an
     * orchestrator's session, and a gRPC reconnect has to fail rather than
     * quietly attach to nothing.
     */
    @Test
    void aSecondConnectionIsRefused() throws Exception {
        bridge(new byte[0]);

        // Sync: once a byte has come through, the listener is definitely closed
        send(this.orchestrator, new byte[] { 42 });
        assertArrayEquals(new byte[] { 42 }, receive(this.grpc, 1));

        var port = this.bridge.port();
        assertThrows(ConnectException.class, () -> new Socket(LOOPBACK, port));
    }

    /** A connection nobody ever dialled still has to come down cleanly. */
    @Test
    void closingBeforeAnythingConnectedReturnsAtOnce() throws Exception {
        this.bridge = new SocketBridge(this.accepted, PREFACE);
        var port = this.bridge.port();

        var started = System.nanoTime();
        this.bridge.close();
        var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsed < 3000, "closing an unused bridge took " + elapsed + " ms");
        assertTrue(this.accepted.isClosed(), "the orchestrator socket was left open");
        assertThrows(ConnectException.class, () -> new Socket(LOOPBACK, port), "the listener was left open");
        assertTrue(this.bridge.done().isDone(), "a closed bridge has to report itself finished");
    }

    /**
     * The accept and the close landing at the same moment.
     *
     * There are three orderings and the bridge has to survive all of them: the
     * close gets there first and the connect is refused; the connect is
     * accepted, pumps and all, and the close takes those down; or — the window
     * that is a handful of instructions wide — a socket is accepted after
     * {@code close()} already took its snapshot of what to take down, and the
     * accept thread has to close it itself.
     *
     * Both threads are held at a barrier so the first two are genuinely raced
     * rather than decided by how long a thread takes to start, and every other
     * attempt waits for the connection to be up before closing, so the accepted
     * ordering is covered whichever way the scheduler leans. In all of them: no
     * hang, both sockets down, the owner told.
     */
    @Test
    void aConnectionRacingTheCloseIsNotLeftBehind() throws Exception {
        for (var attempt = 0; attempt < 50; attempt++) {
            try (var connection = new Connection()) {
                var racer = new SocketBridge(connection.accepted, PREFACE);
                var port = racer.port();

                var start = new CyclicBarrier(2);
                var dialled = new CompletableFuture<Socket>();
                var dialler = new Thread(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        dialled.complete(new Socket(LOOPBACK, port));
                    } catch (IOException refused) {
                        // The close got there first, which is just as correct
                        dialled.complete(null);
                    } catch (Exception e) {
                        dialled.completeExceptionally(e);
                    }
                }, "test-dialler");
                dialler.start();

                start.await(5, TimeUnit.SECONDS);
                if (attempt % 2 == 1) {
                    // Half the attempts do not race at all: the connection is
                    // definitely up and being pumped before the close begins
                    dialled.get(5, TimeUnit.SECONDS);
                }
                racer.close();

                assertNull(racer.done().get(5, TimeUnit.SECONDS), "attempt " + attempt + " never finished");
                assertTrue(connection.accepted.isClosed(), "attempt " + attempt + " left the orchestrator socket open");

                var socket = dialled.get(5, TimeUnit.SECONDS);
                if (socket != null) {
                    assertDead(socket, "attempt " + attempt + " left a dialled-in socket alive");
                    socket.close();
                }
                dialler.join(5000);
            }
        }
    }

    /**
     * Asserts a socket is finished: it ends, promptly.
     *
     * Not that it is empty — a connection that was accepted before the close
     * has the remainder the pump already replayed sitting in it, and that is
     * correct. What may not happen is that it stays open waiting for more.
     */
    private static void assertDead(Socket socket, String message) throws IOException {
        socket.setSoTimeout(2000);
        var buffer = new byte[4096];
        try {
            while (socket.getInputStream().read(buffer) >= 0) {
                // drain whatever made it through before the close
            }
        } catch (SocketTimeoutException e) {
            fail(message + " (it is still waiting for data)");
        } catch (IOException reset) {
            // A connection the OS refused after the fact answers with a reset,
            // which says the same thing
        }
    }

    @Test
    void closingTwiceChangesNothing() throws Exception {
        bridge(PREFACE);
        send(this.orchestrator, new byte[] { 42 });
        assertArrayEquals(concat(PREFACE, new byte[] { 42 }), receive(this.grpc, PREFACE.length + 1));

        this.bridge.close();
        assertDoesNotThrow(() -> this.bridge.close());

        assertTrue(this.accepted.isClosed());
        assertTrue(this.bridge.done().isDone());
        assertEquals(-1, readOrEnd(this.grpc), "the gRPC side was left hanging on a closed bridge");
    }

    /**
     * The channel is the bridge's own and goes down with it — otherwise a
     * connection that ended leaves a transport behind that keeps retrying into
     * a loopback port that is gone.
     */
    @Test
    void theChannelIsBuiltOnceAndShutDownByClose() throws Exception {
        bridge(new byte[0]);

        var channel = this.bridge.channel();
        assertSame(channel, this.bridge.channel(), "a second call built a second channel");
        assertEquals("127.0.0.1:" + this.bridge.port(), channel.authority());

        this.bridge.close();

        assertTrue(channel.isShutdown(), "the channel outlived the bridge");
        assertTrue(channel.awaitTermination(5, TimeUnit.SECONDS), "the channel never terminated");
        assertThrows(IllegalStateException.class, () -> this.bridge.channel());
    }

    /** A closed connection reads as an end of stream, or fails outright. */
    private static int readOrEnd(Socket socket) throws IOException {
        try (InputStream in = socket.getInputStream()) {
            return in.read();
        } catch (IOException e) {
            return -1;
        }
    }
}
