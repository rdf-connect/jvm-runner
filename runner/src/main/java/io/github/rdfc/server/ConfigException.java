package io.github.rdfc.server;

/**
 * The server configuration file is missing or invalid.
 *
 * A checked exception on purpose: every caller of
 * {@link ServerConfig#parse(java.nio.file.Path)} is an entrypoint that has to
 * turn this into one actionable line and an exit code, not a stack trace. The
 * message says what is wrong and where, so it can be printed as it is.
 */
public class ConfigException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
