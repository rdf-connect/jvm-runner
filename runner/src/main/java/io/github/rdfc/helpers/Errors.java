package io.github.rdfc.helpers;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Turning a failure into the sentence that goes out over the wire.
 *
 * Everything in this runner is chained on CompletableFutures, so by the time a
 * failure reaches the code that reports it, it is usually wrapped in one or more
 * {@link CompletionException}s. Reporting that wrapper hands the orchestrator
 * "java.util.concurrent.CompletionException: java.lang.RuntimeException: ..."
 * instead of the one line that says what actually went wrong, so the wrappers
 * are peeled off first.
 */
public final class Errors {

    private Errors() {
        // static helpers only
    }

    /**
     * Peels the future plumbing off a failure.
     *
     * Only the two exceptions that the concurrency library adds by itself are
     * unwrapped: anything else is a cause somebody chose to wrap on purpose, and
     * that wrapping carries information.
     *
     * @param error the failure to unwrap, may be null
     * @return the outermost exception that is not a plain wrapper, or null when
     *         null went in
     */
    public static Throwable unwrap(Throwable error) {
        var current = error;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            var cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }

    /**
     * The one-line description of a failure, as the orchestrator gets to see it.
     *
     * The type is named in front of the message, because for the failures this
     * runner reports most often the type <em>is</em> the payload: a
     * {@code ClassNotFoundException} carries the class name and nothing else, a
     * {@code NoSuchFileException} a bare path, a {@code FileNotFoundException} a
     * bare URL. Handing a user "rdfc.test.Echo" says nothing at all; handing them
     * "ClassNotFoundException: rdfc.test.Echo" says what to fix.
     *
     * The simple name, not the qualified one: the package of an exception is
     * noise in a message an orchestrator puts in front of somebody.
     *
     * @param error the failure to describe, may be null
     * @return the root cause's type and message, its type alone when it carries no
     *         message, or null when null went in
     */
    public static String describe(Throwable error) {
        var root = unwrap(error);
        if (root == null) {
            return null;
        }

        // Empty for an anonymous class, and then there is no name to report
        var type = root.getClass().getSimpleName();
        if (type.isEmpty()) {
            return root.toString();
        }

        // Some exceptions carry no message, then the type is the whole story
        var message = root.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
