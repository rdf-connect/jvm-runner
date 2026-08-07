package io.github.rdfc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import rdfc.RunnerGrpc;

/**
 * A gRPC {@link Channel} that never leaves the JVM: every call is answered by
 * this object instead of by a server.
 *
 * It records everything the runner sends, per method, lets a test push
 * responses back onto a call, and — the point of the whole thing — lets a test
 * react <em>synchronously, from inside the send</em>. That models the fastest
 * possible orchestrator: one that acknowledges a message before the sending
 * call even returned.
 */
class FakeOrchestrator extends Channel {
    /** The most recent call per full method name. */
    private final Map<String, FakeCall<?, ?>> calls = new ConcurrentHashMap<>();
    /** Everything the runner sent, per full method name. */
    private final Map<String, List<Object>> sent = new ConcurrentHashMap<>();
    /** Hooks run from inside sendMessage, per full method name. */
    private final Map<String, Consumer<Object>> hooks = new ConcurrentHashMap<>();

    static RunnerGrpc.RunnerStub stub(FakeOrchestrator orchestrator) {
        return RunnerGrpc.newStub(orchestrator);
    }

    @Override
    public String authority() {
        return "fake-orchestrator";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method, CallOptions options) {
        var call = new FakeCall<ReqT, RespT>(method.getFullMethodName());
        this.calls.put(method.getFullMethodName(), call);
        return call;
    }

    /**
     * Everything the runner sent on this method, oldest first.
     *
     * @param <ReqT>  request type of the method
     * @param <RespT> response type of the method
     * @param method  the method to look at
     * @return a snapshot of the sent messages
     */
    @SuppressWarnings("unchecked")
    <ReqT, RespT> List<ReqT> sentOn(MethodDescriptor<ReqT, RespT> method) {
        var messages = this.sent.get(method.getFullMethodName());
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return (List<ReqT>) new ArrayList<>(messages);
        }
    }

    /**
     * Runs the hook from inside the runner's send, on the sending thread, before
     * that send returns.
     *
     * @param <ReqT>  request type of the method
     * @param <RespT> response type of the method
     * @param method  the method to hook into
     * @param hook    called with each message the runner sends
     */
    @SuppressWarnings("unchecked")
    <ReqT, RespT> void whileSending(MethodDescriptor<ReqT, RespT> method, Consumer<ReqT> hook) {
        this.hooks.put(method.getFullMethodName(), (Consumer<Object>) hook);
    }

    /**
     * Delivers a response to the runner, the way the gRPC machinery would.
     *
     * @param <ReqT>   request type of the method
     * @param <RespT>  response type of the method
     * @param method   the method to answer on
     * @param response the message the runner receives
     */
    @SuppressWarnings("unchecked")
    <ReqT, RespT> void respond(MethodDescriptor<ReqT, RespT> method, RespT response) {
        var call = (FakeCall<ReqT, RespT>) this.calls.get(method.getFullMethodName());
        if (call == null) {
            throw new IllegalStateException("no call was made on " + method.getFullMethodName());
        }
        call.respond(response);
    }

    private void record(String method, Object message) {
        this.sent.computeIfAbsent(method, _key -> Collections.synchronizedList(new ArrayList<>())).add(message);

        var hook = this.hooks.get(method);
        if (hook != null) {
            hook.accept(message);
        }
    }

    private final class FakeCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {
        private final String method;
        private volatile Listener<RespT> listener;

        private FakeCall(String method) {
            this.method = method;
        }

        void respond(RespT message) {
            var current = this.listener;
            if (current == null) {
                throw new IllegalStateException("call on " + this.method + " was never started");
            }
            current.onMessage(message);
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            this.listener = responseListener;
        }

        @Override
        public void request(int numMessages) {
            // no flow control, everything is delivered by hand
        }

        @Override
        public void cancel(String message, Throwable cause) {
            // nothing to cancel
        }

        @Override
        public void halfClose() {
            // nothing to close
        }

        @Override
        public void sendMessage(ReqT message) {
            FakeOrchestrator.this.record(this.method, message);
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }
}
