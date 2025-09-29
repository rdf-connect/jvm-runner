package io.github.rdfc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;

/**
 * Channel
 */
public class Reader implements IReader {
    private String id;

    private List<StreamIter<Iter<ByteString>>> streams = new ArrayList<>();
    private List<StreamIter<ByteString>> strings = new ArrayList<>();

    public Reader(String id) {
        this.id = id;
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

    // The runner receives a single byestring message.
    // Each listener expecting a stream, receives a stream with a single chunk
    // Each listener expecting a single message, gets that message
    CompletableFuture<Void> msg(ByteString buffer) {
        var stringFutures = this.strings.stream()
                .map(string -> string.push(buffer));

        var streamFutures = this.streams.stream()
                .map(string -> string.push(new Reader.SingleIter<>(buffer)));

        var futures = java.util.stream.Stream.concat(stringFutures, streamFutures)
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

    }

    // The Runner receives a stream message event, and creates create a stream
    // message
    // For each listener, create a new stream and push it to that listener.
    // Return a generator that for each incoming message for that stream, pushes it
    // to all just created streams.
    public Stream<ByteString> stream(Consumer<Void> betweenChunks) {
        return new Stream<ByteString>() {
            ByteString completeString = ByteString.empty();

            List<StreamIter<ByteString>> streams = Reader.this.streams.stream().map(st -> {
                var consumingStream = new StreamIter<ByteString>();
                st.push(consumingStream);
                return consumingStream;
            }).collect(Collectors.toList());

            @Override
            public CompletableFuture<Void> chunk(ByteString chunk) {
                if (!strings.isEmpty()) {
                    this.completeString = this.completeString.concat(chunk);
                }

                List<CompletableFuture<Void>> streamFutures = this.streams.stream()
                        .map(st -> st.push(chunk))
                        .collect(Collectors.toList());

                return CompletableFuture.allOf(streamFutures.toArray(new CompletableFuture[0]))
                        .thenAccept(betweenChunks);
            }

            @Override
            public CompletableFuture<Void> close() {
                for (var st : this.streams) {
                    st.end();
                }

                var stringFutures = Reader.this.strings.stream()
                        .map(string -> string.push(this.completeString)).collect(Collectors.toList());

                return CompletableFuture.allOf(stringFutures.toArray(new CompletableFuture[0]));
            }
        };
    }

    // The runner receives a close, all listeners should close
    void close() {
        this.strings.forEach(string -> string.end());
        this.streams.forEach(stream -> stream.end());
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
            List<CompletableFuture<?>> futures = this.callbacks.stream()
                    .map(cb -> cb.apply(item).thenApply(x -> null)) // invoke each callback
                    .collect(Collectors.toList());
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }

        void end() {
            this.endFuture.complete(null);
        }
    }
}
