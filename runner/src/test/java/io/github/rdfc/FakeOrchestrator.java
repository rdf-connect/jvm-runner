package io.github.rdfc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
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
    /**
     * Incoming messages are delivered here and never on a sending thread, the way
     * a real transport does it.
     */
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "fake-orchestrator-delivery");
        thread.setDaemon(true);
        return thread;
    });

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
     * Delivers a response to the runner on the delivery thread and waits for it to
     * be handled.
     *
     * This is how a real transport behaves: incoming messages arrive on the
     * transport's own thread, never on the thread that is sending. Use this from
     * the test thread, outside of a {@link #whileSending} hook.
     *
     * @param <ReqT>   request type of the method
     * @param <RespT>  response type of the method
     * @param method   the method to answer on
     * @param response the message the runner receives
     */
    <ReqT, RespT> void respond(MethodDescriptor<ReqT, RespT> method, RespT response) {
        var delivered = this.respondLater(method, response);
        try {
            delivered.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("delivering on " + method.getFullMethodName() + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("delivering on " + method.getFullMethodName() + " timed out", e);
        }
    }

    /**
     * Hands the response to the delivery thread and returns right away.
     *
     * This is what a {@link #whileSending} hook has to use: the hook runs while the
     * runner holds the lock serializing that stream, so it may not block waiting
     * for a delivery that might want the very same lock.
     *
     * @param <ReqT>   request type of the method
     * @param <RespT>  response type of the method
     * @param method   the method to answer on
     * @param response the message the runner receives
     * @return completes once the runner handled the response
     */
    <ReqT, RespT> Future<?> respondLater(MethodDescriptor<ReqT, RespT> method, RespT response) {
        return this.delivery.submit(() -> this.respondOnThisThread(method, response));
    }

    /**
     * Delivers a response on the calling thread, so from inside a
     * {@link #whileSending} hook it lands before the send even returned.
     *
     * That is what a transport with a direct executor would do. No real transport
     * this runner uses behaves like this — it exists to pin down the
     * acknowledgement races, where the fix is exactly that the runner has to be
     * ready for the answer before it sends.
     *
     * @param <ReqT>   request type of the method
     * @param <RespT>  response type of the method
     * @param method   the method to answer on
     * @param response the message the runner receives
     */
    @SuppressWarnings("unchecked")
    <ReqT, RespT> void respondOnThisThread(MethodDescriptor<ReqT, RespT> method, RespT response) {
        var call = (FakeCall<ReqT, RespT>) this.calls.get(method.getFullMethodName());
        if (call == null) {
            throw new IllegalStateException("no call was made on " + method.getFullMethodName());
        }
        call.respond(response);
    }

    /**
     * Breaks the call on this method, the way a connection that drops does.
     *
     * The runner's observer for that method sees an onError. Delivered on the
     * delivery thread, so it lands where a real transport would put it.
     *
     * @param <ReqT>      request type of the method
     * @param <RespT>     response type of the method
     * @param method      the method whose call breaks
     * @param description what went wrong, shows up in the status
     */
    <ReqT, RespT> void fail(MethodDescriptor<ReqT, RespT> method, String description) {
        this.close(method, Status.UNAVAILABLE.withDescription(description));
    }

    /**
     * Closes the call on this method the orderly way: the runner's observer for it
     * sees an onCompleted.
     *
     * @param <ReqT>  request type of the method
     * @param <RespT> response type of the method
     * @param method  the method whose call is closed
     */
    <ReqT, RespT> void complete(MethodDescriptor<ReqT, RespT> method) {
        this.close(method, Status.OK);
    }

    private <ReqT, RespT> void close(MethodDescriptor<ReqT, RespT> method, Status status) {
        this.onDeliveryThread(method, () -> {
            var call = this.calls.get(method.getFullMethodName());
            if (call == null) {
                throw new IllegalStateException("no call was made on " + method.getFullMethodName());
            }
            call.close(status);
        });
    }

    /**
     * Runs the action on the delivery thread and waits for it, turning whatever it
     * threw into a failure of this test rather than of a background thread.
     */
    private void onDeliveryThread(MethodDescriptor<?, ?> method, Runnable action) {
        try {
            this.delivery.submit(action).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("delivering on " + method.getFullMethodName() + " failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("delivering on " + method.getFullMethodName() + " timed out", e);
        }
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
            this.listener().onMessage(message);
        }

        void close(Status status) {
            this.listener().onClose(status, new Metadata());
        }

        private Listener<RespT> listener() {
            var current = this.listener;
            if (current == null) {
                throw new IllegalStateException("call on " + this.method + " was never started");
            }
            return current;
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
