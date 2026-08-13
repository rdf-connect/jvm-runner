package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * Proves the JUnit 5 rig runs and that the runner's own classes are on the test
 * classpath.
 */
class SmokeTest {
    @Test
    void readerExposesItsChannelId() {
        var reader = new Reader("http://example.org/channel/1", Logger.getLogger(SmokeTest.class.getName()));

        assertEquals("http://example.org/channel/1", reader.id());
        assertNotNull(reader.buffers());
    }
}
