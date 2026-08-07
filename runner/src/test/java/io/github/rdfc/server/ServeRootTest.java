package io.github.rdfc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The serving root is what every advertised IRI is relative to, so getting it
 * wrong makes the index point at paths the file handler then refuses.
 */
class ServeRootTest {

    @Test
    void isTheDeepestDirectoryHoldingEverything() {
        var root = ServeRoot.of(Paths.get("/srv/pipeline/conf"),
                List.of(Paths.get("/srv/pipeline/conf/server.ttl"),
                        Paths.get("/srv/pipeline/processors/echo.ttl")));

        assertEquals(Paths.get("/srv/pipeline"), root);
    }

    @Test
    void isTheConfigDirectoryWhenNothingIsWhitelisted() {
        var dir = Paths.get("/srv/pipeline/conf");

        assertEquals(dir, ServeRoot.of(dir, Set.of()));
    }

    @Test
    void staysInsideTheConfigDirectoryWhenEverythingIsUnderIt() {
        var dir = Paths.get("/srv/conf");

        assertEquals(dir, ServeRoot.of(dir, List.of(Paths.get("/srv/conf/a/b/c.ttl"),
                Paths.get("/srv/conf/d.ttl"))));
    }

    @Test
    void doesNotConfusePrefixesOfNamesWithDirectories() {
        // /srv/pipe and /srv/pipeline share the string "/srv/pipe", but not a directory
        var root = ServeRoot.commonPath(List.of(Paths.get("/srv/pipe/a.ttl"), Paths.get("/srv/pipeline/b.ttl")));

        assertEquals(Paths.get("/srv"), root);
    }

    @Test
    void fallsBackOnTheFilesystemRoot() {
        var root = ServeRoot.commonPath(List.of(Paths.get("/one/a.ttl"), Paths.get("/two/b.ttl")));

        assertEquals(Paths.get("/"), root);
    }

    @Test
    void normalizesBeforeComparing() {
        var root = ServeRoot.commonPath(List.of(Paths.get("/srv/conf/../conf/a.ttl"),
                Paths.get("/srv/conf/b.ttl")));

        assertEquals(Paths.get("/srv/conf"), root);
    }

    @Test
    void refusesAnEmptyCollection() {
        assertThrows(IllegalArgumentException.class, () -> ServeRoot.commonPath(List.<Path>of()));
    }
}
