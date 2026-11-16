package rdfc.test;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import io.github.rdfc.IReader;
import io.github.rdfc.Processor;

/**
 * Logs every message it reads.
 *
 * The tail of the test pipelines: its log lines are what a test asserts on, so
 * they are the evidence that a message travelled the whole chain and came out
 * the other end intact.
 */
public class LogProcessor extends Processor<LogProcessor.Args> {

    /**
     * What the pipeline configures.
     *
     * The field name is the {@code sh:name} of the processor's SHACL shape; the
     * runner deserializes the orchestrator's JSON straight onto it.
     */
    public static class Args {
        /** The channel messages arrive on. */
        public IReader reader;
    }

    /**
     * @param arguments the channel to read from
     * @param logger    the logger reporting to the orchestrator
     */
    public LogProcessor(Args arguments, Logger logger) {
        super(arguments, logger);
    }

    @Override
    public CompletableFuture<?> init() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> transform() {
        // strings() rather than buffers(): the payloads here are text, and this is
        // the one place in the test bed that reads a channel as text at all.
        return this.arguments.reader.strings().on((String message) -> {
            this.logger.info("Received message: " + message);
        });
    }

    @Override
    public CompletableFuture<?> produce() {
        // Nothing to produce: this processor is a sink
        return CompletableFuture.completedFuture(null);
    }
}
