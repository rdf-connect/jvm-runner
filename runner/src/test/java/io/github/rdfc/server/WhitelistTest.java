package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whitelist decides what an anonymous HTTP client can read off this
 * machine, so its walk is pinned down: what it follows, where it stops, and
 * what a broken file does to it.
 */
class WhitelistTest {
    private static final Logger LOG = Logger.getLogger(WhitelistTest.class.getName());

    /**
     * Writes a Turtle document that imports a number of other files.
     *
     * @param file    to write
     * @param imports the files it imports, by {@code file:} IRI
     * @return the file, canonical
     */
    private static Path document(Path file, Path... imports) throws IOException {
        StringBuilder ttl = new StringBuilder("@prefix owl: <http://www.w3.org/2002/07/owl#>.\n<>");
        String separator = " owl:imports ";
        for (Path imported : imports) {
            ttl.append(separator).append('<').append(imported.toUri()).append('>');
            separator = ", ";
        }
        ttl.append(".\n");

        Files.write(file, ttl.toString().getBytes(StandardCharsets.UTF_8));
        return file.toRealPath();
    }

    @Test
    void followsAChainOfImports(@TempDir Path dir) throws Exception {
        var c = document(dir.resolve("c.ttl"));
        var b = document(dir.resolve("b.ttl"), c);
        var a = document(dir.resolve("a.ttl"), b);

        Set<Path> whitelist = Whitelist.build(Collections.singletonList(a), LOG);

        assertEquals(Set.of(a, b, c), whitelist);
    }

    @Test
    void terminatesOnACycle(@TempDir Path dir) throws Exception {
        // Written twice: A has to exist before B can name it, and B before A imports it
        var a = dir.resolve("a.ttl");
        var b = document(dir.resolve("b.ttl"));
        document(a, b);
        document(b, a);

        Set<Path> whitelist = Whitelist.build(Collections.singletonList(a.toRealPath()), LOG);

        assertEquals(Set.of(a.toRealPath(), b), whitelist);
    }

    @Test
    void ignoresAnImportOfAFileThatIsNotThere(@TempDir Path dir) throws Exception {
        var missing = dir.resolve("gone.ttl");
        var a = document(dir.resolve("a.ttl"), missing);

        Set<Path> whitelist = Whitelist.build(Collections.singletonList(a), LOG);

        assertEquals(Set.of(a), whitelist);
        assertFalse(whitelist.contains(missing), "a file that is not there cannot be served");
    }

    @Test
    void ignoresNonFileImports(@TempDir Path dir) throws Exception {
        var a = dir.resolve("a.ttl");
        Files.write(a, ("@prefix owl: <http://www.w3.org/2002/07/owl#>.\n"
                + "<> owl:imports <http://www.w3.org/ns/shacl#>.\n").getBytes(StandardCharsets.UTF_8));

        Set<Path> whitelist = Whitelist.build(Collections.singletonList(a.toRealPath()), LOG);

        assertEquals(Set.of(a.toRealPath()), whitelist);
    }

    /**
     * A file that was named by the operator stays servable even when it cannot be
     * parsed — the syntax error is somebody's problem, but refusing to serve the
     * file makes it a different, more confusing problem. What does stop is the
     * walk: an unparsable document has no imports anybody can trust.
     */
    @Test
    void keepsAnUnparsableFileButDoesNotFollowIt(@TempDir Path dir) throws Exception {
        var c = document(dir.resolve("c.ttl"));
        var b = dir.resolve("b.ttl");
        Files.write(b, ("@prefix owl: <http://www.w3.org/2002/07/owl#>.\n"
                + "<> owl:imports <" + c.toUri() + ">.\n"
                + "this is { not ] turtle\n").getBytes(StandardCharsets.UTF_8));
        var a = document(dir.resolve("a.ttl"), b);

        Set<Path> whitelist = Whitelist.build(Collections.singletonList(a), LOG);

        assertTrue(whitelist.contains(b.toRealPath()), "the broken file itself stays whitelisted");
        assertFalse(whitelist.contains(c), "nothing it claims to import is followed");
    }

    /**
     * Only what a document says about itself counts. An import hung on another
     * subject is a claim about a document this one does not own, and honouring it
     * would let any catalogue enlarge the served set at will.
     */
    @Test
    void ignoresImportsOnOtherSubjects(@TempDir Path dir) throws Exception {
        var other = document(dir.resolve("other.ttl"));
        var a = dir.resolve("a.ttl");
        Files.write(a, ("@prefix owl: <http://www.w3.org/2002/07/owl#>.\n"
                + "<http://example.org/somebody-else> owl:imports <" + other.toUri() + ">.\n")
                        .getBytes(StandardCharsets.UTF_8));

        Set<Path> whitelist = Whitelist.build(Collections.singletonList(a.toRealPath()), LOG);

        assertEquals(Set.of(a.toRealPath()), whitelist);
    }

    @Test
    void startsFromEveryRoot(@TempDir Path dir) throws Exception {
        var shared = document(dir.resolve("shared.ttl"));
        var one = document(dir.resolve("one.ttl"), shared);
        var two = document(dir.resolve("two.ttl"), shared);

        Set<Path> whitelist = Whitelist.build(List.of(one, two), LOG);

        assertEquals(Set.of(one, two, shared), whitelist);
    }
}
