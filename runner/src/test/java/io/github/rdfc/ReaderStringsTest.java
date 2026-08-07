package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

/**
 * Reader.strings() has to hand out the payload as UTF-8 text, not protobuf's
 * debug representation of the ByteString.
 */
class ReaderStringsTest {
    private static final String MULTI_BYTE = "héllo wörld ☃";

    private static Reader reader() {
        return new Reader("http://example.org/channel/1", Logger.getLogger(ReaderStringsTest.class.getName()));
    }

    @Test
    void stringsDecodesMultiByteUtf8() throws Exception {
        var reader = reader();
        var received = new ArrayList<String>();
        reader.strings().on((String s) -> {
            received.add(s);
        });

        reader.msg(ByteString.copyFromUtf8(MULTI_BYTE)).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(MULTI_BYTE), received);
    }

    @Test
    void stringsDoesNotLeakTheDebugRepresentation() throws Exception {
        var reader = reader();
        var received = new ArrayList<String>();
        reader.strings().on((String s) -> {
            received.add(s);
        });

        // Long enough that ByteString.toString() would truncate the contents
        var payload = MULTI_BYTE.repeat(20);
        reader.msg(ByteString.copyFromUtf8(payload)).get(5, TimeUnit.SECONDS);

        assertEquals(1, received.size());
        assertFalse(received.get(0).contains("ByteString@"), "strings() returned the debug representation");
        assertEquals(payload, received.get(0));
    }

    @Test
    void bytesRoundTripThroughAStreamMessage() throws Exception {
        var reader = reader();
        var received = new ArrayList<String>();
        reader.strings().on((String s) -> {
            received.add(s);
        });

        // A stream message concatenates its chunks and pushes the whole thing to the
        // string listeners, splitting a multi-byte character across two chunks.
        var bytes = ByteString.copyFromUtf8(MULTI_BYTE);
        var stream = reader.stream(() -> {
        });
        stream.chunk(bytes.substring(0, 2)).get(5, TimeUnit.SECONDS);
        stream.chunk(bytes.substring(2)).get(5, TimeUnit.SECONDS);
        stream.close().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(MULTI_BYTE), received);
    }

    @Test
    void emptyPayloadDecodesToAnEmptyString() throws Exception {
        var reader = reader();
        var received = new ArrayList<String>();
        reader.strings().on((String s) -> {
            received.add(s);
        });

        reader.msg(ByteString.empty()).get(5, TimeUnit.SECONDS);

        assertTrue(received.get(0).isEmpty());
    }
}
