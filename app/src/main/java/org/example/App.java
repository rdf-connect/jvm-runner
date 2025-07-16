package org.example;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;

import org.example.json.ChannelHandlerModule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
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

    public static void main(String[] args) throws Exception {
        System.out.println(new App().getGreeting());
        ManagedChannel channel = ManagedChannelBuilder.forTarget("localhost:50051")
                .usePlaintext()
                .build();
        RunnerGrpc.RunnerStub stub = RunnerGrpc.newStub(channel);

        // Server setup example
        ObjectMapper mapper = new ObjectMapper();
        Runner runner = new Runner(stub);
        mapper.registerModule(new ChannelHandlerModule(runner));

        var json = "{  \"name\": \"Arthur\",  \"reader\": { \"@type\": \"http://example.com/ns#Reader\", \"@id\": \"someReader\" }, \"writer\": { \"@type\": \"http://example.com/ns#Writer\", \"@id\": \"someWriter\" } }";
        loadClassFromJar("/tmp/testProc/lib/build/libs/lib.jar", "rdfc.proc.Library", mapper, json);
        // App inputArg = mapper.readValue(
        // App.class);
        //
        // System.out.println(inputArg.name + " " + inputArg.reader.id() + " " +
        // inputArg.writer.id());

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

    public static Object loadClassFromJar(String jarPath, String className, ObjectMapper mapper, String json)
            throws Exception {

        URL jarUrl = new URL("file://" + jarPath);
        try (URLClassLoader loader = new URLClassLoader(new URL[] { jarUrl }, App.class.getClassLoader())) {
            Class<?> clazz = loader.loadClass(className);

            mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(loader));
            // Find constructor with one argument
            Constructor<?> constructor = null;
            for (Constructor<?> c : clazz.getConstructors()) {
                if (c.getParameterCount() == 1) {
                    constructor = c;
                    break;
                }
            }
            if (constructor == null) {
                throw new RuntimeException("No single-arg constructor found");
            }

            Class<?> paramType = constructor.getParameterTypes()[0];

            System.out.println("Found type " + paramType.getTypeName());

            // Use Jackson to deserialize JSON into the param type
            Object arg = mapper.readValue(json, paramType);

            // Instantiate using default constructor
            constructor.setAccessible(true);
            return constructor.newInstance(arg);
        }
    }
}
