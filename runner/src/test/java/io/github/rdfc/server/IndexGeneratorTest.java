package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The index is the only thing an orchestrator reads before it decides whether
 * to talk to this server at all, so it is checked as RDF — parsed back and
 * asserted triple by triple — and not as text: how RDF4J happens to lay a
 * document out is not part of the contract, what it says is.
 */
class IndexGeneratorTest {
    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private static final String BASE = "http://localhost:3000/";

    private static final IRI ECHO = VF.createIRI("http://example.org/Echo");
    private static final IRI LOG = VF.createIRI("http://example.org/Log");

    /**
     * Writes a catalogue declaring one processor.
     *
     * @param file    to write
     * @param uri     of the processor
     * @param label   its label, or null
     * @param comment its comment, or null
     * @return the file, canonical
     */
    private static Path catalogue(Path file, String uri, String label, String comment) throws IOException {
        StringBuilder ttl = new StringBuilder()
                .append("@prefix rdfc: <https://w3id.org/rdf-connect#>.\n")
                .append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n")
                .append('<').append(uri).append("> rdfc:javaImplementationOf <").append(uri).append("Definition>")
                .append(";\n  rdfc:jar \"x.jar\";\n  rdfc:class \"org.example.X\"");
        if (label != null) {
            ttl.append(";\n  rdfs:label \"").append(label).append('"');
        }
        if (comment != null) {
            ttl.append(";\n  rdfs:comment \"").append(comment).append('"');
        }
        ttl.append(".\n");

        Files.createDirectories(file.getParent());
        Files.write(file, ttl.toString().getBytes(StandardCharsets.UTF_8));
        return file.toRealPath();
    }

    /** Parses a generated document back, so it can be asserted as triples. */
    private static Model parse(String turtle) throws IOException {
        return Rio.parse(new ByteArrayInputStream(turtle.getBytes(StandardCharsets.UTF_8)), BASE, RDFFormat.TURTLE);
    }

    /** The single object of a statement, for the assertions below. */
    private static Value object(Model model, IRI subject, IRI predicate) {
        var objects = model.filter(subject, predicate, null).objects();
        assertEquals(1, objects.size(), "expected exactly one " + predicate + " on " + subject);
        return objects.iterator().next();
    }

    @Test
    void advertisesTheRunnerItself(@TempDir Path dir) throws Exception {
        var root = dir.resolve("root").toAbsolutePath();
        Files.createDirectories(root);
        var generator = new IndexGenerator(List.of(), root.toRealPath(), "runner.example.org", 4001);

        var model = parse(generator.generate(BASE));
        var runner = VF.createIRI(BASE + "jvmRunner");

        assertTrue(model.contains(runner, RDF.TYPE, Vocabulary.TCP_RUNNER),
                "the orchestrator finds this server by its type");
        assertTrue(model.contains(runner, Vocabulary.HANDLES_SUBJECTS_OF, Vocabulary.JAVA_IMPLEMENTATION_OF));
        assertEquals("runner.example.org:4001", object(model, runner, Vocabulary.GRPC).stringValue());
    }

    @Test
    void carriesThePrelude(@TempDir Path dir) throws Exception {
        var root = dir.toRealPath();
        var generator = new IndexGenerator(List.of(), root, "localhost", 50051);

        var model = parse(generator.generate(BASE));
        var shacl = "http://www.w3.org/ns/shacl#";

        assertTrue(model.contains(Vocabulary.JAVA_IMPLEMENTATION_OF, RDFS.SUBPROPERTYOF,
                VF.createIRI("https://w3id.org/sds#implementationOf")),
                "a JVM processor is a processor");
        assertTrue(model.contains(null, VF.createIRI(shacl, "targetSubjectsOf"), Vocabulary.JAVA_IMPLEMENTATION_OF),
                "the shape says how a JVM processor is described");
        // The two properties the runner actually reads off a processor description
        assertTrue(model.contains(null, VF.createIRI(shacl, "name"), VF.createLiteral("jar")));
        assertTrue(model.contains(null, VF.createIRI(shacl, "name"), VF.createLiteral("clazz")));
    }

    @Test
    void advertisesEachProcessorRelativeToTheServingRoot(@TempDir Path dir) throws Exception {
        var root = dir.resolve("root");
        Files.createDirectories(root);
        var echo = catalogue(root.resolve("processors/echo.ttl"), ECHO.stringValue(), "Echo", "Sends it back.");
        var log = catalogue(root.resolve("log.ttl"), LOG.stringValue(), null, null);

        var generator = new IndexGenerator(List.of(echo, log), root.toRealPath(), "localhost", 50051);
        var model = parse(generator.generate(BASE));

        assertTrue(model.contains(ECHO, RDF.TYPE, Vocabulary.PROCESSOR));
        assertEquals(BASE + "processors/echo.ttl", object(model, ECHO, RDFS.ISDEFINEDBY).stringValue());
        assertEquals("Echo", object(model, ECHO, RDFS.LABEL).stringValue());
        assertEquals("Sends it back.", object(model, ECHO, RDFS.COMMENT).stringValue());

        assertTrue(model.contains(LOG, RDF.TYPE, Vocabulary.PROCESSOR));
        assertEquals(BASE + "log.ttl", object(model, LOG, RDFS.ISDEFINEDBY).stringValue());
        assertTrue(model.filter(LOG, RDFS.LABEL, null).isEmpty(), "no label was declared, so none is invented");
        assertTrue(model.filter(LOG, RDFS.COMMENT, null).isEmpty(), "and no comment either");
    }

    /**
     * A catalogue outside the serving root can never be fetched from this server:
     * its IRI would contain a '..' that every client normalizes away before
     * sending, so the request that arrives asks for something else. Advertising it
     * would promise a 403.
     */
    @Test
    void leavesOutAProcessorDeclaredOutsideTheServingRoot(@TempDir Path dir) throws Exception {
        var root = dir.resolve("root");
        Files.createDirectories(root);
        var inside = catalogue(root.resolve("echo.ttl"), ECHO.stringValue(), "Echo", null);
        var outside = catalogue(dir.resolve("elsewhere/log.ttl"), LOG.stringValue(), null, null);

        var generator = new IndexGenerator(List.of(inside, outside), root.toRealPath(), "localhost", 50051);
        var model = parse(generator.generate(BASE));

        assertTrue(model.contains(ECHO, RDF.TYPE, Vocabulary.PROCESSOR));
        assertTrue(model.filter(LOG, null, null).isEmpty(),
                "a processor that cannot be fetched is not advertised at all");
    }

    @Test
    void rebasesOnEveryBaseItIsAskedFor(@TempDir Path dir) throws Exception {
        var root = dir.resolve("root");
        Files.createDirectories(root);
        var echo = catalogue(root.resolve("echo.ttl"), ECHO.stringValue(), null, null);

        var generator = new IndexGenerator(List.of(echo), root.toRealPath(), "localhost", 50051);
        var other = "https://rdfc.example.com:8443/";
        var model = parse(generator.generate(other));

        assertEquals(other + "echo.ttl", object(model, ECHO, RDFS.ISDEFINEDBY).stringValue());
        assertTrue(model.contains(VF.createIRI(other + "jvmRunner"), RDF.TYPE, Vocabulary.TCP_RUNNER));
    }

    @Test
    void deduplicatesAProcessorDeclaredTwice(@TempDir Path dir) throws Exception {
        var root = dir.resolve("root");
        Files.createDirectories(root);
        var first = catalogue(root.resolve("a.ttl"), ECHO.stringValue(), "First", null);
        var second = catalogue(root.resolve("b.ttl"), ECHO.stringValue(), "Second", null);

        var generator = new IndexGenerator(List.of(first, second), root.toRealPath(), "localhost", 50051);

        assertEquals(1, generator.descriptions().size());
        var model = parse(generator.generate(BASE));
        assertEquals(BASE + "a.ttl", object(model, ECHO, RDFS.ISDEFINEDBY).stringValue());
    }

    @Test
    void skipsACatalogueItCannotParse(@TempDir Path dir) throws Exception {
        var root = dir.resolve("root");
        Files.createDirectories(root);
        var good = catalogue(root.resolve("echo.ttl"), ECHO.stringValue(), null, null);
        var broken = root.resolve("broken.ttl");
        Files.write(broken, "not { turtle ] at all".getBytes(StandardCharsets.UTF_8));

        var generator = new IndexGenerator(List.of(good, broken.toRealPath()), root.toRealPath(), "localhost",
                50051);

        assertEquals(1, generator.descriptions().size(), "the unparsable catalogue contributes nothing");
        assertFalse(generator.generate(BASE).isEmpty());
    }

    @Test
    void servesTheSameDocumentForTheSameBase(@TempDir Path dir) throws Exception {
        var generator = new IndexGenerator(List.of(), dir.toRealPath(), "localhost", 50051);

        assertSame(generator.generate(BASE), generator.generate(BASE), "the second request is served from the cache");
    }

    /**
     * The cache key is the Host header, which anybody who can reach the port picks
     * freely, so it may not be a way to make this server allocate without end.
     */
    @Test
    void boundsTheCacheOfBases(@TempDir Path dir) throws Exception {
        var generator = new IndexGenerator(List.of(), dir.toRealPath(), "localhost", 50051);

        for (int i = 0; i < IndexGenerator.MAX_CACHED_BASES + 1; i++) {
            generator.generate("http://host-" + i + ".example.org/");
        }

        assertEquals(IndexGenerator.MAX_CACHED_BASES, generator.cachedBases());
    }
}
