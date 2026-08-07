package io.github.rdfc.server;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

/**
 * The first line of a remote connection, over real sockets.
 *
 * Real ones on purpose: what is being tested is where the bytes end up when a
 * TCP segment does not line up with the protocol — which is exactly what a mock
 * stream would have to pretend about.
 */
@Timeout(30)
class HandshakeTest {
    private static final String URI = "urn:test:runner";

    /** What the orchestrator starts sending the moment the line is out. */
    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(US_ASCII);

    private ServerSocket listener;

    /** The orchestrator's end. */
    private Socket client;

    /** The end this runner accepted and hands to {@link Handshake}. */
    private Socket server;

    @BeforeEach
    void connect() throws IOException {
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.client = new Socket(InetAddress.getLoopbackAddress(), this.listener.getLocalPort());
        this.server = this.listener.accept();
    }

    @AfterEach
    void disconnect() {
        for (var closeable : new AutoCloseable[] { this.client, this.server, this.listener }) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // a test may well have closed these already
            }
        }
    }

    /** Every byte value, so nothing can be blamed on a friendly payload. */
    private static byte[] sweep() {
        var bytes = new byte[256];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private void send(byte[] bytes) throws IOException {
        this.client.getOutputStream().write(bytes);
        this.client.getOutputStream().flush();
    }

    private void send(String text) throws IOException {
        send(text.getBytes(UTF_8));
    }

    /**
     * Blocks until the bytes are in the accepted socket's receive buffer.
     *
     * Without this the test would be racing the network stack: the handshake
     * reads once, and what one read returns is whatever has arrived by then.
     * The point of the one-segment test is that the handshake keeps hold of the
     * bytes it over-read, not that the loopback delivered them in time.
     */
    private void awaitArrival(int count) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.server.getInputStream().available() < count) {
            if (System.nanoTime() > deadline) {
                fail("only " + this.server.getInputStream().available() + " of " + count + " bytes arrived");
            }
            Thread.sleep(5);
        }
    }

    private static HandshakeException.Reason reasonOf(Executable read) {
        return assertThrows(HandshakeException.class, read).reason();
    }

    /**
     * The usual case: the orchestrator's HTTP/2 preface shares the segment with
     * the IRI line, and every byte of it has to survive.
     */
    @Test
    void theLineAndWhatFollowsItArriveTogether() throws Exception {
        var remainder = concat(PREFACE, sweep());
        var payload = concat((URI + "\n").getBytes(UTF_8), remainder);

        send(payload);
        awaitArrival(payload.length);

        var result = Handshake.read(this.server);

        assertEquals(URI, result.uri());
        assertArrayEquals(remainder, result.remainder(),
                "the bytes past the newline did not come through unchanged");
        assertEquals(0, this.server.getSoTimeout(), "the handshake left its read timeout on the socket");
    }

    /** The line itself may be split over as many segments as it likes. */
    @Test
    void aLineSplitAcrossWritesIsStillRead() throws Exception {
        var writer = new Thread(() -> {
            try {
                send("urn:test:ru");
                Thread.sleep(100);
                send("nner\nXYZ");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();

        var result = Handshake.read(this.server);

        assertEquals(URI, result.uri());
        assertArrayEquals("XYZ".getBytes(UTF_8), result.remainder());
        writer.join();
    }

    /**
     * The other half of the contract: what arrives strictly later was never
     * read here, so it is still on the socket for whoever reads next. The
     * remainder is only what the handshake happened to over-read.
     */
    @Test
    void whatArrivesAfterTheHandshakeIsLeftOnTheSocket() throws Exception {
        send(URI + "\n");
        awaitArrival(URI.length() + 1);

        var result = Handshake.read(this.server);

        assertEquals(URI, result.uri());
        assertEquals(0, result.remainder().length, "nothing had arrived past the newline yet");

        send("AFTER");

        var later = new byte[5];
        var read = this.server.getInputStream().read(later);
        assertEquals(5, read);
        assertArrayEquals("AFTER".getBytes(UTF_8), later, "bytes went missing between the handshake and the pump");
    }

    @Test
    void aCarriageReturnIsTrimmedOffTheUri() throws Exception {
        send(URI + "\r\n" + "rest");
        awaitArrival(URI.length() + 6);

        var result = Handshake.read(this.server);

        assertEquals(URI, result.uri());
        assertArrayEquals("rest".getBytes(UTF_8), result.remainder());
    }

    @Test
    void anEmptyLineIsRejected() throws Exception {
        send("\n");
        assertEquals(HandshakeException.Reason.EMPTY, reasonOf(() -> Handshake.read(this.server)));
        assertEquals(0, this.server.getSoTimeout(), "a rejected handshake left its read timeout on the socket");
    }

    @Test
    void aLineOfNothingButWhitespaceIsRejected() throws Exception {
        send("   \r\n");
        assertEquals(HandshakeException.Reason.EMPTY, reasonOf(() -> Handshake.read(this.server)));
    }

    /** A line of exactly the limit is still a line. */
    @Test
    void aLineAtTheLimitIsAccepted() throws Exception {
        var uri = "urn:test:" + repeat("a", Handshake.MAX_LINE - "urn:test:".length());
        assertEquals(Handshake.MAX_LINE, uri.length());

        send(uri + "\n");
        awaitArrival(uri.length() + 1);

        assertEquals(uri, Handshake.read(this.server).uri());
    }

    @Test
    void aLineOverTheLimitIsRejected() throws Exception {
        var overlong = repeat("a", Handshake.MAX_LINE + 1);

        send(overlong);
        awaitArrival(overlong.length());

        assertEquals(HandshakeException.Reason.TOO_LONG, reasonOf(() -> Handshake.read(this.server)));
    }

    /** A peer that streams and never sends a newline is cut off just as well. */
    @Test
    void aFloodWithoutANewlineIsRejected() throws Exception {
        send(repeat("a", 4096) + "\n");
        assertEquals(HandshakeException.Reason.TOO_LONG, reasonOf(() -> Handshake.read(this.server)));
    }

    @Test
    void anEndOfStreamBeforeTheNewlineIsRejected() throws Exception {
        send("urn:test");
        this.client.close();

        assertEquals(HandshakeException.Reason.EOF, reasonOf(() -> Handshake.read(this.server)));
    }

    /** Not the orchestrator, whoever it is. */
    @Test
    void aLineThatIsNotUtf8IsRejected() throws Exception {
        send(new byte[] { (byte) 0xFF, (byte) 0xFE, '\n' });
        awaitArrival(3);

        assertEquals(HandshakeException.Reason.INVALID_ENCODING, reasonOf(() -> Handshake.read(this.server)));
    }

    /** A peer that connects and says nothing may not hold a slot forever. */
    @Test
    void aSilentPeerIsDropped() {
        var started = System.nanoTime();

        assertEquals(HandshakeException.Reason.TIMEOUT, reasonOf(() -> Handshake.read(this.server, 100)));

        var elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed < 2000, "the handshake waited " + elapsed + " ms, well past its timeout");
    }

    /** Java 11 has no {@code String.repeat}. */
    private static String repeat(String text, int times) {
        var builder = new StringBuilder(text.length() * times);
        for (var i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
    }
}
