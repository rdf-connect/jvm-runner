package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Stream
 */
public class Iter<T> {
    private List<Consumer<Optional<T>>> callbacks = new ArrayList<>();

    void on(Consumer<Optional<T>> cb) {
        this.callbacks.add(cb);
    }

    void push(T item) {
        this.callbacks.forEach(cb -> cb.accept(Optional.of(item)));
    }

    void end() {
        this.callbacks.forEach(cb -> cb.accept(Optional.empty()));
    }

    public static class SingleIter<T> extends Iter<T> {
        private T item;

        public SingleIter(T item) {
            this.item = item;
        }

        @Override
        void on(Consumer<Optional<T>> cb) {
            cb.accept(Optional.of(this.item));
            cb.accept(Optional.empty());
        }
    }
}
