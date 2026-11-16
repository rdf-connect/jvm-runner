package io.github.rdfc;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Turns the jar URL in a processor's description into a file this machine
 * already has, when it happens to have it.
 *
 * The orchestrator names a processor's jar with the URL it read that processor's
 * description from, so in server mode the jar of a processor this very server
 * advertises is a URL pointing back at this very server's HTTP port. Downloading
 * it would be a runner fetching a file it is sitting on — and it would not even
 * work: the HTTP side serves only whitelisted Turtle, so the jar next to it
 * comes back as a 403.
 *
 * The CLI knows nothing about served files and passes {@link #NONE}, so nothing
 * about its jar handling changes: every URL takes the download path it always
 * took.
 *
 * <b>Implementations must not throw and must not block for long.</b> This is
 * called while a processor is being constructed; the runner guards the call and
 * falls back on downloading, but a resolver that relies on that guard is a
 * resolver that resolves nothing.
 */
public interface JarResolver {
    /**
     * The local file a jar URL stands for.
     *
     * @param jarUrl the URL out of the processor's description
     * @return the file on this machine, or empty when this resolver cannot say —
     *         in which case the runner downloads the jar as it always did
     */
    Optional<Path> resolve(String jarUrl);

    /** Resolves nothing, so every jar is fetched the ordinary way. */
    JarResolver NONE = jarUrl -> Optional.empty();
}
