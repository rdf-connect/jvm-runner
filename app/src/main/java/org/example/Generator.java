
package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.example.Reader.StreamIter;

import com.google.protobuf.ByteString;

import io.grpc.stub.StreamObserver;
import rdfc.Common.DataChunk;

public abstract class Generator implements StreamObserver<DataChunk> {
    abstract void push(ByteString item);

    abstract void end();

    @Override
    public void onNext(DataChunk value) {
        this.push(value.getData());
    }

    @Override
    public void onError(Throwable t) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onError'");
    }

    @Override
    public void onCompleted() {
        this.end();
    }

    public static class StreamsGenerator extends Generator {
        private List<StreamIter<ByteString>> streams;
        private ByteString st;
        Consumer<ByteString> onEnd;

        public StreamsGenerator(List<StreamIter<ByteString>> streams, Consumer<ByteString> onEnd) {
            this.streams = streams;
            this.st = ByteString.EMPTY;
            this.onEnd = onEnd;
        }

        @Override
        void push(ByteString item) {
            this.streams.forEach(stream -> stream.push(item));
            this.st = this.st.concat(item);
        }

        @Override
        void end() {
            this.streams.forEach(stream -> stream.end());
            this.onEnd.accept(this.st);
        }
    }
}
