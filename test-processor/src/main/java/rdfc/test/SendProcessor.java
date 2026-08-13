package rdfc.test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import io.github.rdfc.IWriter;
import io.github.rdfc.Processor;

/**
 * Writes the messages it was configured with to a channel and closes it.
 *
 * The head of the test pipelines: nothing arrives here, so the whole run is
 * driven by what this processor produces.
 */
public class SendProcessor extends Processor<SendProcessor.Args> {

    /**
     * What the pipeline configures.
     *
     * The field names are the {@code sh:name}s of the processor's SHACL shape;
     * the runner deserializes the orchestrator's JSON straight onto them.
     */
    public static class Args {
        /** The messages to send, in order. */
        public List<String> msg;
        /** The channel to send them on. */
        public IWriter writer;
    }

    /**
     * @param arguments the configured messages and the channel to write them to
     * @param logger    the logger reporting to the orchestrator
     */
    public SendProcessor(Args arguments, Logger logger) {
        super(arguments, logger);
    }

    @Override
    public CompletableFuture<?> init() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> transform() {
        // Nothing to read: this processor is a source
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> produce() {
        List<String> messages = this.arguments.msg != null ? this.arguments.msg : Collections.emptyList();

        // chunks() pulls one message, awaits its acknowledgement and only then asks
        // this iterator for the next one, so the messages are sent in order and the
        // log line below is written when the message actually goes out — not all of
        // them up front.
        Iterator<String> texts = messages.iterator();
        Iterator<ByteString> chunks = new Iterator<ByteString>() {
            @Override
            public boolean hasNext() {
                return texts.hasNext();
            }

            @Override
            public ByteString next() {
                String text = texts.next();
                SendProcessor.this.logger.info("Sending message: " + text);
                return ByteString.copyFromUtf8(text);
            }
        };

        return this.arguments.writer.chunks(chunks)
                // Closing tells whoever reads this channel that no more is coming,
                // which is what ends the pipeline
                .thenCompose(sent -> this.arguments.writer.close());
    }
}
