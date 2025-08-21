package io.github.rdfc;

import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import rdfc.RunnerGrpc;

public class Rdfc {
    public String name;
    public IReader reader;
    public IWriter writer;

    public static void main(String[] args) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(args[0])
                .usePlaintext()
                .build();

        RunnerGrpc.RunnerStub stub = RunnerGrpc.newStub(channel);

        new Runner(stub, args[1], () -> channel.shutdown());

        channel.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }
}
