package rdfc.test;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import io.github.rdfc.IReader;
import io.github.rdfc.IWriter;
import io.github.rdfc.Processor;

/**
 * Forwards every message it reads to a channel it writes, unchanged.
 *
 * The middle of the test pipelines: it makes a run exercise both directions of
 * the protocol — reading a message and acknowledging it, writing one and waiting
 * for the acknowledgement to come back.
 */
public class EchoProcessor extends Processor<EchoProcessor.Args> {

    /**
     * What the pipeline configures.
     *
     * The field names are the {@code sh:name}s of the processor's SHACL shape;
     * the runner deserializes the orchestrator's JSON straight onto them.
     */
    public static class Args {
        /** The channel messages arrive on. */
        public IReader reader;
        /** The channel they are forwarded to. */
        public IWriter writer;
    }

    /**
     * @param arguments the channel to read from and the one to write to
     * @param logger    the logger reporting to the orchestrator
     */
    public EchoProcessor(Args arguments, Logger logger) {
        super(arguments, logger);
    }

    @Override
    public CompletableFuture<?> init() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> transform() {
        // Declared rather than written inline: the returned future is what makes the
        // runner hold the incoming message until it has been forwarded, and a lambda
        // that resolved to the Consumer overload instead would silently drop that
        // backpressure.
        Function<ByteString, CompletableFuture<?>> forward = buffer -> {
            this.logger.info("Echoing message: " + buffer.toStringUtf8());
            return this.arguments.writer.chunk(buffer);
        };

        return this.arguments.reader.buffers().on(forward)
                // The incoming channel closed, so nothing more will be forwarded and
                // the outgoing one can close too
                .thenCompose(end -> this.arguments.writer.close());
    }

    @Override
    public CompletableFuture<?> produce() {
        // Nothing to produce on its own: everything this writes is a reaction
        return CompletableFuture.completedFuture(null);
    }
}
