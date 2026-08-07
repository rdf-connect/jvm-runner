package io.github.rdfc.server;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private ServerSocket listener;

    /** The orchestrator's end of the connection. */
    private Socket orchestrator;

    /** The end this runner accepted, and hands to the bridge. */
    private Socket accepted;

    private SocketBridge bridge;

    /** Stands in for the gRPC channel dialling the bridge. */
    private Socket grpc;

    @BeforeEach
    void connect() throws IOException {
        this.listener = new ServerSocket(0, 1, LOOPBACK);
        this.orchestrator = new Socket(LOOPBACK, this.listener.getLocalPort());
        this.orchestrator.setSoTimeout(10_000);
        this.accepted = this.listener.accept();
    }

    @AfterEach
    void disconnect() {
        if (this.bridge != null) {
            this.bridge.close();
        }
        for (var closeable : new AutoCloseable[] { this.grpc, this.orchestrator, this.accepted, this.listener }) {
            if (closeable == null) {
                continue;
            }
            try {
                closeable.close();
            } catch (Exception ignored) {
                // several of these are closed by the bridge itself
            }
        }
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
    }

    @Test
    void closingTwiceChangesNothing() throws Exception {
        bridge(PREFACE);
        send(this.orchestrator, new byte[] { 42 });
        assertArrayEquals(concat(PREFACE, new byte[] { 42 }), receive(this.grpc, PREFACE.length + 1));

        this.bridge.close();
        assertDoesNotThrow(() -> this.bridge.close());

        assertTrue(this.accepted.isClosed());
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
