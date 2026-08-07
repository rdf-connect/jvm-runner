package io.github.rdfc.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Where the HTTP server's root maps onto.
 *
 * Not the process' working directory — that is wherever the server happened to
 * be started from and has nothing to do with the files it serves. The root is
 * the deepest directory that contains the configuration document <em>and</em>
 * every whitelisted file, because the index document advertises each processor
 * catalogue as a path relative to this root: a root that does not contain a
 * served file would advertise an IRI with {@code ..} in it, which RFC 3986
 * clients normalize away before sending, so they end up asking for a path this
 * server then refuses to serve.
 */
public final class ServeRoot {
    private ServeRoot() {
    }

    /**
     * The root to serve a configuration and its whitelist from.
     *
     * @param configDir directory of the server configuration document
     * @param whitelist every file that may be served, canonical
     * @return the directory the HTTP root maps onto
     */
    public static Path of(Path configDir, Collection<Path> whitelist) {
        if (whitelist.isEmpty()) {
            return configDir;
        }

        List<Path> all = new ArrayList<>(whitelist.size() + 1);
        all.add(configDir);
        all.addAll(whitelist);
        return commonPath(all);
    }

    /**
     * The deepest path that is an ancestor of, or equal to, every given path.
     *
     * Files may be passed in as they are: a file contributes its own name as a
     * component, and since the configuration <em>directory</em> is always part of
     * the input in practice, the answer is a directory.
     *
     * @param paths the paths, all absolute and on the same file system root
     * @return their common prefix
     * @throws IllegalArgumentException when the collection is empty, when a path is
     *                                  relative, or when they do not share a root
     */
    public static Path commonPath(Collection<Path> paths) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Cannot take the common path of nothing");
        }

        Path root = null;
        List<String> common = null;

        for (Path path : paths) {
            Path candidate = path.toAbsolutePath().normalize();
            Path candidateRoot = candidate.getRoot();
            if (candidateRoot == null) {
                throw new IllegalArgumentException("Not an absolute path: " + path);
            }

            if (root == null) {
                root = candidateRoot;
                common = components(candidate);
                continue;
            }

            if (!root.equals(candidateRoot)) {
                // Only reachable on Windows, where C:\ and D:\ have no common ancestor
                throw new IllegalArgumentException("Paths on different roots: " + root + " and " + candidateRoot);
            }

            List<String> next = components(candidate);
            int shared = 0;
            int limit = Math.min(common.size(), next.size());
            while (shared < limit && common.get(shared).equals(next.get(shared))) {
                shared++;
            }
            common = common.subList(0, shared);
        }

        Path result = root;
        for (String name : common) {
            result = result.resolve(name);
        }
        return result;
    }

    /**
     * The name components of a path, root excluded.
     *
     * @param path an absolute, normalized path
     * @return its components, outermost first
     */
    private static List<String> components(Path path) {
        List<String> names = new ArrayList<>(path.getNameCount());
        for (Path name : path) {
            names.add(name.toString());
        }
        return names;
    }
}
