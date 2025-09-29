package io.github.rdfc;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

public abstract class Stream<T> {
    /**
     * Pushes a single chunk to the stream.
     * 
     * @param chunk chunk to send on the channel
     * @return future that resolves when the chunk has been handled
     */
    public abstract CompletableFuture<Void> chunk(T chunk);

    /**
     * Pushes a multiple chunks to the stream.
     * 
     * @param buffers for each chunk in chunks, send it on the channel
     * @return future that resolves when the chunks have been handled
     */
    public CompletableFuture<Void> chunks(Iterator<T> buffers) {
        if (!buffers.hasNext()) {
            return CompletableFuture.completedFuture(null); // base case: no more messages
        }

        T buffer = buffers.next();
        // process current buffer, then recursively process the rest
        return this.chunk(buffer).thenCompose(ignored -> this.chunks(buffers));
    }

    /**
     * Closes the stream
     * 
     * @return a future that completes when the stream is closed
     */
    public abstract CompletableFuture<Void> close();
}
