package io.github.rdfc;

import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Processor
 * A processor is finished when the transform and produce callbacks are called
 */
public abstract class Processor<T> {
    protected final T arguments;
    protected final Logger logger;

    public Processor(T arguments, Logger logger) {
        this.arguments = arguments;
        this.logger = logger;
    }

    // This is called and awaits the callback before transform
    public abstract void init(Consumer<Void> callback);

    // Transofrm is called before produce, but does not await the callback
    public abstract void transform(Consumer<Void> callback);

    // Produce is called when all processors are constucted
    public abstract void produce(Consumer<Void> callback);
}
