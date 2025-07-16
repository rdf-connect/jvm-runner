package org.example;

import java.util.function.Consumer;

/**
 * Processor
 * A processor is finished when the transform and produce callbacks are called
 */
public abstract class Processor<T> {
    protected T arguments;

    public Processor(T arguments) {
        this.arguments = arguments;
    }

    // This is called and awaits the callback before transform
    public abstract void init(Consumer<Void> callback);

    // Transofrm is called before produce, but does not await the callback
    public abstract void transform(Consumer<Void> callback);

    // Produce is called when all processors are constucted
    public abstract void produce(Consumer<Void> callback);
}
