package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server configuration is the first thing an operator gets wrong, so what
 * it accepts, what it defaults to and what it refuses is pinned down here.
 */
class ServerConfigTest {

    /**
     * A fixture file, from the copy on the test classpath.
     *
     * Deliberately not resolved against the working directory: these tests are
     * about paths resolving against the <em>document</em>, and a fixture reached
     * through the classpath lives somewhere else entirely than where Gradle runs
     * the tests from.
     */
    private static Path fixture(String name) throws Exception {
        URL url = ServerConfigTest.class.getResource("/server/" + name);
        if (url == null) {
            throw new IllegalStateException("Missing fixture /server/" + name);
        }
        return Paths.get(url.toURI());
    }

    @Test
    void takesTheDefaultsWhenTheConfigSaysNothing() throws Exception {
        var config = ServerConfig.parse(fixture("defaults.ttl"));

        assertEquals(ServerConfig.DEFAULT_HTTP_PORT, config.httpPort());
        assertEquals(ServerConfig.DEFAULT_GRPC_PORT, config.grpcPort());
        assertEquals(ServerConfig.DEFAULT_HOSTNAME, config.hostname());
        assertEquals(ServerConfig.DEFAULT_HISTORY_SIZE, config.historySize());
        assertTrue(config.processorConfigs().isEmpty(), "no catalogue was configured");
    }

    @Test
    void readsEveryConfiguredValue() throws Exception {
        var config = ServerConfig.parse(fixture("explicit.ttl"));

        assertEquals(8080, config.httpPort());
        assertEquals(4001, config.grpcPort());
        assertEquals("example.org", config.hostname());
        assertEquals(-1, config.historySize(), "-1 means an unbounded history");
    }

    @Test
    void knowsWhereItWasRead() throws Exception {
        var file = fixture("defaults.ttl");
        var config = ServerConfig.parse(file);

        assertEquals(file.toRealPath(), config.configPath());
        assertEquals(file.toRealPath().getParent(), config.configDir());
    }

    /**
     * The point of the whole exercise: a catalogue named relative to the
     * configuration is found next to the configuration, whatever directory the
     * server was started from.
     */
    @Test
    void resolvesCataloguesAgainstTheConfigDirectory() throws Exception {
        var file = fixture("explicit.ttl");
        var config = ServerConfig.parse(file);

        var expected = Arrays.asList(
                file.toRealPath().getParent().resolve("processors/echo.ttl").toRealPath(),
                file.toRealPath().getParent().resolve("processors/log.ttl").toRealPath());
        // Sorted, so the order does not depend on the parser
        assertEquals(expected, config.processorConfigs());

        // And explicitly not against the working directory, which is where a naive
        // implementation would have looked
        var cwd = Paths.get("").toAbsolutePath();
        for (Path catalogue : config.processorConfigs()) {
            assertTrue(catalogue.startsWith(config.configDir()),
                    catalogue + " should sit under the config directory " + config.configDir());
            assertTrue(!catalogue.equals(cwd.resolve("processors/echo.ttl")),
                    "the catalogue was resolved against the working directory");
        }
    }

    @Test
    void refusesAConfigThatIsNotThere(@TempDir Path dir) {
        var missing = dir.resolve("nope.ttl");

        var thrown = assertThrows(ConfigException.class, () -> ServerConfig.parse(missing));
        assertTrue(thrown.getMessage().contains("not found"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("nope.ttl"), thrown.getMessage());
    }

    @Test
    void refusesAPortThatIsNotANumber() throws Exception {
        var file = fixture("bad-port.ttl");

        var thrown = assertThrows(ConfigException.class, () -> ServerConfig.parse(file));
        assertTrue(thrown.getMessage().contains("rdfc:httpPort"),
                "the message must name the property: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("three thousand"),
                "the message must show the value: " + thrown.getMessage());
    }

    @Test
    void refusesADocumentWithoutAServer() throws Exception {
        var file = fixture("no-type.ttl");

        var thrown = assertThrows(ConfigException.class, () -> ServerConfig.parse(file));
        assertTrue(thrown.getMessage().contains("No rdfc:JvmRunnerServer"), thrown.getMessage());
    }

    @Test
    void refusesADocumentWithTwoServers() throws Exception {
        var file = fixture("two-servers.ttl");

        var thrown = assertThrows(ConfigException.class, () -> ServerConfig.parse(file));
        assertTrue(thrown.getMessage().contains("More than one"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("http://example.org/first"), thrown.getMessage());
    }

    @Test
    void refusesACatalogueThatIsNotOnThisMachine() throws Exception {
        var file = fixture("remote-config.ttl");

        var thrown = assertThrows(ConfigException.class, () -> ServerConfig.parse(file));
        assertTrue(thrown.getMessage().contains("http://example.org/processors.ttl"), thrown.getMessage());
    }

    @Test
    void refusesACatalogueThatDoesNotExist(@TempDir Path dir) throws Exception {
        var file = dir.resolve("server.ttl");
        Files.write(file, ("@prefix rdfc: <https://w3id.org/rdf-connect#>.\n"
                + "<> a rdfc:JvmRunnerServer; rdfc:processorConfig <./gone.ttl>.\n").getBytes("UTF-8"));

        var thrown = assertThrows(ConfigException.class, () -> ServerConfig.parse(file));
        assertTrue(thrown.getMessage().contains("does not exist"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("gone.ttl"), thrown.getMessage());
    }

    @Test
    void refusesADocumentThatIsNotTurtle(@TempDir Path dir) throws Exception {
        var file = dir.resolve("server.ttl");
        Files.write(file, "this is not turtle at all {{{".getBytes("UTF-8"));

        var thrown = assertThrows(ConfigException.class, () -> ServerConfig.parse(file));
        assertTrue(thrown.getMessage().contains("Failed to parse"), thrown.getMessage());
    }
}
