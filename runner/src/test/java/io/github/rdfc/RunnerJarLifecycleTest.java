package io.github.rdfc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import rdfc.RunnerGrpc;

/**
 * Several processors usually come out of one and the same jar. Loading that jar
 * once per processor downloads the same file over and over and hands every
 * processor its own copy of the same classes, so the runner caches the loader —
 * and being a cache on the runner, it has to be emptied when the runner ends.
 *
 * In server mode that matters twice over: runners come and go, and every one of
 * them that leaves an open class loader and a temp file behind is a leak that
 * accumulates for as long as the server runs.
 */
class RunnerJarLifecycleTest {
    private static final Logger LOGGER = Logger.getLogger(RunnerJarLifecycleTest.class.getName());
    /** The entry every fixture jar carries, to see whether its loader still reads. */
    private static final String ENTRY = "fixture.txt";

    /**
     * Writes a jar with a single resource in it. A real jar, so the class loader
     * genuinely opens it and genuinely has something to close.
     *
     * @param path where to write it
     * @return the URL that would appear in a processor's config
     */
    private static String jarAt(Path path) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry(ENTRY));
            out.write("fixture".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return path.toUri().toString();
    }

    /** Every jar this runner downloaded lands in the temp dir under this name. */
    private static Set<Path> downloadedJars() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(file -> file.getFileName().toString().startsWith("remote-lib"))
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        }
    }

    private static void tearDown(FakeOrchestrator orchestrator) {
        orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped");
    }

    @Test
    void theSameJarIsLoadedOnce(@TempDir Path dir) throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-cached");
        var jar = jarAt(dir.resolve("processors.jar"));

        var first = runner.classLoaderFor(jar, LOGGER);
        var second = runner.classLoaderFor(jar, LOGGER);

        assertSame(first, second, "two processors out of one jar were handed two class loaders");
    }

    @Test
    void differentJarsGetTheirOwnLoader(@TempDir Path dir) throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-separate");

        var one = runner.classLoaderFor(jarAt(dir.resolve("one.jar")), LOGGER);
        var other = runner.classLoaderFor(jarAt(dir.resolve("other.jar")), LOGGER);

        assertNotSame(one, other);
    }

    @Test
    void theTeardownClosesTheLoaders(@TempDir Path dir) throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-closed");

        var loader = runner.classLoaderFor(jarAt(dir.resolve("processors.jar")), LOGGER);
        assertNotNull(loader.getResource(ENTRY), "the fixture jar was not readable to begin with");

        tearDown(orchestrator);

        // A closed URLClassLoader hands back nothing anymore, and only then does it
        // let go of the file it has open
        assertNull(loader.getResource(ENTRY), "the class loader was left open");
    }

    /** After the teardown nothing may be loaded that would never be closed again. */
    @Test
    void nothingIsLoadedAfterTheTeardown(@TempDir Path dir) throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-after-teardown");
        var jar = jarAt(dir.resolve("processors.jar"));

        tearDown(orchestrator);

        assertThrows(IllegalStateException.class, () -> runner.classLoaderFor(jar, LOGGER));
    }

    /**
     * The whole point of the cache for a remote jar: downloaded once, however many
     * processors come out of it, and the copy it made is cleaned up afterwards.
     */
    @Test
    void aRemoteJarIsDownloadedOnceAndDeletedOnTeardown(@TempDir Path dir) throws Exception {
        var jar = Files.readAllBytes(Path.of(java.net.URI.create(jarAt(dir.resolve("processors.jar")))));
        var requests = new AtomicInteger();

        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/processors.jar", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, jar.length);
            try (var body = exchange.getResponseBody()) {
                body.write(jar);
            }
        });
        server.start();

        try {
            var orchestrator = new FakeOrchestrator();
            var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-downloaded");
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/processors.jar";

            var before = downloadedJars();
            var loader = runner.classLoaderFor(url, LOGGER);
            assertSame(loader, runner.classLoaderFor(url, LOGGER));
            assertEquals(1, requests.get(), "the same jar was downloaded more than once");

            var downloaded = downloadedJars();
            downloaded.removeAll(before);
            assertEquals(1, downloaded.size(), "expected exactly one downloaded jar, got " + downloaded);
            var copy = downloaded.iterator().next();
            assertTrue(Files.exists(copy));

            tearDown(orchestrator);

            assertFalse(Files.exists(copy), "the downloaded jar was left behind in " + copy);
        } finally {
            server.stop(0);
        }
    }

    /**
     * A jar that was already on this machine belongs to whoever put it there, so
     * the teardown closes the loader but leaves the file alone.
     */
    @Test
    void aLocalJarIsNotDeleted(@TempDir Path dir) throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-local");

        var path = dir.resolve("processors.jar");
        runner.classLoaderFor(jarAt(path), LOGGER);

        tearDown(orchestrator);

        assertTrue(Files.exists(path), "a jar this runner did not download was deleted");
    }
}
