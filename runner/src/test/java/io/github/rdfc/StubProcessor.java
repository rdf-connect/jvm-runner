package io.github.rdfc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A processor whose three phases are completed by the test, so the test decides
 * exactly when init finishes and can look at what already ran.
 */
class StubProcessor extends Processor<Object> {
    final CompletableFuture<Object> init = new CompletableFuture<>();
    final CompletableFuture<Object> transform = new CompletableFuture<>();
    final CompletableFuture<Object> produce = new CompletableFuture<>();

    final AtomicInteger initCalls = new AtomicInteger();
    final AtomicInteger transformCalls = new AtomicInteger();
    final AtomicInteger produceCalls = new AtomicInteger();

    /** When set, transform() throws this instead of returning its future. */
    volatile RuntimeException transformThrows;
    /** When set, transform() returns null instead of its future. */
    volatile boolean transformReturnsNull;

    StubProcessor() {
        super(new Object(), Logger.getLogger(StubProcessor.class.getName()));
    }

    @Override
    public CompletableFuture<?> init() {
        this.initCalls.incrementAndGet();
        return this.init;
    }

    @Override
    public CompletableFuture<?> transform() {
        this.transformCalls.incrementAndGet();

        if (this.transformThrows != null) {
            throw this.transformThrows;
        }

        return this.transformReturnsNull ? null : this.transform;
    }

    @Override
    public CompletableFuture<?> produce() {
        this.produceCalls.incrementAndGet();
        return this.produce;
    }
}
