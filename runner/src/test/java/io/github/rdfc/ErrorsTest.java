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
 * plumbing that happen to sit around it, and not a message with the one word
 * that gives it meaning left off.
 */
class ErrorsTest {
    @Test
    void theWrappersAroundAFailureArePeeledOff() {
        var root = new IllegalStateException("the file was not there");
        var described = "IllegalStateException: the file was not there";

        assertEquals(described, Errors.describe(root));
        assertEquals(described, Errors.describe(new CompletionException(root)));
        assertEquals(described, Errors.describe(new ExecutionException(root)));
        assertEquals(described,
                Errors.describe(new CompletionException(new ExecutionException(new CompletionException(root)))));
    }

    /** Anything that is not future plumbing was wrapped on purpose. */
    @Test
    void aDeliberateWrappingIsKept() {
        var wrapped = new IllegalStateException("could not start the processor", new RuntimeException("no such class"));

        assertEquals("IllegalStateException: could not start the processor", Errors.describe(wrapped));
    }

    /**
     * The commonest startup failures carry nothing but a name, and which name it
     * is is the entire question: a class that is not in the jar, a file that is
     * not on disk and a URL that answered 404 otherwise all read as one bare
     * string.
     */
    @Test
    void theTypeIsNamedInFrontOfTheMessage() {
        assertEquals("ClassNotFoundException: rdfc.test.Echo",
                Errors.describe(new CompletionException(new ClassNotFoundException("rdfc.test.Echo"))));
        assertEquals("NoSuchFileException: /srv/conf/echo.ttl",
                Errors.describe(new java.nio.file.NoSuchFileException("/srv/conf/echo.ttl")));
        assertEquals("FileNotFoundException: http://localhost:3000/echo.jar",
                Errors.describe(new java.io.FileNotFoundException("http://localhost:3000/echo.jar")));
    }

    @Test
    void anExceptionWithoutAMessageFallsBackOnItsType() {
        assertEquals("NullPointerException", Errors.describe(new CompletionException(
                new NullPointerException())));
    }

    /** A message of nothing but spaces says as little as none at all. */
    @Test
    void aBlankMessageFallsBackOnTheTypeToo() {
        assertEquals("IllegalStateException", Errors.describe(new IllegalStateException("   ")));
    }

    /** An anonymous class has no name to report, so the whole toString is. */
    @Test
    void anAnonymousExceptionIsDescribedInFull() {
        var anonymous = new RuntimeException("went wrong") {
            private static final long serialVersionUID = 1L;
        };

        assertEquals(anonymous.toString(), Errors.describe(anonymous));
    }

    /** A wrapper without a cause is all there is, so it is what gets reported. */
    @Test
    void aWrapperWithoutACauseIsKept() {
        var lonely = new CompletionException("nothing underneath", null);

        assertSame(lonely, Errors.unwrap(lonely));
        assertEquals("CompletionException: nothing underneath", Errors.describe(lonely));
    }

    @Test
    void nothingInIsNothingOut() {
        assertNull(Errors.unwrap(null));
        assertNull(Errors.describe(null));
    }
}
