package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The serving root is what every advertised IRI is relative to and what the
 * containment checks on the HTTP and the jar side are measured against, so it
 * is the configuration directory and nothing wider.
 */
class ServeRootTest {

    /** Keeps what was logged, so the warnings can be asserted on. */
    private static final class Recording extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            this.messages.add(record.getLevel() + " " + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        boolean warned(String about) {
            return this.messages.stream().anyMatch(line -> line.startsWith(Level.WARNING.toString())
                    && line.contains(about));
        }
    }

    private static Logger recordingInto(Recording recording) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(recording);
        return logger;
    }

    @Test
    void isTheConfigDirectoryWhenNothingIsWhitelisted() {
        var dir = Paths.get("/srv/pipeline/conf");

        assertEquals(dir, ServeRoot.of(dir, Set.of(), Logger.getAnonymousLogger()));
    }

    @Test
    void isTheConfigDirectoryWhenEverythingIsUnderIt() {
        var dir = Paths.get("/srv/conf");
        var recording = new Recording();

        assertEquals(dir, ServeRoot.of(dir, List.of(Paths.get("/srv/conf/a/b/c.ttl"), Paths.get("/srv/conf/d.ttl")),
                recordingInto(recording)));
        assertFalse(recording.warned("unreachable"), "nothing is out of reach, so nothing is warned about");
    }

    /**
     * The regression: the root used to be the common ancestor of the configuration
     * directory and everything on the whitelist, and the whitelist follows
     * {@code owl:imports} anywhere on disk. One processor description importing an
     * ontology out of {@code /opt} therefore made the root {@code /} — which put
     * absolute filesystem paths in a public document and left
     * {@code real.startsWith(serveRoot)} true for every file on the machine.
     */
    @Test
    void doesNotWidenPastTheConfigDirectoryForAnOutOfTreeImport() {
        var dir = Paths.get("/srv/rdfc/config");
        var recording = new Recording();

        var root = ServeRoot.of(dir, List.of(Paths.get("/srv/rdfc/config/processors/echo.ttl"),
                Paths.get("/opt/ontologies/shapes.ttl")), recordingInto(recording));

        assertEquals(dir, root, "one import out of the tree collapsed the serving root");
        assertTrue(recording.warned("/opt/ontologies/shapes.ttl"),
                "the file that cannot be served was not reported: " + recording.messages);
        assertFalse(recording.warned("/srv/rdfc/config/processors/echo.ttl"),
                "a file that is served was reported as unreachable");
    }

    /** A sibling of the configuration directory is outside it, prefix or not. */
    @Test
    void doesNotConfusePrefixesOfNamesWithContainment() {
        var recording = new Recording();

        var root = ServeRoot.of(Paths.get("/srv/pipe"), List.of(Paths.get("/srv/pipeline/b.ttl")),
                recordingInto(recording));

        assertEquals(Paths.get("/srv/pipe"), root);
        assertTrue(recording.warned("/srv/pipeline/b.ttl"));
    }

    @Test
    void normalizesTheDirectoryItIsGiven() {
        Path root = ServeRoot.of(Paths.get("/srv/conf/../conf"), List.of(Paths.get("/srv/conf/a.ttl")),
                Logger.getAnonymousLogger());

        assertEquals(Paths.get("/srv/conf"), root);
    }

    /**
     * The containment test is on normalized paths on both sides, so a whitelist
     * entry that is inside the tree but spelled with a {@code ..} in it is inside.
     */
    @Test
    void normalizesTheWhitelistBeforeComparing() {
        var recording = new Recording();

        ServeRoot.of(Paths.get("/srv/conf"), List.of(Paths.get("/srv/conf/sub/../a.ttl")),
                recordingInto(recording));

        assertFalse(recording.warned("a.ttl"),
                "a file inside the root was called unreachable because of a '..' in its name: "
                        + recording.messages);
    }

    /**
     * The invariant the containment checks depend on: everything measured against
     * this root is a {@link Path#toRealPath} result, so the root has to be one too.
     * A symlinked configuration directory used to leave that to whoever called this
     * and 403 every served file when they did not.
     */
    @Test
    void resolvesASymlinkedDirectoryToItsRealPath(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectory(tmp.resolve("conf"));
        Path file = Files.writeString(real.resolve("a.ttl"), "# a");
        Path link = tmp.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "this filesystem does not do symlinks: " + e);
            return;
        }

        var recording = new Recording();
        Path root = ServeRoot.of(link, List.of(file.toRealPath()), recordingInto(recording));

        assertEquals(real.toRealPath(), root, "the symlink was left unresolved, so nothing under it serves");
        assertTrue(file.toRealPath().startsWith(root),
                "a file in the configuration directory failed the containment check the file handler makes");
        assertFalse(recording.warned("unreachable"),
                "a file under the root was reported as out of reach: " + recording.messages);
    }

    /**
     * A configuration directory that is not on disk is no reason to refuse a start:
     * the root is then as canonical as what it was handed, which is what it was
     * before.
     */
    @Test
    void fallsBackToTheAbsolutePathWhenTheDirectoryDoesNotExist(@TempDir Path tmp) {
        Path absent = tmp.resolve("nowhere");

        assertEquals(absent, ServeRoot.of(absent, Set.of(), Logger.getAnonymousLogger()));
    }
}
