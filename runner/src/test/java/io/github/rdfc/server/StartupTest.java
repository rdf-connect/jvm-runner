package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;

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
