package org.example;

import java.io.IOException;

import org.example.json.ChannelHandlerModule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import rdfc.Orchestrator.Identify;
import rdfc.Orchestrator.OrchestratorMessage;
import rdfc.Runner.RunnerMessage;
import rdfc.RunnerGrpc;
import rdfc.Common.StreamMessage;

public class App {
    public String name;
    public IReader reader;
    public IWriter writer;

    public String getGreeting() {
        return "Hello World!";
    }

    public static void main(String[] args) throws IOException {
        System.out.println(new App().getGreeting());
        ManagedChannel channel = ManagedChannelBuilder.forTarget("localhost:50051")
                .usePlaintext()
                .build();
        RunnerGrpc.RunnerStub stub = RunnerGrpc.newStub(channel);

        // Server setup example
        ObjectMapper mapper = new ObjectMapper();
        Runner runner = new Runner(stub);
        mapper.registerModule(new ChannelHandlerModule(runner));

        App inputArg = mapper.readValue(
                "{  \"name\": \"Arthur\",  \"reader\": { \"@type\": \"http://example.com/ns#Reader\", \"@id\": \"someReader\" }, \"writer\": { \"@type\": \"http://example.com/ns#Writer\", \"@id\": \"someWriter\" } }",
                App.class);

        System.out.println(inputArg.name + " " + inputArg.reader.id + "  " + inputArg.writer.id);

        //
        // StreamObserver<RunnerMessage> responseObserver = new
        // StreamObserver<RunnerMessage>() {
        // @Override
        // public void onNext(RunnerMessage message) {
        // System.out.println("Received from server: " + message.getProc());
        // }
        //
        // @Override
        // public void onError(Throwable t) {
        // System.err.println("Error from server: " + t);
        // }
        //
        // @Override
        // public void onCompleted() {
        // System.out.println("Server completed");
        // }
        // };
        //
        // StreamObserver<OrchestratorMessage> requestObserver =
        // stub.connect(responseObserver);
        //
        // var identify = Identify.newBuilder();
        // identify.setUriBytes(ByteString.copyFromUtf8(""));
        // requestObserver.onNext(OrchestratorMessage.newBuilder().setIdentify(identify).build());
        //
        // var streamMessage = StreamMessage.newBuilder();
        // streamMessage.getIdBuilder().setId(0);
        // var msgBuilder =
        // OrchestratorMessage.newBuilder().setStreamMsg(streamMessage).build();
        //
        // // send messages
        // requestObserver.onNext(msgBuilder);
        //
        // // when done sending
        // requestObserver.onCompleted();
    }
}
