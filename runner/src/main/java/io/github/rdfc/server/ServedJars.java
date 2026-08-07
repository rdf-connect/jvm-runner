package io.github.rdfc.server;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

import io.github.rdfc.JarResolver;

/**
 * Finds the jar of a processor this server advertises on the disk it advertises
 * it from.
 *
 * The orchestrator hands a processor's jar back as an absolute URL, because it
 * resolved the relative {@code rdfc:jar} of the description against the document
 * it fetched that description from — which is this server's own HTTP port. So a
 * runner in server mode is routinely told to download a jar that is lying next
 * to the catalogue it is already serving. It cannot even do that: the HTTP side
 * hands out whitelisted Turtle and nothing else, so a jar next to it comes back
 * as a 403.
 *
 * This is the JVM counterpart of the js-runner's {@code makeRelative}: the jar
 * URL's path is relativized against the <em>path</em> of the runner's own IRI —
 * both were minted against the same served base — and resolved under the serving
 * root.
 *
 * <b>Only the paths are compared, never the hosts.</b> A container that
 * advertises itself as {@code http://runner:3000/} is reached by the orchestrator
 * under a name that the port mapping, the compose network or the reverse proxy
 * chose, and the jar URL then carries whichever of those names the orchestrator
 * happened to use. Requiring the two hosts to match would switch this off exactly
 * where it is needed most. The safety does not come from the host: it comes from
 * the file having to resolve, canonically, to something inside the serving root.
 */
public final class ServedJars implements JarResolver {
    private static final Logger LOGGER = Logger.getLogger(ServedJars.class.getName());

    private final Path serveRoot;

    /**
     * The path the runner's own IRI sits in, with a trailing slash.
     *
     * The IRI itself names the runner, so it is the "file name" in that path and
     * plays no part in the prefix — the same thing {@code makeRelative} does when
     * it pops the last segment off the base.
     */
    private final String basePath;

    private ServedJars(Path serveRoot, String basePath) {
        this.serveRoot = serveRoot;
        this.basePath = basePath;
    }

    /**
     * A resolver for one runner of one server.
     *
     * @param serveRoot the directory the HTTP root maps onto, canonical
     * @param runnerUri the IRI this runner was asked for, i.e. the one the
     *                  orchestrator resolved the jar URLs against
     * @return the resolver, or {@link JarResolver#NONE} when nothing can be
     *         mapped: no serving root, or a runner IRI that is not an
     *         {@code http(s)} URL with a path
     */
    public static JarResolver of(Path serveRoot, String runnerUri) {
        if (serveRoot == null || runnerUri == null) {
            return JarResolver.NONE;
        }

        Path real;
        try {
            real = serveRoot.toRealPath();
        } catch (IOException e) {
            LOGGER.warning("Not mapping jars onto " + serveRoot + ": " + e);
            return JarResolver.NONE;
        }

        URI uri = parse(runnerUri);
        if (uri == null || !isHttp(uri.getScheme())) {
            // A runner named urn:… or anything else that is not a URL under this
            // server's base: no jar URL can be relative to it
            return JarResolver.NONE;
        }

        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return JarResolver.NONE;
        }

        return new ServedJars(real, path.substring(0, path.lastIndexOf('/') + 1));
    }

    /**
     * The served file a jar URL names, when it names one.
     *
     * Every step can say no, and every no means "download it the ordinary way":
     * a URL that is not {@code http(s)}, a path that does not sit under the
     * runner's own, a file that resolves outside the serving root — a {@code ..}
     * or a symlink pointing out of the tree — or one that is not there at all.
     *
     * @param jarUrl the URL out of the processor's description
     * @return the file to load, or empty
     */
    @Override
    public Optional<Path> resolve(String jarUrl) {
        URI uri = parse(jarUrl);
        if (uri == null || !isHttp(uri.getScheme())) {
            return Optional.empty();
        }

        // Decoded by URI itself, so a %2e%2e cannot smuggle a segment past the
        // containment check below in an encoding this class does not undo
        String path = uri.getPath();
        if (path == null || !path.startsWith(this.basePath)) {
            return Optional.empty();
        }

        String relative = path.substring(this.basePath.length());
        if (relative.isEmpty()) {
            return Optional.empty();
        }

        Path candidate;
        try {
            candidate = this.serveRoot.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            LOGGER.fine("Not mapping " + jarUrl + " onto a file: " + e);
            return Optional.empty();
        }

        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            // Not there, or not readable: whoever serves it can hand it out
            LOGGER.fine("Not mapping " + jarUrl + " onto " + candidate + ": " + e);
            return Optional.empty();
        }

        // Canonical on both sides, so neither a `..` segment nor a symlink out of
        // the tree can reach a file this server does not serve
        if (!real.startsWith(this.serveRoot) || !Files.isRegularFile(real)) {
            LOGGER.fine("Not mapping " + jarUrl + " onto " + real + ": outside " + this.serveRoot);
            return Optional.empty();
        }

        return Optional.of(real);
    }

    /** @return the directory jars are looked up under */
    public Path serveRoot() {
        return this.serveRoot;
    }

    /** @return the URL path the runner's IRI sits in, with a trailing slash */
    public String basePath() {
        return this.basePath;
    }

    private static URI parse(String text) {
        try {
            return new URI(text);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean isHttp(String scheme) {
        if (scheme == null) {
            return false;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        return lower.equals("http") || lower.equals("https");
    }
}
