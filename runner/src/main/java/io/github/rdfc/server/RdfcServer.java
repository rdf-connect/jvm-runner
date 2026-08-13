package io.github.rdfc.server;

import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.logging.Logger;

import io.github.rdfc.Logging;

/**
 * The entrypoint of server mode: {@code java -jar runner-all.jar server
 * <config.ttl>}.
 *
 * It does the four things a {@code main} owes its operator — check the
 * arguments, set logging up, report a bad configuration as one line instead of a
 * stack trace, and stop cleanly on a signal — and leaves everything else to
 * {@link RunnerServer}.
 *
 * The exit codes are the ones the js- and py-runners use: <b>2</b> for being
 * called wrongly, <b>1</b> for a configuration or a port this server cannot
 * work with, <b>0</b> for a server that was asked to stop.
 */
public final class RdfcServer {
    private static final Logger LOGGER = Logger.getLogger(RdfcServer.class.getName());

    static final String USAGE = "usage: java -jar runner-all.jar server <server-config.ttl>";

    private RdfcServer() {
    }

    /**
     * Starts a runner server and blocks until it is stopped.
     *
     * @param args exactly one: the path of the server configuration
     */
    public static void main(String[] args) {
        // First, so even the complaints below are logged the way everything else
        // is. Idempotent, so coming here through Rdfc costs nothing.
        Logging.init();

        if (args.length != 1) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        RunnerServer server;
        try {
            server = new RunnerServer(ServerConfig.parse(Paths.get(args[0])));
        } catch (ConfigException | InvalidPathException e) {
            // Expected, operator-actionable failures: the reason, not a traceback
            LOGGER.severe(e.getMessage());
            System.exit(1);
            return;
        }

        // Installed before the listeners are opened, not after: a signal arriving
        // in between would otherwise find no hook and kill a process that has
        // ports bound and possibly a connection on them. shutdown() on a server
        // that never started is a no-op.
        //
        // SIGINT and SIGTERM both land here. The hook returning is what lets the
        // JVM finish exiting, so the shutdown has to happen inside it rather than
        // only unblocking the main thread.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server.isServing()) {
                // Straight to stderr, and not through the logger: the JDK's own
                // shutdown hook resets every log handler, it runs concurrently
                // with this one, and it usually wins — so a stop that is only
                // logged is a stop that is regularly invisible. The lines inside
                // shutdown() stay for everyone who calls it while the process is
                // still alive.
                //
                // Only when there is something to stop: this hook also runs on
                // the System.exit below, where a port that could not be bound is
                // the story and "stopping" is noise on top of it.
                System.err.println("Stopping the JVM runner server...");
            }
            server.shutdown();
        }, "rdfc-server-shutdown"));

        try {
            server.start();
        } catch (ServerStartupException e) {
            LOGGER.severe(e.getMessage());
            System.exit(1);
            return;
        }

        try {
            server.awaitShutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.shutdown();
        }
    }
}
