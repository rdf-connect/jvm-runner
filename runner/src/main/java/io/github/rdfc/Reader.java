package io.github.rdfc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import java.util.logging.*;
import com.google.protobuf.ByteString;

/**
 * Channel
 */
public class Reader implements IReader {
    private final String id;
    private final List<StreamIter<Iter<ByteString>>> streams = new ArrayList<>();
    private final List<StreamIter<ByteString>> strings = new ArrayList<>();
    private final Logger logger;

    public Reader(String id, Logger logger) {
        this.id = id;
        this.logger = logger;
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public Iter<Iter<ByteString>> streams() {
        var out = new StreamIter<Iter<ByteString>>();
        this.streams.add(out);
        return out;
    }

    @Override
    public Iter<ByteString> buffers() {
        var out = new StreamIter<ByteString>();
        this.strings.add(out);
        return out;
    }

    @Override
    public Iter<String> strings() {
        return this.buffers().transform(ByteString::toString);
    }

    /**
     * The runner receives a single byestring message.
     * 
     * @param buffer the received message
     * @return a future that completes when all consumers have handled the message
     */
    CompletableFuture<Void> msg(ByteString buffer) {
        var stringFutures = this.strings.stream()
                .map(string -> string.push(buffer));

        var streamFutures = this.streams.stream()
                .map(string -> string.push(new Reader.SingleIter<>(buffer)));

        var futures = java.util.stream.Stream.concat(stringFutures, streamFutures)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);

    }

    /**
     * The Runner receives a stream message event, and creates a consuming stream
     * message
     * 
     * @param betweenChunks Consumer called after receiving a chunk and when all
     *                      consumers have handled that chunk
     * @return a Stream that for each incoming message for that stream, pushes it to
     *         all just created streams.
     */
    public Stream<ByteString> stream(Runnable betweenChunks) {
        List<StreamIter<ByteString>> streams = this.streams.stream().map(st -> {
            var consumingStream = new StreamIter<ByteString>();
            st.push(consumingStream);
            return consumingStream;
        }).collect(Collectors.toList());

        return new StreamExtension(betweenChunks, streams, this.logger);
    }

    // The runner receives a close, all listeners should close
    void close() {
        this.strings.forEach(StreamIter::end);
        this.streams.forEach(StreamIter::end);
    }

    /**
     * Helper class that receives chunks from a stream message.
     * Each chunk has three parts:
     * - concat the chunk if there are string listeners
     * - for each consumer for the stream message, let them consume the chunk
     * - do an 'betweenChunks' operation, here sending a SendingStreamControl
     *
     * When the stream message is finished, let the string listeners consumer the
     * full string.
     */
    private final class StreamExtension extends Stream<ByteString> {
        private final Runnable betweenChunks;
        private final Logger logger;
        private final List<StreamIter<ByteString>> streams;

        ByteString completeString = ByteString.empty();

        private StreamExtension(Runnable betweenChunks, List<StreamIter<ByteString>> streams, Logger logger) {
            this.betweenChunks = betweenChunks;
            this.streams = streams;
            this.logger = logger;
        }

        @Override
        public CompletableFuture<Void> chunk(ByteString chunk) {
            if (!strings.isEmpty()) {
                this.completeString = this.completeString.concat(chunk);
            }

            this.logger.finest("Received stream chunk");
            var streamFutures = this.streams.stream()
                    .map(st -> st.push(chunk))
                    .toArray(CompletableFuture[]::new);

            return CompletableFuture.allOf(streamFutures)
                    .thenAccept(_void -> {
                        this.logger.finest("Stream chunk handled");
                        betweenChunks.run();
                    });
        }

        @Override
        public CompletableFuture<Void> close() {
            for (var st : this.streams) {
                st.end();
            }

            this.logger.finest("All stream chunks received, notifying the buffer handlers");
            var stringFutures = Reader.this.strings.stream()
                    .map(string -> string.push(this.completeString))
                    .toArray(CompletableFuture[]::new);

            return CompletableFuture.allOf(stringFutures);
        }
    }

    private static class SingleIter<T> extends Iter<T> {
        private T item;

        SingleIter(T item) {
            this.item = item;
        }

        @Override
        public CompletableFuture<Void> on(Function<T, CompletableFuture<?>> apply) {
            return apply.apply(this.item).thenApply(x -> null);
        }
    }

    static class StreamIter<T> extends Iter<T> {
        CompletableFuture<Void> push(T item) {
            var futures = this.callbacks.stream()
                    .map(cb -> cb.apply(item))
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures);
        }

        void end() {
            this.endFuture.complete(null);
        }
    }
}
