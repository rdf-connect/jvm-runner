package io.github.rdfc;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Data is received from a channel using an IReader.
 * Either consume the data as Streams, Buffers or Strings.
 * When a message comes in that is not the expected type, the IReader will
 * upcast the message to the expected type.
 */
public interface IReader {
    /**
     * @return the URI of the channel
     */
    String id();

    /**
     * Consume the Reader as an Iter of Iters
     * 
     * @return an iterator of ByteString iterators
     */
    Iter<Iter<ByteString>> streams();

    /**
     * Consume the Reader as an Iter of buffers
     * 
     * @return an iterator of ByteStrings
     */
    Iter<ByteString> buffers();

    Iter<String> strings();

    /**
     * This remembers consuming callbacks for each message `T`.
     * Each callback is a CompletableFuture, each T is only handled
     * This is the reading side of the stream, so functions that push data into it
     * are missing.
     */
    public static abstract class Iter<T> {
        protected List<Function<T, CompletableFuture<?>>> callbacks = new ArrayList<>();
        /**
         * endFuture is a CompletableFuture that resolves when the corresponding stream
         * closes.
         */
        protected CompletableFuture<Void> endFuture = new CompletableFuture<>();

        public <B> Iter<B> transform(Function<T, B> apply) {
            return new Iter<B>() {
                public CompletableFuture<Void> on(Function<B, CompletableFuture<?>> f) {
                    Iter.this.callbacks.add(apply.andThen(f));
                    return endFuture;
                }
            };
        }

        /**
         * @param apply called for each incoming piece of data
         * @return a CompletableFuture resolves when the stream is closed.
         */
        public CompletableFuture<Void> on(Consumer<T> apply) {
            return this.on((chunk) -> {
                // Accept the value
                apply.accept(chunk);
                // Nothing to await for this chunk
                return CompletableFuture.completedFuture(null);
            });
        }

        /**
         * @param apply called for each incoming piece of data, the returned
         *              CompletableFuture is awaited before receiving another chunk
         *              of data.
         * @return a CompletableFuture resolves when the stream is closed.
         */
        public CompletableFuture<Void> on(Function<T, CompletableFuture<?>> apply) {
            this.callbacks.add(apply);
            return endFuture;
        }
    }
}
