package io.github.rdfc.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens on the gRPC port, from the first byte to the entry in the
 * dashboard's history.
 *
 * The one thing this deliberately does <b>not</b> do is speak gRPC back. An
 * orchestrator is a gRPC <em>server</em> on a socket it opened itself, and
 * grpc-java cannot be given an accepted socket to serve on — which is the whole
 * reason {@link SocketBridge} exists, and is why that inversion is tested there,
 * against a hand-written peer. What is left for this class is everything around
 * it: who is let in, who is refused, and that a connection that ends leaves
 * nothing behind — no state entry, no connection slot, no socket.
 */
@Timeout(90)
class ConnectionFlowTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final List<Socket> sockets = new ArrayList<>();
    private RunnerServer server;

    @AfterEach
    void stop() {
        for (Socket socket : this.sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // a test that already closed it
            }
        }
        if (this.server != null) {
            this.server.shutdown();
        }
    }

    private RunnerServer start(Path dir, int maxConnections) throws Exception {
        this.server = new RunnerServer(ServerFixture.config(dir), 0, 0, maxConnections);
        this.server.start();
        return this.server;
    }

    private RunnerServer start(Path dir, int maxConnections, long establishMillis) throws Exception {
        this.server = new RunnerServer(ServerFixture.config(dir), 0, 0, maxConnections, establishMillis);
        this.server.start();
        return this.server;
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket(LOOPBACK, this.server.boundGrpcPort());
        socket.setSoTimeout(10_000);
        this.sockets.add(socket);
        return socket;
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(UTF_8));
        socket.getOutputStream().flush();
    }

    /**
     * @return true when the peer closed this socket
     */
    private static boolean closedByPeer(Socket socket) {
        try {
            return socket.getInputStream().read() < 0;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            // Reset rather than a clean close; from here that is the same answer
            return true;
        }
    }

    /**
     * The same question for a connection that got as far as having a bridge.
     *
     * {@link #closedByPeer} reads one byte and asks whether it was the end; past
     * the handshake there is something to read first — the runner's channel put
     * its HTTP/2 preface and a SETTINGS frame on this socket before anything went
     * wrong — so the answer only comes after draining it.
     *
     * @param socket the orchestrator's end
     * @return true when the peer closed it, false when it went quiet instead
     */
    private static boolean atEndOfStream(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        byte[] chunk = new byte[4096];
        try {
            while (socket.getInputStream().read(chunk) >= 0) {
                // gRPC talking to an orchestrator that never was one
            }
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            // Reset rather than a clean close; from here that is the same answer
            return true;
        }
    }

    /**
     * A peer that does not send a usable IRI is dropped, and nothing about it is
     * remembered: it never was a runner.
     */
    @Test
    void aFailedHandshakeClosesTheConnectionAndRegistersNothing(@TempDir Path dir) throws Exception {
        start(dir, RunnerServer.MAX_GRPC_CONNECTIONS);

        Socket socket = connect();
        write(socket, "\n");

        assertTrue(closedByPeer(socket), "the server kept a connection that sent no runner IRI");
        assertEquals(List.of(), this.server.state().snapshot(), "a failed handshake registered a runner");
        ServerFixture.await(() -> this.server.activeConnections() == 0, "the connection slot is handed back");
    }

    /**
     * The same for a peer that never sends a newline at all and then goes away.
     */
    @Test
    void aTruncatedHandshakeRegistersNothing(@TempDir Path dir) throws Exception {
        start(dir, RunnerServer.MAX_GRPC_CONNECTIONS);

        Socket socket = connect();
        write(socket, "http://example.org/runner");
        socket.shutdownOutput();

        assertTrue(closedByPeer(socket), "the server kept a connection that never finished its line");
        assertEquals(List.of(), this.server.state().snapshot());
        ServerFixture.await(() -> this.server.activeConnections() == 0, "the connection slot is handed back");
    }

    /**
     * The whole life of a connection: the IRI is accepted, the runner shows up in
     * the state, and when the orchestrator drops the connection the runner moves
     * into the history with everything released.
     *
     * It ends in {@code error} and not in {@code done} on purpose — the peer went
     * away before this runner had run anything, and the dashboard has to be able
     * to tell that apart from a pipeline that finished.
     */
    @Test
    void aConnectionThatDropsEndsUpInTheHistory(@TempDir Path dir) throws Exception {
        start(dir, RunnerServer.MAX_GRPC_CONNECTIONS);

        Socket socket = connect();
        write(socket, "http://example.org/runner/dropped\n");

        ServerFixture.await(() -> !this.server.state().snapshot().isEmpty(), "the runner is registered");
        Map<String, Object> running = this.server.state().snapshot().get(0);
        assertEquals("http://example.org/runner/dropped", running.get("uri"));
        assertEquals(LOOPBACK.getHostAddress(), running.get("host"));

        socket.close();

        ServerFixture.await(() -> this.server.activeConnections() == 0, "the connection slot is handed back");
        List<Map<String, Object>> snapshot = this.server.state().snapshot();
        assertEquals(1, snapshot.size(), "the runner was not kept in the history");

        Map<String, Object> gone = snapshot.get(0);
        assertEquals("error", gone.get("status"), "a connection that dropped mid-run was reported as a clean end");
        assertNotNull(gone.get("disconnectedAt"), "the runner was never marked as disconnected");
    }

    /**
     * A peer that gets past the handshake and then goes silent is evicted.
     *
     * The handshake budget covers the IRI line and nothing else, so writing one
     * line used to buy a connection slot for as long as this process ran: no read
     * timeout on the pumps, no deadline on the channel, nothing waiting on a
     * clock. Thirty-two of these and the server was closed for business until
     * somebody restarted it.
     *
     * The deadline is a constructor parameter here for the obvious reason — the
     * real one is {@link RunnerServer#ESTABLISH_MILLIS} and this test would
     * otherwise take half a minute to watch a socket not be spoken on.
     */
    @Test
    void aConnectionThatNeverEstablishesIsEvicted(@TempDir Path dir) throws Exception {
        start(dir, RunnerServer.MAX_GRPC_CONNECTIONS, 500);

        Socket socket = connect();
        // A perfectly good IRI line, and then never a byte of HTTP/2: the channel
        // this server dials back never reaches READY
        write(socket, "urn:test:silent\n");

        ServerFixture.await(() -> !this.server.state().snapshot().isEmpty(), "the runner is registered");

        ServerFixture.await(() -> this.server.activeConnections() == 0, "the connection slot is handed back");
        assertTrue(atEndOfStream(socket), "the socket of an evicted connection was left open");

        List<Map<String, Object>> snapshot = this.server.state().snapshot();
        assertEquals(1, snapshot.size(), "the evicted runner was not kept in the history");
        assertEquals("error", snapshot.get(0).get("status"), "an eviction was reported as a clean end");
        assertNotNull(snapshot.get(0).get("disconnectedAt"), "the runner was never marked as disconnected");
    }

    /**
     * Past the cap the connection is refused at once — accepted so the operating
     * system does not answer with a reset, then closed with a line in the log,
     * which is a far better failure than a server that takes everything and
     * thrashes.
     *
     * The cap is a constructor parameter for this test. Proving it by opening
     * thirty-two connections would take thirty-two sockets and their bridges to
     * prove a {@code >=}, and the sockets would have to survive the five-second
     * handshake timeout while doing it.
     */
    @Test
    void aConnectionPastTheCapIsRefused(@TempDir Path dir) throws Exception {
        start(dir, 2);

        Socket first = connect();
        Socket second = connect();
        // Nothing is written: both park in their handshake, which is exactly the
        // state a slow orchestrator would be in
        ServerFixture.await(() -> this.server.activeConnections() == 2, "both connections are being served");

        Socket third = connect();
        third.setSoTimeout(5_000);
        assertTrue(closedByPeer(third), "the connection past the cap was not refused");

        // And the two that were let in are still there
        assertEquals(2, this.server.activeConnections());
        first.setSoTimeout(200);
        second.setSoTimeout(200);
        assertThrows(SocketTimeoutException.class, () -> first.getInputStream().read(),
                "a connection inside the cap was dropped");
        assertThrows(SocketTimeoutException.class, () -> second.getInputStream().read(),
                "a connection inside the cap was dropped");
    }

    /**
     * A server that is stopping refuses rather than accepts: a connection taken
     * during the shutdown is one that would be cancelled a moment later, which
     * from the orchestrator's side is indistinguishable from a runner that
     * crashed.
     */
    @Test
    void aConnectionArrivingDuringShutdownIsRefused(@TempDir Path dir) throws Exception {
        start(dir, RunnerServer.MAX_GRPC_CONNECTIONS);
        int port = this.server.boundGrpcPort();

        this.server.shutdown();

        // The listener is closed, so this is refused by the operating system now
        assertThrows(IOException.class, () -> new Socket(LOOPBACK, port));
    }
}
