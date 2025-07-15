package org.example;

import java.util.Optional;
import java.util.function.Consumer;

import com.google.protobuf.ByteString;

/**
 * Writer
 */
public class Writer extends IWriter {
    private Runner runner;

    public Writer(String id, Runner runner) {
        super(id);
        this.runner = runner;
    }

    @Override
    public void msg(ByteString buffer) {
        this.runner.sendMessage(this.id, buffer);
    }

    @Override
    public Stream stream() {
        return new WriteStream(this.runner.sendStreamMessage(this.id));
    }

    @Override
    public void close() {
        this.runner.closeChannel(this.id);
    }

    private static class WriteStream implements Stream {
        private Consumer<Optional<ByteString>> consumer;

        WriteStream(Consumer<Optional<ByteString>> consumer) {
            this.consumer = consumer;
        }

        @Override
        public void push(ByteString chunk) {
            this.consumer.accept(Optional.of(chunk));
        }

        @Override
        public void close() {
            this.consumer.accept(Optional.empty());
        }
    }
}
