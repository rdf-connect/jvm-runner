package io.github.rdfc.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * The directory layout the server tests run against.
 *
 * <pre>
 * root/
 *   outside.ttl              — exists, is not under the serving root
 *   serve/
 *     server.ttl             — the configuration; its directory is the serving root
 *     secret.ttl             — under the serving root, not whitelisted
 *     processors/echo.ttl    — the one catalogue, and so the one served file
 * </pre>
 *
 * Built in a temporary directory rather than read off the classpath, because two
 * of the things worth asserting — a path that escapes the serving root, a file
 * inside it that is not whitelisted — only exist if the test controls what sits
 * next to what.
 */
final class ServerFixture {
    /** What the fixture configuration advertises, and never binds. */
    static final int ADVERTISED_GRPC_PORT = 4001;

    /** The host name the fixture configuration advertises. */
    static final String HOSTNAME = "example.org";

    /** The processor the served catalogue declares. */
    static final String PROCESSOR = "http://example.org/Echo";

    private ServerFixture() {
    }

    /**
     * Writes the layout and parses the configuration in it.
     *
     * @param root an empty directory to build in
     * @return the parsed configuration
     */
    static ServerConfig config(Path root) throws IOException, ConfigException {
        Path serve = Files.createDirectories(root.resolve("serve"));
        Path processors = Files.createDirectories(serve.resolve("processors"));

        Files.write(processors.resolve("echo.ttl"), String.join("\n",
                "@prefix rdfc: <https://w3id.org/rdf-connect#>.",
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.",
                "<" + PROCESSOR + "> a rdfc:Processor;",
                "  rdfc:javaImplementationOf <http://example.org/EchoDefinition>;",
                "  rdfs:label \"Echo\";",
                "  rdfc:jar \"processors/echo.jar\";",
                "  rdfc:class \"org.example.Echo\".",
                "").getBytes(UTF_8));

        Files.write(serve.resolve("secret.ttl"),
                "# Inside the serving root, named by nobody, so never served.\n".getBytes(UTF_8));
        Files.write(root.resolve("outside.ttl"),
                "# Outside the serving root entirely.\n".getBytes(UTF_8));

        Path config = serve.resolve("server.ttl");
        Files.write(config, String.join("\n",
                "@prefix rdfc: <https://w3id.org/rdf-connect#>.",
                "<> a rdfc:JvmRunnerServer;",
                "  rdfc:httpPort 8080;",
                "  rdfc:grpcPort " + ADVERTISED_GRPC_PORT + ";",
                "  rdfc:hostname \"" + HOSTNAME + "\";",
                "  rdfc:processorConfig <./processors/echo.ttl>.",
                "").getBytes(UTF_8));

        return ServerConfig.parse(config);
    }

    /**
     * Waits for something the server does on a thread of its own.
     *
     * @param condition what has to become true
     * @param what      named in the failure
     */
    static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting until " + what);
            }
        }
        fail("timed out waiting until " + what);
    }
}
