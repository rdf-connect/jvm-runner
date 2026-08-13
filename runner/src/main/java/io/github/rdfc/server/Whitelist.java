package io.github.rdfc.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * The set of files the HTTP server is allowed to hand out.
 *
 * An orchestrator that fetches a processor description needs the shapes and
 * ontologies that description imports as well, so serving only the configured
 * catalogues is not enough. Rather than serving a whole directory tree — which
 * would put every file that happens to sit next to a catalogue on the public
 * side of an HTTP server — the set is derived from the documents themselves:
 * start at the catalogues and follow {@code <thisDocument> owl:imports
 * <file:...>} transitively.
 *
 * The subject matters. Only what a document says about <em>itself</em> is
 * followed; an {@code owl:imports} on some other subject is a statement about
 * that other document, and a catalogue may not enlarge the served set by making
 * claims on behalf of files it does not own.
 *
 * Every path is canonicalized with {@code toRealPath}, and the HTTP handler has
 * to do the same before it looks a request up: membership is then decided on
 * what a path actually resolves to, so neither {@code ..} segments nor a
 * symlink pointing out of the tree can reach a file that is not in here.
 */
public final class Whitelist {
    private Whitelist() {
    }

    /**
     * Collects the files reachable from a set of catalogues.
     *
     * A file that cannot be parsed <b>stays in the set but is not followed</b>.
     * Those two halves are deliberate: the file was named by the operator, so
     * refusing to serve it would turn a syntax error in an unrelated import into a
     * 403 on the file that is fine; and a document nobody could parse has no
     * imports anybody can trust, so the walk stops there.
     *
     * An import that points at something other than a {@code file:} IRI, or at a
     * file that is not on this machine, is skipped with a warning — it is either
     * served by someone else or simply missing, and in both cases there is nothing
     * here to hand out.
     *
     * @param processorConfigs the catalogues to start from
     * @param log              where to report what is skipped and why
     * @return the canonical paths of every file that may be served, the roots
     *         included
     */
    public static Set<Path> build(Collection<Path> processorConfigs, Logger log) {
        Set<Path> whitelist = new LinkedHashSet<>();
        Deque<Path> todo = new ArrayDeque<>();

        for (Path root : processorConfigs) {
            Path real = canonical(root, log);
            if (real != null) {
                todo.add(real);
            }
        }

        while (!todo.isEmpty()) {
            Path file = todo.poll();
            // The visited set is the whitelist itself, so a cycle — A importing B
            // importing A — ends on the second visit instead of walking forever
            if (!whitelist.add(file)) {
                continue;
            }

            String document = file.toUri().toString();
            Model model;
            try (InputStream in = Files.newInputStream(file)) {
                model = Rio.parse(in, document, RDFFormat.TURTLE);
            } catch (IOException | RuntimeException e) {
                log.warning("Not following " + file + " while building the whitelist: " + e);
                continue;
            }

            IRI subject = SimpleValueFactory.getInstance().createIRI(document);
            for (Value imported : model.filter(subject, OWL.IMPORTS, null).objects()) {
                Path target = importedFile(imported, file, log);
                if (target != null && !whitelist.contains(target)) {
                    todo.add(target);
                }
            }
        }

        return Collections.unmodifiableSet(whitelist);
    }

    /**
     * The local file an {@code owl:imports} points at, or null when it points
     * somewhere this server cannot serve from.
     *
     * @param imported the imported IRI
     * @param source   the importing file, for the message
     * @param log      where to report what is skipped
     * @return the canonical path, or null
     */
    private static Path importedFile(Value imported, Path source, Logger log) {
        String iri = imported.stringValue();
        if (!iri.startsWith("file:")) {
            // http(s) imports are somebody else's to serve; this is the common case
            // for an ontology, so it is not worth a warning
            log.fine("Not following the non-file import " + iri + " of " + source);
            return null;
        }

        Path path;
        try {
            path = Paths.get(new URI(iri));
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            log.warning("Ignoring the unusable import " + iri + " of " + source + ": " + e);
            return null;
        }

        if (!Files.isRegularFile(path)) {
            log.warning("Ignoring the import " + iri + " of " + source + ": no such file");
            return null;
        }

        return canonical(path, log);
    }

    /**
     * The canonical path of a file, or null when it cannot be resolved.
     *
     * @param path the path to resolve
     * @param log  where to report a failure
     * @return the canonical path, or null
     */
    private static Path canonical(Path path, Logger log) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            log.warning("Leaving " + path + " out of the whitelist: " + e);
            return null;
        }
    }
}
