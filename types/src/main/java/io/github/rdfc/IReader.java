package io.github.rdfc;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public interface IReader {
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

    public static abstract class Iter<T> {
        protected List<Function<T, CompletableFuture<?>>> callbacks = new ArrayList<>();
        protected CompletableFuture<Void> endFuture = new CompletableFuture<>();

        /**
         * @param apply called for each incoming piece of data
         * @return a CompletableFuture resolves when the stream is closed.
         */
        public CompletableFuture<Void> on(Consumer<T> apply) {
            return this.on((value) -> {
                apply.accept(value);
                return endFuture;
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
