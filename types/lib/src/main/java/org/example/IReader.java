package org.example;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface IReader {
    String id();

    Iter<Iter<ByteString>> streams();

    Iter<ByteString> buffers();

    public static abstract class Iter<T> {
        protected List<Consumer<Optional<T>>> callbacks = new ArrayList<>();

        public void on(Consumer<Optional<T>> cb) {
            this.callbacks.add(cb);
        }
    }
}
