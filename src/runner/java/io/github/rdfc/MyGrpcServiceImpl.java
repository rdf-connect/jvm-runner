package io.github.rdfc;

import io.grpc.stub.StreamObserver;
import rdfc.Orchestrator.OrchestratorMessage;
import rdfc.Runner.RunnerMessage;
import rdfc.RunnerGrpc.RunnerImplBase;

/**
 * MyGrpcServiceImpl
 */
public class MyGrpcServiceImpl extends RunnerImplBase {
    @Override
    public StreamObserver<OrchestratorMessage> connect(StreamObserver<RunnerMessage> responseObserver) {
        // TODO Auto-generated method stub
        var out = super.connect(responseObserver);

        return out;
    }

}
