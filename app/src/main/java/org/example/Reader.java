package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
    void msg(ByteString buffer) {
        this.strings.forEach(string -> string.push(buffer));
        this.streams.forEach(stream -> stream.push(
                new Reader.SingleIter<>(buffer)));
    }

    // The Runner receives a stream message event, and creates create a stream
    // message
    // For each listener, create a new stream and push it to that listener.
    // Return a generator that for each incoming message for that stream, pushes it
    // to all just created streams.
    Generator stream() {
        var listeningStreams = new ArrayList<StreamIter<ByteString>>(this.streams.size());
        this.streams.forEach(stream -> {
            var iter = new StreamIter<ByteString>();
            stream.push(iter);
            listeningStreams.add(iter);
        });

        // When all data is received from this stream message, send that full string to
        // all simple string listeners
        return new Generator.StreamsGenerator(listeningStreams, st -> {
            this.strings.forEach(stream -> stream.push(st));
        });
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
        public void on(Consumer<Optional<T>> cb) {
            cb.accept(Optional.of(this.item));
            cb.accept(Optional.empty());
        }
    }

    static class StreamIter<T> extends Iter<T> {
        void push(T item) {
            this.callbacks.forEach(cb -> cb.accept(Optional.of(item)));
        }

        void end() {
            this.callbacks.forEach(cb -> cb.accept(Optional.empty()));
        }
    }
}
