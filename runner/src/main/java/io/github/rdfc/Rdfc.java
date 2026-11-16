package io.github.rdfc;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import io.github.rdfc.server.RdfcServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import rdfc.RunnerGrpc;

public class Rdfc {
    /**
     * The name of the subcommand that starts a server instead of a runner.
     *
     * It cannot collide with the client form: that one's first argument is an
     * orchestrator address, which always carries a colon.
     */
    static final String SERVER = "server";

    static final String USAGE = "Usage: java -jar runner-all.jar <orchestrator-host:port> <runner-uri>"
            + System.lineSeparator()
            + "       java -jar runner-all.jar server <server-config.ttl>";

    public static void main(String[] args) throws Exception {
        // First, so even the usage complaint below could be logged, and so
        // whatever a processor logs during its init lands on the console
        Logging.init();

        if (args.length > 0 && SERVER.equals(args[0])) {
            // Everything after the subcommand is that command's own argv, so
            // `server` on its own prints the server's usage and not this one
            RdfcServer.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (args.length != 2) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        connect(args[0], args[1]);
    }

    /**
     * Connects this runner to an orchestrator and blocks until the connection is
     * terminated.
     *
     * @param target the orchestrator to connect to (host:port)
     * @param uri    the URI identifying this runner
     */
    static void connect(String target, String uri) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();

        RunnerGrpc.RunnerStub stub = RunnerGrpc.newStub(channel);

        new Runner(stub, uri, channel::shutdownNow);

        channel.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }
}
