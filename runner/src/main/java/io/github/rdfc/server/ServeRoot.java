package io.github.rdfc.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.logging.Logger;

/**
 * Where the HTTP server's root maps onto: the directory of the configuration
 * document, and never anything above it.
 *
 * Not the process' working directory — that is wherever the server happened to
 * be started from and has nothing to do with the files it serves. And not the
 * common ancestor of everything that is whitelisted either, which is what this
 * used to be: the whitelist follows {@code owl:imports <file:...>} wherever on
 * disk it points, so one processor description importing
 * {@code /opt/ontologies/shapes.ttl} moved the root up to {@code /} — putting
 * absolute filesystem paths in a public document and turning
 * {@link ServedJars}' "the file has to resolve inside the serving root" into a
 * check that holds for every file on the machine.
 *
 * The operator names the configuration, so the directory it sits in is the tree
 * they chose to expose, and that is the widest this server ever serves. A
 * whitelisted file outside it stays whitelisted — it was reachable enough to be
 * parsed, and its imports are part of the served set — but it cannot be
 * advertised in the index or handed out over HTTP, and that is said once, at
 * startup, rather than left to be discovered as a 403.
 */
public final class ServeRoot {
    private ServeRoot() {
    }

    /**
     * The root to serve a configuration and its whitelist from.
     *
     * The root is canonicalized here rather than assumed to arrive that way: the
     * containment checks measured against it compare
     * {@link Path#toRealPath(java.nio.file.LinkOption...)} results, so a caller
     * handing in a symlinked directory would otherwise make every served file
     * fail the check and 403 with nothing to go on. A directory that does not
     * exist yet cannot be resolved and is not worth refusing a start over, so it
     * falls back to an absolute, normalized path — the invariant is then as good
     * as the caller's, which is what it used to be everywhere.
     *
     * @param configDir directory of the server configuration document
     * @param whitelist every file that may be served, canonical
     * @param log       where to report the files this root cannot reach
     * @return the directory the HTTP root maps onto, canonical where the
     *         filesystem can say so
     */
    public static Path of(Path configDir, Collection<Path> whitelist, Logger log) {
        Path root = canonical(configDir, log);

        for (Path file : whitelist) {
            if (!file.toAbsolutePath().normalize().startsWith(root)) {
                // Named by the operator or imported by something they named, so
                // this is worth a line: it is served by nobody, and an
                // orchestrator that needs it will fail on a missing import
                log.warning("The whitelisted file " + file + " lies outside the serving root " + root
                        + ", so it is unreachable over HTTP; move it under " + root + " to have it served");
            }
        }

        return root;
    }

    /** The real path of {@code dir}, or the best that can be said without one. */
    private static Path canonical(Path dir, Logger log) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            Path fallback = dir.toAbsolutePath().normalize();
            log.fine("Cannot resolve the serving root " + dir + " on disk (" + e
                    + "), serving from " + fallback + " instead");
            return fallback;
        }
    }
}
