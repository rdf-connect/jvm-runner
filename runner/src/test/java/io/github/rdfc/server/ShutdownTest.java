package io.github.rdfc.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stopping.
 *
 * A server that is asked to stop has to actually stop, inside the grace it
 * promises: a container runtime that sends SIGTERM follows it with SIGKILL, and
 * a shutdown that runs past that is a shutdown nobody ever completes. The three
 * things that have to be true afterwards are here — it returned in time, both
 * ports are closed, and the runner that was live is accounted for in the state
 * rather than left hanging in it.
 */
@Timeout(90)
class ShutdownTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void shutdownEndsALiveConnectionWithinTheGrace(@TempDir Path dir) throws Exception {
        RunnerServer server = new RunnerServer(ServerFixture.config(dir), 0, 0,
                RunnerServer.MAX_GRPC_CONNECTIONS);
        server.start();

        int grpcPort = server.boundGrpcPort();
        int httpPort = server.boundHttpPort();

        Socket orchestrator = new Socket(LOOPBACK, grpcPort);
        orchestrator.setSoTimeout(10_000);
        orchestrator.getOutputStream().write("http://example.org/runner/live\n".getBytes(UTF_8));
        orchestrator.getOutputStream().flush();

        // Past the handshake and into a runner: from here the connection holds a
        // bridge, a channel and a thread, which is what the shutdown has to undo
        ServerFixture.await(() -> !server.state().snapshot().isEmpty(), "the runner is registered");
        assertEquals(1, server.activeConnections());

        long started = System.nanoTime();
        server.shutdown();
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(took <= RunnerServer.SHUTDOWN_GRACE_MILLIS,
                "the shutdown took " + took + " ms, past its own " + RunnerServer.SHUTDOWN_GRACE_MILLIS
                        + " ms grace");

        // Both listeners are gone
        assertThrows(IOException.class, () -> new Socket(LOOPBACK, grpcPort), "the gRPC port is still open");
        assertThrows(IOException.class, () -> new Socket(LOOPBACK, httpPort), "the HTTP port is still open");

        // And the runner that was live is in the history, not still "running"
        assertEquals(0, server.activeConnections(), "a connection was left behind");
        List<Map<String, Object>> snapshot = server.state().snapshot();
        assertEquals(1, snapshot.size());
        assertNotNull(snapshot.get(0).get("disconnectedAt"), "the runner was never marked as disconnected");
        assertTrue(atEndOfStream(orchestrator), "the orchestrator's socket was left open");

        orchestrator.close();
    }

    /**
     * Drains a socket and says whether it ended.
     *
     * There is something to drain: the runner's channel already wrote its HTTP/2
     * preface and a SETTINGS frame through the bridge, so the orchestrator side
     * has bytes waiting on it before it ever sees the close.
     *
     * @param socket the orchestrator's end
     * @return true when the peer closed it, false when it went quiet instead
     */
    private static boolean atEndOfStream(Socket socket) throws IOException {
        socket.setSoTimeout(5_000);
        byte[] chunk = new byte[4096];
        try {
            while (socket.getInputStream().read(chunk) >= 0) {
                // gRPC's preface, on its way to an orchestrator that never was one
            }
            return true;
        } catch (java.net.SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            // Reset rather than a clean close; from here that is the same answer
            return true;
        }
    }

    /**
     * The runtime hook and a {@code shutdown()} elsewhere arrive together often
     * enough that the second caller may not return into a half-stopped server: it
     * waits for the first one instead.
     */
    @Test
    void shutdownIsIdempotentAndTheSecondCallerWaits(@TempDir Path dir) throws Exception {
        RunnerServer server = new RunnerServer(ServerFixture.config(dir), 0, 0,
                RunnerServer.MAX_GRPC_CONNECTIONS);
        server.start();
        int httpPort = server.boundHttpPort();

        CountDownLatch both = new CountDownLatch(2);
        Runnable stop = () -> {
            server.shutdown();
            both.countDown();
        };

        Thread first = new Thread(stop, "stop-1");
        Thread second = new Thread(stop, "stop-2");
        first.start();
        second.start();
        first.join();
        second.join();

        assertTrue(both.await(1, TimeUnit.SECONDS), "a shutdown call never returned");
        assertThrows(IOException.class, () -> new Socket(LOOPBACK, httpPort));

        // A third one, on a server that is long since stopped, still returns
        server.shutdown();
    }
}
