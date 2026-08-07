package io.github.rdfc.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The first line an orchestrator writes on a freshly accepted connection.
 *
 * The protocol is deliberately tiny: the orchestrator connects, writes
 * {@code <runnerIRI>\n} in UTF-8 with no framing around it, and then
 * <em>immediately</em> starts speaking HTTP/2 on the same socket as the gRPC
 * server. The two are not separated in time and usually not in space either —
 * the HTTP/2 client preface ({@code PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n} plus a
 * SETTINGS frame) very often lands in the same TCP segment as the IRI line.
 *
 * That is why this reads <b>raw bytes</b> and hands back whatever it read past
 * the newline. Everything after the {@code 0x0A} belongs to the HTTP/2
 * transport and has to reach it byte for byte: a {@code Reader}, a
 * {@code BufferedReader} or any other charset round-trip would either swallow
 * those bytes into a buffer nobody can reach anymore, or turn the ones that are
 * not valid text into replacement characters. Only the bytes <em>before</em>
 * the newline are ever decoded.
 *
 * The counterpart of that contract: after {@link #read} returns, the caller
 * must go on reading from <b>the same</b> {@link InputStream} — {@code
 * socket.getInputStream()} — and must not wrap it in anything that buffers,
 * for the same reason.
 */
public final class Handshake {
    private static final Logger LOGGER = Logger.getLogger(Handshake.class.getName());

    /**
     * The longest IRI line accepted, the newline itself not counted.
     *
     * The same limit the js- and py-runners use. It is there so a peer that
     * connects and streams without ever sending a newline cannot make this
     * runner buffer without bound.
     */
    static final int MAX_LINE = 1024;

    /** How long a connection may stay silent before it is dropped. */
    static final int TIMEOUT_MILLIS = 5000;

    /**
     * How much is read at a time.
     *
     * Bigger than the preface plus a SETTINGS frame, so in the usual case one
     * read call collects the line and everything the orchestrator already sent
     * after it.
     */
    private static final int CHUNK = 8192;

    private Handshake() {
    }

    /**
     * Reads the IRI line off a freshly accepted connection.
     *
     * The socket keeps a read timeout of {@link #TIMEOUT_MILLIS} for the
     * duration of the handshake and gets its previous one back afterwards — on
     * the way out through a failure as well, since the caller may want to keep
     * the socket around to report on it.
     *
     * @param socket the accepted orchestrator connection
     * @return the IRI and any bytes read past the newline
     * @throws HandshakeException when the peer sent no usable line: nothing at
     *                            all within the timeout, an end of stream
     *                            before the newline, more than
     *                            {@link #MAX_LINE} bytes without one, bytes
     *                            that are not UTF-8, or an empty line
     * @throws IOException        when the socket itself failed
     */
    public static Result read(Socket socket) throws HandshakeException, IOException {
        return read(socket, TIMEOUT_MILLIS);
    }

    /**
     * As {@link #read(Socket)}, with the timeout given.
     *
     * Exists for the tests, which would otherwise have to sit still for five
     * seconds to watch a silent peer be dropped.
     *
     * @param socket        the accepted orchestrator connection
     * @param timeoutMillis how long the peer may stay silent
     */
    static Result read(Socket socket, int timeoutMillis) throws HandshakeException, IOException {
        var previous = socket.getSoTimeout();
        socket.setSoTimeout(timeoutMillis);
        try {
            return scan(socket.getInputStream());
        } finally {
            restore(socket, previous);
        }
    }

    /**
     * Reads until the first {@code 0x0A}.
     *
     * The line is accumulated across as many reads as it takes; the tail of the
     * read that finally contained the newline is the remainder. Nothing is
     * pushed back and nothing is buffered inside this class — what is not
     * returned has not been read.
     */
    private static Result scan(InputStream in) throws HandshakeException, IOException {
        var line = new ByteArrayOutputStream();
        var chunk = new byte[CHUNK];

        while (true) {
            int read;
            try {
                read = in.read(chunk);
            } catch (SocketTimeoutException e) {
                throw new HandshakeException(HandshakeException.Reason.TIMEOUT,
                        "no runner IRI within the handshake timeout", e);
            }

            if (read < 0) {
                throw new HandshakeException(HandshakeException.Reason.EOF,
                        "the connection ended after " + line.size() + " bytes, before a newline");
            }

            var newline = indexOfNewline(chunk, read);
            if (newline < 0) {
                line.write(chunk, 0, read);
                checkLength(line.size());
                continue;
            }

            line.write(chunk, 0, newline);
            checkLength(line.size());

            return new Result(decode(line.toByteArray()), Arrays.copyOfRange(chunk, newline + 1, read));
        }
    }

    private static int indexOfNewline(byte[] buffer, int length) {
        for (var i = 0; i < length; i++) {
            if (buffer[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static void checkLength(int length) throws HandshakeException {
        if (length > MAX_LINE) {
            throw new HandshakeException(HandshakeException.Reason.TOO_LONG,
                    "the runner IRI is longer than " + MAX_LINE + " bytes, or no newline was sent");
        }
    }

    /**
     * Turns the bytes before the newline into the IRI.
     *
     * Strictly: a peer that is not sending UTF-8 is not the orchestrator, and a
     * lenient decode would answer it with a URI full of replacement characters
     * that identifies no runner anyone asked for. The trim takes care of the
     * carriage return of a CRLF line ending, and of stray padding.
     */
    private static String decode(byte[] bytes) throws HandshakeException {
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new HandshakeException(HandshakeException.Reason.INVALID_ENCODING,
                    "the runner IRI is not valid UTF-8", e);
        }

        var uri = decoded.trim();
        if (uri.isEmpty()) {
            throw new HandshakeException(HandshakeException.Reason.EMPTY, "the runner IRI is empty");
        }

        return uri;
    }

    /**
     * Puts the read timeout back.
     *
     * A socket that was closed under us cannot be configured anymore, and that
     * is not worth failing over — whatever closed it is the real story, and it
     * is either already on its way up or was the point of the call.
     */
    private static void restore(Socket socket, int timeout) {
        try {
            socket.setSoTimeout(timeout);
        } catch (SocketException e) {
            LOGGER.log(Level.FINE, "could not restore the read timeout on a handshake socket", e);
        }
    }

    /**
     * A completed handshake.
     *
     * {@link #remainder()} is <b>only what the handshake happened to over-read
     * </b>: the bytes that shared a read with the newline. It is routinely
     * empty — when the orchestrator's preface arrives in a later segment, those
     * bytes are simply still in the socket and the caller's pump will read them
     * itself. Nothing is lost either way; what matters is that these bytes go
     * into the transport <em>first</em> and unchanged.
     */
    public static final class Result {
        private final String uri;
        private final byte[] remainder;

        Result(String uri, byte[] remainder) {
            this.uri = uri;
            this.remainder = remainder;
        }

        /**
         * @return the runner IRI, trimmed. Never empty.
         */
        public String uri() {
            return this.uri;
        }

        /**
         * @return the bytes read past the newline, possibly empty. Not copied —
         *         this is on the hot path into {@link SocketBridge}, which does
         *         not modify it.
         */
        public byte[] remainder() {
            return this.remainder;
        }

        @Override
        public String toString() {
            return "Handshake.Result[uri=" + this.uri + ", remainder=" + this.remainder.length + " bytes]";
        }
    }
}
