package io.github.rdfc.server;

/**
 * A listener could not be opened, e.g. because its port is already in use.
 *
 * Thrown instead of letting a raw {@link java.io.IOException} out, so the
 * entrypoint can print one actionable line — which port, which configuration
 * property to change — and exit, rather than a stack trace of a socket bind that
 * says nothing the operator can act on.
 */
public class ServerStartupException extends Exception {
    private static final long serialVersionUID = 1L;

    public ServerStartupException(String message) {
        super(message);
    }

    public ServerStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
