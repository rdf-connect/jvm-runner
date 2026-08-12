package io.github.rdfc.server;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Everything an orchestrator, an operator or a browser can ask this server over
 * HTTP.
 *
 * The requests are written onto a socket by hand instead of going through
 * {@code HttpURLConnection}: two of the cases are about paths a well-behaved
 * client would never send — a {@code ..} that escapes the serving root, a method
 * that is not GET — and a client that normalizes the path before sending it
 * tests the client, not this server.
 */
@Timeout(60)
class HttpEndpointsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path root;
    private ServerConfig config;
    private RunnerServer server;

    @BeforeEach
    void start(@TempDir Path dir) throws Exception {
        this.root = dir;
        this.config = ServerFixture.config(dir);
        // Ephemeral ports: the configured ones are somebody's real ports, and two
        // of these running at once would fight over them
        this.server = new RunnerServer(this.config, 0, 0, RunnerServer.MAX_GRPC_CONNECTIONS);
        this.server.start();
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.shutdown();
        }
    }

    @Test
    void healthReportsTheConnectionCount() throws Exception {
        Response response = get("/health");

        assertEquals(200, response.status);
        assertTrue(response.contentType().startsWith("application/json"), response.contentType());

        JsonNode health = MAPPER.readTree(response.body);
        assertEquals("ok", health.get("status").asText());
        assertEquals(0, health.get("activeConnections").asInt());
    }

    /**
     * The index is what an orchestrator reads before it decides to talk to this
     * server at all, so it is checked as RDF and not as text.
     */
    @Test
    void theIndexAdvertisesTheConfiguredGrpcAddress() throws Exception {
        Response response = get("/");

        assertEquals(200, response.status);
        assertEquals("text/turtle;charset=utf-8", response.contentType());

        String base = "http://localhost:" + this.server.boundHttpPort() + "/";
        Model model = Rio.parse(new ByteArrayInputStream(response.body.getBytes(UTF_8)), base, RDFFormat.TURTLE);

        List<Resource> runners = List.copyOf(model.filter(null, RDF.TYPE, Vocabulary.TCP_RUNNER).subjects());
        assertEquals(1, runners.size(), "the index does not advertise exactly one TcpRunner");
        Resource runner = runners.get(0);

        // The advertised port is the configured one, not the ephemeral one this
        // test happens to be listening on: the configuration is what an
        // orchestrator has to be able to reach
        assertEquals(ServerFixture.HOSTNAME + ":" + ServerFixture.ADVERTISED_GRPC_PORT,
                model.filter(runner, Vocabulary.GRPC, null).objects().iterator().next().stringValue());

        assertTrue(model.contains(runner, Vocabulary.HANDLES_SUBJECTS_OF, Vocabulary.JAVA_IMPLEMENTATION_OF));
        // And the base comes from the Host header this request carried
        assertEquals(base + IndexGenerator.RUNNER_NAME, runner.stringValue());
    }

    @Test
    void aWhitelistedFileIsServedAsTurtle() throws Exception {
        Response response = get("/processors/echo.ttl");

        assertEquals(200, response.status);
        assertEquals("text/turtle;charset=utf-8", response.contentType());
        assertEquals(new String(Files.readAllBytes(this.root.resolve("serve/processors/echo.ttl")), UTF_8),
                response.body);
    }

    /**
     * Being inside the serving root is not enough — only what a catalogue names,
     * directly or through its imports, is handed out.
     */
    @Test
    void aFileThatIsNotWhitelistedIsRefused() throws Exception {
        assertTrue(Files.exists(this.root.resolve("serve/secret.ttl")), "the fixture is not what this test needs");

        assertEquals(403, get("/secret.ttl").status);
    }

    @Test
    void aPathThatEscapesTheServingRootIsRefused() throws Exception {
        assertTrue(Files.exists(this.root.resolve("outside.ttl")), "the fixture is not what this test needs");

        assertEquals(403, get("/../outside.ttl").status, "a .. segment reached outside the serving root");
        assertEquals(403, get("/%2e%2e/outside.ttl").status, "an encoded .. reached outside the serving root");
        assertEquals(403, get("/processors/../../outside.ttl").status);
    }

    /**
     * The whitelist follows {@code owl:imports} wherever they point, so it names
     * files the operator never put in the tree they chose to expose. Those stay on
     * the whitelist — the walk had to read them, and what they import belongs to
     * the served set — but the serving root does not move up to swallow them, and
     * they are not handed out.
     */
    @Test
    void aWhitelistedFileOutsideTheConfigDirectoryIsNotServed() throws Exception {
        Path area = Files.createDirectories(this.root.resolve("clamped"));
        Path conf = Files.createDirectories(area.resolve("conf"));

        // Next to the configuration directory, not under it
        Path shapes = area.resolve("shapes.ttl");
        Files.write(shapes, "# an ontology outside the configuration directory\n".getBytes(UTF_8));

        Files.write(conf.resolve("echo.ttl"), String.join("\n",
                "@prefix rdfc: <https://w3id.org/rdf-connect#>.",
                "@prefix owl: <http://www.w3.org/2002/07/owl#>.",
                "<> owl:imports <" + shapes.toRealPath().toUri() + ">.",
                "<http://example.org/ClampedEcho> rdfc:javaImplementationOf <http://example.org/EchoDefinition>;",
                "  rdfc:jar \"echo.jar\";",
                "  rdfc:class \"org.example.Echo\".",
                "").getBytes(UTF_8));

        Path config = conf.resolve("server.ttl");
        Files.write(config, String.join("\n",
                "@prefix rdfc: <https://w3id.org/rdf-connect#>.",
                "<> a rdfc:JvmRunnerServer;",
                "  rdfc:httpPort 8080;",
                "  rdfc:grpcPort 4001;",
                "  rdfc:hostname \"example.org\";",
                "  rdfc:processorConfig <./echo.ttl>.",
                "").getBytes(UTF_8));

        RunnerServer clamped = new RunnerServer(ServerConfig.parse(config), 0, 0,
                RunnerServer.MAX_GRPC_CONNECTIONS);
        clamped.start();
        try {
            assertEquals(conf.toRealPath(), clamped.serveRoot(), "an import out of the tree widened the root");
            assertTrue(clamped.whitelist().contains(shapes.toRealPath()),
                    "the import is still whitelisted, it is only not reachable");

            int port = clamped.boundHttpPort();
            assertEquals(200, request("GET", "/echo.ttl", port).status);
            assertEquals(403, request("GET", "/../shapes.ttl", port).status,
                    "a whitelisted file outside the serving root was handed out");
            assertEquals(403, request("GET", "/%2e%2e/shapes.ttl", port).status);
        } finally {
            clamped.shutdown();
        }
    }

    /**
     * A path that resolves to nothing is refused the same way a path that
     * resolves to an unserved file is — which is what the py-runner does, and
     * deliberately: whether a file this server does not serve happens to exist is
     * not something it should be answering.
     */
    @Test
    void anUnknownPathIsRefusedRatherThanReportedMissing() throws Exception {
        assertEquals(403, get("/does-not-exist.ttl").status);
    }

    @Test
    void anythingOtherThanGetIsRefused() throws Exception {
        assertEquals(405, request("POST", "/").status);
        assertEquals(405, request("DELETE", "/processors/echo.ttl").status);
    }

    @Test
    void theDashboardIsServed() throws Exception {
        Response response = get("/dashboard");

        assertEquals(200, response.status);
        assertEquals("text/html;charset=utf-8", response.contentType());
        assertTrue(response.body.contains("JVM runner dashboard"), "the dashboard was not rebranded");
        assertFalse(response.body.contains("py-runner"), "the dashboard still names the py-runner");
        assertTrue(response.body.contains("/api/state"), "the dashboard does not read the state endpoint");
    }

    @Test
    void theStateIsServedAsJson() throws Exception {
        Response response = get("/api/state");

        assertEquals(200, response.status);
        assertTrue(response.contentType().startsWith("application/json"), response.contentType());
        assertTrue(MAPPER.readTree(response.body).isArray());
        assertEquals(0, MAPPER.readTree(response.body).size(), "a server with no runners reported some");
    }

    // ------------------------------------------------------------------ the client

    private Response get(String path) throws IOException {
        return request("GET", path);
    }

    /**
     * Sends one request verbatim and reads the whole answer.
     *
     * {@code Connection: close} throughout, so the body is simply everything up
     * to the end of the stream and this needs no chunked-transfer handling.
     *
     * @param method  the request method
     * @param rawPath the request target, exactly as it goes on the wire
     * @return the answer
     */
    private Response request(String method, String rawPath) throws IOException {
        return request(method, rawPath, this.server.boundHttpPort());
    }

    private static Response request(String method, String rawPath, int port) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);

            OutputStream out = socket.getOutputStream();
            out.write((method + " " + rawPath + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(US_ASCII));
            out.flush();

            return Response.parse(readAll(socket.getInputStream()));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** One answer, split into its status, its headers and its body. */
    private static final class Response {
        private final int status;
        private final Map<String, String> headers;
        private final String body;

        private Response(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String contentType() {
            return this.headers.getOrDefault("content-type", "");
        }

        static Response parse(byte[] answer) {
            String text = new String(answer, UTF_8);
            int split = text.indexOf("\r\n\r\n");
            if (split < 0) {
                throw new IllegalStateException("not an HTTP answer: " + text);
            }

            String[] lines = text.substring(0, split).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);

            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }

            return new Response(status, headers, text.substring(split + 4));
        }
    }
}
