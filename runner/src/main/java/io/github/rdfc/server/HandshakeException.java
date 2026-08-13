package io.github.rdfc.server;

/**
 * A connection that did not open with a usable runner IRI.
 *
 * Checked, because this is an ordinary event on a public port — a health
 * checker, a port scanner, a browser — and the caller is expected to log it and
 * close the socket, not to fall over.
 */
public class HandshakeException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Why the handshake was rejected. */
    public enum Reason {
        /** The peer sent nothing at all within the timeout. */
        TIMEOUT,
        /** The connection ended before a newline arrived. */
        EOF,
        /** More than {@link Handshake#MAX_LINE} bytes without a newline. */
        TOO_LONG,
        /** The bytes before the newline are not valid UTF-8. */
        INVALID_ENCODING,
        /** The line held nothing but whitespace. */
        EMPTY,
    }

    private final Reason reason;

    HandshakeException(Reason reason, String message) {
        this(reason, message, null);
    }

    HandshakeException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * @return why the handshake was rejected, for a caller that wants to branch
     *         on it
     */
    public Reason reason() {
        return this.reason;
    }
}
