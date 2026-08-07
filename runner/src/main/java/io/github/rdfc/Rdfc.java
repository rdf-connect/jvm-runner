package io.github.rdfc;

import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import rdfc.RunnerGrpc;

public class Rdfc {
    static final String USAGE = "Usage: java -jar runner-all.jar <orchestrator-host:port> <runner-uri>";

    public static void main(String[] args) throws Exception {
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
