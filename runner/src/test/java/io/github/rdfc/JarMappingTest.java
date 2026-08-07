package io.github.rdfc;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.github.rdfc.server.ServedJars;
import rdfc.RunnerGrpc;

/**
 * In server mode the jar of a processor this server advertises is a URL pointing
 * back at this server's own HTTP port — the orchestrator resolved the
 * catalogue's relative {@code rdfc:jar} against the document it fetched it from.
 * Downloading it would be a runner fetching a file it is sitting on, and it
 * would not even work: the HTTP side serves whitelisted Turtle and nothing else,
 * so the jar comes back as a 403.
 *
 * This lives in {@code io.github.rdfc} rather than next to the rest of the
 * server tests because the behaviour under test is the <em>runner's</em> jar
 * loading, and reaching it needs the package-private hooks the other runner
 * tests use ({@code classLoaderFor}, {@code downloadedCopyOf}).
 */
@Timeout(120)
class JarMappingTest {
    private static final Logger LOGGER = Logger.getLogger(JarMappingTest.class.getName());

    /** The entry every fixture jar carries, to see whether its loader reads. */
    private static final String ENTRY = "fixture.txt";

    /** A port nothing listens on: a URL that is fetched here fails loudly. */
    private static final String DEAD = "http://127.0.0.1:1";

    /** Where the runner thinks it is served from. */
    private static final String RUNNER_URI = "http://runner.example:3000/jvmRunner";

    private static Path jarAt(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry(ENTRY));
            out.write("fixture".getBytes(UTF_8));
            out.closeEntry();
        }
        return path;
    }

    private static Runner runner(FakeOrchestrator orchestrator, String uri, JarResolver resolver) {
        RunnerGrpc.RunnerStub stub = FakeOrchestrator.stub(orchestrator);
        return new Runner(stub, uri, () -> {
        }, RunnerObserver.NOOP, resolver);
    }

    private static void tearDown(FakeOrchestrator orchestrator) {
        orchestrator.fail(RunnerGrpc.getConnectMethod(), "connection dropped");
    }

    /**
     * The case this exists for: a jar under the runner's own base is loaded off
     * the disk it is served from.
     *
     * The URL points at a port nothing listens on, so a download would fail
     * rather than quietly succeed — the loader working is proof that no fetch
     * happened. The host is a different one from the runner's on purpose: inside
     * a container the orchestrator reaches this server under a name it was given
     * by a port mapping or a compose network, and requiring the hosts to match
     * would switch this off exactly where it is needed.
     */
    @Test
    void aJarUnderTheRunnersBaseIsLoadedFromDisk(@TempDir Path root) throws Exception {
        Path serveRoot = Files.createDirectories(root.resolve("serve"));
        Path jar = jarAt(serveRoot.resolve("processors/echo.jar"));

        JarResolver resolver = ServedJars.of(serveRoot, RUNNER_URI);
        assertEquals(Optional.of(jar.toRealPath()), resolver.resolve(DEAD + "/processors/echo.jar"));

        FakeOrchestrator orchestrator = new FakeOrchestrator();
        Runner runner = runner(orchestrator, RUNNER_URI, resolver);
        try {
            String url = DEAD + "/processors/echo.jar";
            assertNotNull(runner.classLoaderFor(url, LOGGER).getResource(ENTRY),
                    "the jar was not loaded from the serving root");
            assertNull(runner.downloadedCopyOf(url), "the served jar was downloaded anyway");
        } finally {
            tearDown(orchestrator);
        }
    }

    /**
     * A path that climbs out of the serving root maps onto nothing, however real
     * the file it lands on is — and then the ordinary download path is what is
     * left, which here means a failure, because that is what fetching a jar off a
     * dead port does.
     */
    @Test
    void aJarOutsideTheServingRootIsNotLoadedFromDisk(@TempDir Path root) throws Exception {
        Path serveRoot = Files.createDirectories(root.resolve("serve"));
        jarAt(root.resolve("outside.jar"));

        JarResolver resolver = ServedJars.of(serveRoot, RUNNER_URI);
        assertEquals(Optional.empty(), resolver.resolve(DEAD + "/../outside.jar"),
                "a .. segment reached a jar outside the serving root");

        FakeOrchestrator orchestrator = new FakeOrchestrator();
        Runner runner = runner(orchestrator, RUNNER_URI, resolver);
        try {
            assertThrows(IOException.class, () -> runner.classLoaderFor(DEAD + "/../outside.jar", LOGGER),
                    "an escaping path was served off the disk instead of being downloaded");
        } finally {
            tearDown(orchestrator);
        }
    }

    /**
     * A jar that is not under the runner's own path is somebody else's to serve,
     * so it is downloaded exactly the way it always was.
     */
    @Test
    void aJarOutsideTheRunnersBaseIsDownloaded(@TempDir Path root) throws Exception {
        Path serveRoot = Files.createDirectories(root.resolve("serve"));
        jarAt(serveRoot.resolve("echo.jar"));

        // This runner is served from /sub/, so /other/ is not its business
        JarResolver resolver = ServedJars.of(serveRoot, "http://runner.example:3000/sub/jvmRunner");
        assertEquals(Optional.empty(), resolver.resolve("http://runner.example:3000/other/echo.jar"));

        AtomicInteger requests = new AtomicInteger();
        HttpServer jars = serveJar(serveRoot.resolve("echo.jar"), "/other/echo.jar", requests);
        try {
            FakeOrchestrator orchestrator = new FakeOrchestrator();
            Runner runner = runner(orchestrator, "http://runner.example:3000/sub/jvmRunner", resolver);
            try {
                String url = "http://127.0.0.1:" + jars.getAddress().getPort() + "/other/echo.jar";
                assertNotNull(runner.classLoaderFor(url, LOGGER).getResource(ENTRY));
                assertEquals(1, requests.get(), "the jar was not fetched");
                assertNotNull(runner.downloadedCopyOf(url), "the jar was not downloaded to a copy of its own");
            } finally {
                tearDown(orchestrator);
            }
        } finally {
            jars.stop(0);
        }
    }

    /**
     * The CLI has no serving root and no served base, so nothing about its jar
     * handling changes: the same URL that the server would have mapped onto a
     * file is fetched over HTTP, temporary copy and all.
     */
    @Test
    void theCliDownloadsAsItAlwaysDid(@TempDir Path root) throws Exception {
        Path serveRoot = Files.createDirectories(root.resolve("serve"));
        jarAt(serveRoot.resolve("processors/echo.jar"));

        AtomicInteger requests = new AtomicInteger();
        HttpServer jars = serveJar(serveRoot.resolve("processors/echo.jar"), "/processors/echo.jar", requests);
        try {
            FakeOrchestrator orchestrator = new FakeOrchestrator();
            // The three-argument constructor: the one the CLI uses
            Runner runner = new Runner(FakeOrchestrator.stub(orchestrator), RUNNER_URI, () -> {
            });
            try {
                String url = "http://127.0.0.1:" + jars.getAddress().getPort() + "/processors/echo.jar";
                assertNotNull(runner.classLoaderFor(url, LOGGER).getResource(ENTRY));
                assertEquals(1, requests.get(), "the CLI stopped downloading its jars");

                Path copy = runner.downloadedCopyOf(url);
                assertNotNull(copy, "the CLI did not download the jar to a copy of its own");
                assertTrue(Files.exists(copy));

                tearDown(orchestrator);
                assertFalse(Files.exists(copy), "the downloaded jar was left behind in " + copy);
            } finally {
                tearDown(orchestrator);
            }
        } finally {
            jars.stop(0);
        }
    }

    /** A runner that is not named by an http(s) URL can map nothing at all. */
    @Test
    void aRunnerWithoutAServedBaseMapsNothing(@TempDir Path root) throws Exception {
        Path serveRoot = Files.createDirectories(root.resolve("serve"));
        jarAt(serveRoot.resolve("echo.jar"));

        assertEquals(JarResolver.NONE, ServedJars.of(serveRoot, "urn:rdfc:runner"));
        assertEquals(JarResolver.NONE, ServedJars.of(null, RUNNER_URI));
        assertEquals(Optional.empty(),
                ServedJars.of(serveRoot, RUNNER_URI).resolve("file:///tmp/echo.jar"));
    }

    /**
     * Serves one file over HTTP, on a port of the operating system's choosing.
     *
     * @param file     what to serve
     * @param path     under which path
     * @param requests counts the fetches
     * @return the running server, to be stopped by the caller
     */
    private static HttpServer serveJar(Path file, String path, AtomicInteger requests) throws IOException {
        byte[] content = Files.readAllBytes(file);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, content.length);
            try (java.io.OutputStream body = exchange.getResponseBody()) {
                body.write(content);
            }
        });
        server.start();
        return server;
    }
}
