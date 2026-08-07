package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import io.github.rdfc.helpers.Errors;

/**
 * The orchestrator shows the string in an acknowledgement to a user, so it has
 * to be the sentence that says what went wrong — not the two layers of future
 * plumbing that happen to sit around it.
 */
class ErrorsTest {
    @Test
    void theWrappersAroundAFailureArePeeledOff() {
        var root = new IllegalStateException("the file was not there");

        assertEquals("the file was not there", Errors.describe(root));
        assertEquals("the file was not there", Errors.describe(new CompletionException(root)));
        assertEquals("the file was not there", Errors.describe(new ExecutionException(root)));
        assertEquals("the file was not there",
                Errors.describe(new CompletionException(new ExecutionException(new CompletionException(root)))));
    }

    /** Anything that is not future plumbing was wrapped on purpose. */
    @Test
    void aDeliberateWrappingIsKept() {
        var wrapped = new IllegalStateException("could not start the processor", new RuntimeException("no such class"));

        assertEquals("could not start the processor", Errors.describe(wrapped));
    }

    @Test
    void anExceptionWithoutAMessageFallsBackOnItsType() {
        assertEquals("java.lang.NullPointerException", Errors.describe(new CompletionException(
                new NullPointerException())));
    }

    /** A wrapper without a cause is all there is, so it is what gets reported. */
    @Test
    void aWrapperWithoutACauseIsKept() {
        var lonely = new CompletionException("nothing underneath", null);

        assertSame(lonely, Errors.unwrap(lonely));
        assertEquals("nothing underneath", Errors.describe(lonely));
    }

    @Test
    void nothingInIsNothingOut() {
        assertNull(Errors.unwrap(null));
        assertNull(Errors.describe(null));
    }
}
