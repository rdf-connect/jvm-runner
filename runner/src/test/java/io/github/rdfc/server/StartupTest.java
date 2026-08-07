package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starting, and failing to start.
 *
 * A port that is already taken is the most common way this server does not come
 * up, and it is nearly always someone's own earlier copy of it still running. So
 * it is reported as one line naming the port <em>and</em> the configuration
 * property that sets it, rather than as a stack trace ending in "Address already
 * in use" — and a start that gets halfway leaves nothing bound behind it.
 */
@Timeout(60)
class StartupTest {
    /**
     * The entrypoint installs its signal hook before it starts the server — a
     * signal in between would otherwise find no hook — so a shutdown really can
     * arrive before, or during, the binding.
     *
     * Whichever of the two gets the lock first, the invariant is the same and it
     * is the one that matters: once both have returned, nothing is listening. The
     * ordering this asserts directly is "shutdown first", because that is the one
     * that used to end with a server reporting it had stopped and then opening
     * two ports anyway.
     */
    @Test
    void aShutdownBeforeTheStartLeavesNothingBound(@TempDir Path dir) throws Exception {
        int[] ports = freePorts();
        RunnerServer server = new RunnerServer(ServerFixture.config(dir), ports[0], ports[1],
                RunnerServer.MAX_GRPC_CONNECTIONS);

        server.shutdown();
        assertFalse(server.isServing());

        // Returns without binding, rather than throwing at an entrypoint that is
        // already on its way out
        assertDoesNotThrow(server::start);

        assertFalse(server.isServing(), "a stopped server came up anyway");
        assertNothingBound(ports);
        // And the entrypoint's wait is already over
        server.awaitShutdown();
    }

    /**
     * The same invariant when the two genuinely race, which is what a signal
     * landing mid-start does.
     */
    @Test
    void aShutdownRacingTheStartLeavesNothingBound(@TempDir Path dir) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            int[] ports = freePorts();
            RunnerServer server = new RunnerServer(ServerFixture.config(dir), ports[0], ports[1],
                    RunnerServer.MAX_GRPC_CONNECTIONS);

            CyclicBarrier together = new CyclicBarrier(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread starting = new Thread(() -> {
                try {
                    together.await(10, TimeUnit.SECONDS);
                    server.start();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "start");
            Thread stopping = new Thread(() -> {
                try {
                    together.await(10, TimeUnit.SECONDS);
                    server.shutdown();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "stop");

            starting.start();
            stopping.start();
            starting.join();
            stopping.join();

            assertNull(failure.get(), "starting and stopping at once failed: " + failure.get());
            assertFalse(server.isServing(), "the server was left serving after a shutdown");
            assertNothingBound(ports);
        }
    }

    /**
     * Two ports nothing is listening on.
     *
     * Fixed rather than ephemeral, because the point of the tests above is to
     * look at ports <em>after</em> a start that may have decided not to bind
     * anything — and a server that bound nothing cannot report which port it
     * would have used.
     *
     * @return the gRPC and HTTP port to use
     */
    private static int[] freePorts() throws IOException {
        try (ServerSocket grpc = new ServerSocket(0); ServerSocket http = new ServerSocket(0)) {
            return new int[] { grpc.getLocalPort(), http.getLocalPort() };
        }
    }

    private static void assertNothingBound(int[] ports) {
        for (int port : ports) {
            assertDoesNotThrow(() -> {
                new ServerSocket(port).close();
            }, "port " + port + " was left bound");
        }
    }

    @Test
    void aTakenGrpcPortIsReportedWithItsProperty(@TempDir Path dir) throws Exception {
        ServerConfig config = ServerFixture.config(dir);

        RunnerServer first = new RunnerServer(config, 0, 0, RunnerServer.MAX_GRPC_CONNECTIONS);
        first.start();
        try {
            RunnerServer second = new RunnerServer(config, first.boundGrpcPort(), 0,
                    RunnerServer.MAX_GRPC_CONNECTIONS);
            ServerStartupError error = assertThrows(ServerStartupError.class, second::start);

            assertTrue(error.getMessage().contains("gRPC"), error.getMessage());
            assertTrue(error.getMessage().contains(Integer.toString(first.boundGrpcPort())), error.getMessage());
            assertTrue(error.getMessage().contains("rdfc:grpcPort"), error.getMessage());
        } finally {
            first.shutdown();
        }
    }

    /**
     * The HTTP listener is opened second, so its failure has to undo the gRPC one
     * — otherwise a server that never started keeps a port that its next attempt
     * then cannot bind.
     */
    @Test
    void aTakenHttpPortIsReportedAndRollsTheGrpcListenerBack(@TempDir Path dir) throws Exception {
        ServerConfig config = ServerFixture.config(dir);

        RunnerServer first = new RunnerServer(config, 0, 0, RunnerServer.MAX_GRPC_CONNECTIONS);
        first.start();

        int grpcPort;
        try (ServerSocket free = new ServerSocket(0)) {
            grpcPort = free.getLocalPort();
        }

        try {
            RunnerServer second = new RunnerServer(config, grpcPort, first.boundHttpPort(),
                    RunnerServer.MAX_GRPC_CONNECTIONS);
            ServerStartupError error = assertThrows(ServerStartupError.class, second::start);

            assertTrue(error.getMessage().contains("HTTP"), error.getMessage());
            assertTrue(error.getMessage().contains(Integer.toString(first.boundHttpPort())), error.getMessage());
            assertTrue(error.getMessage().contains("rdfc:httpPort"), error.getMessage());

            // The port the failed start had already taken is free again
            assertDoesNotThrow(() -> {
                new ServerSocket(grpcPort).close();
            }, "the gRPC listener of a failed start was left bound");
        } finally {
            first.shutdown();
        }
    }
}
