package io.github.rdfc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import rdfc.RunnerGrpc;

/**
 * A Runner that takes its processors from the test instead of loading them out
 * of a jar, so the lifecycle can be exercised without any classloading.
 */
class TestRunner extends Runner {
    /** How often the runner decided it was completely finished. */
    final AtomicInteger completions;

    private final Map<String, StubProcessor> stubs = new ConcurrentHashMap<>();

    private TestRunner(RunnerGrpc.RunnerStub stub, String uri, AtomicInteger completions) {
        super(stub, uri, completions::incrementAndGet);
        this.completions = completions;
    }

    static TestRunner create(FakeOrchestrator orchestrator, String uri) {
        return new TestRunner(FakeOrchestrator.stub(orchestrator), uri, new AtomicInteger());
    }

    /**
     * Registers the processor the runner hands back when the orchestrator sends a
     * processor with this URI.
     *
     * @param uri       of the processor
     * @param processor to use for it
     * @return the very same processor
     */
    StubProcessor register(String uri, StubProcessor processor) {
        this.stubs.put(uri, processor);
        return processor;
    }

    @Override
    protected Processor<?> startProc(rdfc.Service.Processor proc, Logger logger) {
        var processor = this.stubs.get(proc.getUri());
        if (processor == null) {
            throw new IllegalStateException("no processor registered for " + proc.getUri());
        }

        this.processors.put(proc.getUri(), processor);
        return processor;
    }
}
