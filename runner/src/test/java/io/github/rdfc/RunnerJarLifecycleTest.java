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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

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

        tearDown(orchestrator);
    }

    @Test
    void differentJarsGetTheirOwnLoader(@TempDir Path dir) throws Exception {
        var orchestrator = new FakeOrchestrator();
        var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-separate");

        var one = runner.classLoaderFor(jarAt(dir.resolve("one.jar")), LOGGER);
        var other = runner.classLoaderFor(jarAt(dir.resolve("other.jar")), LOGGER);

        assertNotSame(one, other);

        tearDown(orchestrator);
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

            var loader = runner.classLoaderFor(url, LOGGER);
            assertSame(loader, runner.classLoaderFor(url, LOGGER));
            assertEquals(1, requests.get(), "the same jar was downloaded more than once");

            var copy = runner.downloadedCopyOf(url);
            assertNotNull(copy, "the remote jar was not downloaded to a copy of its own");
            assertTrue(Files.exists(copy));

            tearDown(orchestrator);

            assertFalse(Files.exists(copy), "the downloaded jar was left behind in " + copy);
        } finally {
            server.stop(0);
        }
    }

    /**
     * The one thing a download may never do: hold up the teardown.
     *
     * A jar server that accepts the connection and then says nothing used to park
     * the whole teardown behind it, on a gRPC callback thread — no `onComplete`, no
     * completion, a connection that never ends. A stalled download may cost the
     * processor that wants that jar; it may not cost the runner its ending.
     */
    @Test
    void aStalledDownloadDoesNotHoldUpTheTeardown(@TempDir Path dir) throws Exception {
        var jar = Files.readAllBytes(Path.of(java.net.URI.create(jarAt(dir.resolve("processors.jar")))));
        var downloading = new CountDownLatch(1);
        var answer = new CountDownLatch(1);

        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/processors.jar", exchange -> {
            downloading.countDown();
            try {
                // The stall: the connection is accepted and then nothing happens
                answer.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, jar.length);
            try (var body = exchange.getResponseBody()) {
                body.write(jar);
            }
        });
        server.start();

        var threads = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "jar-loader");
            thread.setDaemon(true);
            return thread;
        });

        try {
            var orchestrator = new FakeOrchestrator();
            var runner = TestRunner.create(orchestrator, "http://example.org/runner/jars-stalled");
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/processors.jar";

            var loading = threads.submit(() -> runner.classLoaderFor(url, LOGGER));
            assertTrue(downloading.await(5, TimeUnit.SECONDS), "the download never started");

            // A second processor out of the same jar waits for that one download
            var waiting = threads.submit(() -> runner.classLoaderFor(url, LOGGER));

            // Bounded by the rig, which gives the delivery five seconds: against a
            // teardown that waits for the download this times out
            tearDown(orchestrator);

            assertThrows(ExecutionException.class, () -> runner.completion().get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS),
                    "the second processor was left waiting for a download nobody is going to use");

            // And when the server finally does answer, the loader that arrives too
            // late is not handed out and not kept either
            answer.countDown();
            var late = assertThrows(ExecutionException.class, () -> loading.get(30, TimeUnit.SECONDS));
            assertTrue(late.getCause().getMessage().contains("torn down"),
                    "the download did not notice the teardown: " + late.getCause());
            assertNull(runner.downloadedCopyOf(url), "the runner held on to a jar it loaded after its teardown");
        } finally {
            answer.countDown();
            threads.shutdownNow();
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
