package org.example;

import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import rdfc.RunnerGrpc;

public class App {
    public String name;
    public IReader reader;
    public IWriter writer;

    public String getGreeting() {
        return "Hello World!";
    }

    public static void main(String[] args) throws Exception {
        for (var arg : args) {
            System.out.println("Argument: " + arg);
        }
        ManagedChannel channel = ManagedChannelBuilder.forTarget("dns:///127.0.0.1:50051")
                .usePlaintext()
                .build();
        RunnerGrpc.RunnerStub stub = RunnerGrpc.newStub(channel);

        new Runner(stub, args[1]);

        channel.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }
}
