package io.github.rdfc.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.WriterConfig;
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings;

/**
 * Builds the Turtle document served at the HTTP root.
 *
 * The document is what an orchestrator reads to learn that this server exists:
 * it declares the {@code rdfc:TcpRunner} with the {@code host:port} to connect
 * to, the SHACL shape that says how a JVM processor is described, and one
 * entry per processor this server hosts, each pointing at the catalogue file
 * the description can be fetched from.
 *
 * Every IRI in it is absolute against a {@code base} — the URL the server was
 * actually reached on, taken from the request — rather than against a
 * configured address. A server behind a port mapping, a container or a reverse
 * proxy is addressed under a name it cannot know at startup, and an index that
 * hard-coded one would send the orchestrator to a host it cannot reach.
 *
 * The catalogues are parsed once, when this is constructed: that work does not
 * depend on the base, and the per-base cache below is keyed on the {@code Host}
 * header, so a cache miss is client-controlled and has to stay cheap — graph
 * assembly and serialization, no file I/O.
 */
public final class IndexGenerator {
    private static final Logger LOGGER = Logger.getLogger(IndexGenerator.class.getName());

    private static final ValueFactory FACTORY = SimpleValueFactory.getInstance();

    /**
     * How many rendered index documents are kept.
     *
     * The key is the {@code Host} header, which anybody who can reach the HTTP
     * port chooses freely: an unbounded cache would be a way to make this server
     * allocate without end.
     */
    static final int MAX_CACHED_BASES = 32;

    /** Local name of the runner this document advertises, under the base URL. */
    static final String RUNNER_NAME = "jvmRunner";

    /** The invariant part of every index, read from the bundled resource once. */
    private static final String PRELUDE = readPrelude();

    private final List<ProcessorDescription> descriptions;
    private final Path serveRoot;
    private final String hostname;
    private final int grpcPort;

    /**
     * Rendered documents per base URL, least recently used evicted first.
     *
     * Guarded by this generator's monitor — the HTTP server serves requests on
     * several threads and a LinkedHashMap in access order mutates on a read.
     */
    private final Map<String, String> cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return this.size() > MAX_CACHED_BASES;
        }
    };

    /**
     * Reads the processor catalogues and prepares to render the index.
     *
     * @param processorConfigs the catalogues, canonical paths
     * @param serveRoot        the directory the HTTP root maps onto
     * @param hostname         the host an orchestrator reaches the gRPC port on
     * @param grpcPort         the port it reaches it on
     */
    public IndexGenerator(Collection<Path> processorConfigs, Path serveRoot, String hostname, int grpcPort) {
        this.descriptions = Collections.unmodifiableList(extract(processorConfigs));
        this.serveRoot = serveRoot;
        this.hostname = hostname;
        this.grpcPort = grpcPort;
    }

    /**
     * One processor this server hosts, as read from a catalogue.
     */
    public static final class ProcessorDescription {
        private final String uri;
        private final String label;
        private final String comment;
        private final Path sourceFile;

        ProcessorDescription(String uri, String label, String comment, Path sourceFile) {
            this.uri = uri;
            this.label = label;
            this.comment = comment;
            this.sourceFile = sourceFile;
        }

        /** @return the processor's IRI */
        public String uri() {
            return this.uri;
        }

        /** @return its {@code rdfs:label}, if it has one */
        public Optional<String> label() {
            return Optional.ofNullable(this.label);
        }

        /** @return its {@code rdfs:comment}, if it has one */
        public Optional<String> comment() {
            return Optional.ofNullable(this.comment);
        }

        /** @return the catalogue file that declares it */
        public Path sourceFile() {
            return this.sourceFile;
        }
    }

    /**
     * The processors this generator will advertise. Visible for testing.
     *
     * @return them, in the order they were found
     */
    public List<ProcessorDescription> descriptions() {
        return this.descriptions;
    }

    /**
     * Renders the index document for one base URL.
     *
     * @param base the URL this server was reached on; a trailing slash is added
     *             when it is missing, so the relative paths below concatenate onto
     *             it correctly
     * @return the Turtle document
     */
    public synchronized String generate(String base) {
        String normalized = base.endsWith("/") ? base : base + "/";

        String cached = this.cache.get(normalized);
        if (cached != null) {
            return cached;
        }

        String rendered = render(normalized);
        this.cache.put(normalized, rendered);
        return rendered;
    }

    /**
     * How many base URLs are currently cached. Visible for testing.
     *
     * @return the size of the cache
     */
    synchronized int cachedBases() {
        return this.cache.size();
    }

    /**
     * Assembles and serializes the document for one base.
     *
     * @param base the base URL, with a trailing slash
     * @return the Turtle document
     */
    private String render(String base) {
        // Parsed per base rather than once: the shape contains a blank node, and the
        // prelude is small — cheaper than deep-copying a model and rewriting nothing.
        final Model model;
        try {
            model = Rio.parse(new ByteArrayInputStream(PRELUDE.getBytes(StandardCharsets.UTF_8)), base,
                    RDFFormat.TURTLE);
        } catch (IOException e) {
            // The prelude is a resource in this jar; if it cannot be parsed the build
            // is broken, not the request
            throw new UncheckedIOException("The bundled index prelude cannot be parsed", e);
        }

        model.setNamespace("rdfc", Vocabulary.RDFC);
        model.setNamespace("rdfs", RDFS.NAMESPACE);

        IRI runner = FACTORY.createIRI(base + RUNNER_NAME);
        model.add(runner, RDF.TYPE, Vocabulary.TCP_RUNNER);
        model.add(runner, Vocabulary.HANDLES_SUBJECTS_OF, Vocabulary.JAVA_IMPLEMENTATION_OF);
        model.add(runner, Vocabulary.GRPC, FACTORY.createLiteral(this.hostname + ":" + this.grpcPort));

        for (ProcessorDescription description : this.descriptions) {
            String relative = relativize(description.sourceFile());
            if (relative == null) {
                // Advertising it anyway would put an IRI with '..' in it in the document.
                // Clients normalize that away before sending, so they would ask for a
                // path outside the serving root, which the file handler refuses — an
                // entry that can only ever produce a confusing 403.
                LOGGER.warning("Processor " + description.uri() + " is declared in " + description.sourceFile()
                        + ", outside the serving root " + this.serveRoot + "; it is not advertised");
                continue;
            }

            IRI subject = FACTORY.createIRI(description.uri());
            model.add(subject, RDF.TYPE, Vocabulary.PROCESSOR);
            description.label().ifPresent(label -> model.add(subject, RDFS.LABEL, FACTORY.createLiteral(label)));
            description.comment().ifPresent(
                    comment -> model.add(subject, RDFS.COMMENT, FACTORY.createLiteral(comment)));
            model.add(subject, RDFS.ISDEFINEDBY, FACTORY.createIRI(base + relative));
        }

        StringWriter out = new StringWriter();
        WriterConfig config = new WriterConfig();
        config.set(BasicWriterSettings.PRETTY_PRINT, true);
        config.set(BasicWriterSettings.INLINE_BLANK_NODES, true);
        Rio.write(model, out, RDFFormat.TURTLE, config);
        return out.toString();
    }

    /**
     * The path of a catalogue relative to the serving root, or null when it lies
     * outside it.
     *
     * @param file the catalogue, a canonical path
     * @return the relative path with {@code /} separators, or null
     */
    private String relativize(Path file) {
        Path relative;
        try {
            relative = this.serveRoot.relativize(file);
        } catch (IllegalArgumentException e) {
            // Different roots; on Windows that is possible, and it means "outside"
            return null;
        }

        String text = relative.toString().replace(File.separatorChar, '/');
        if (text.isEmpty() || text.equals("..") || text.startsWith("../")) {
            return null;
        }
        return text;
    }

    /**
     * Reads every processor a set of catalogues declares.
     *
     * A processor is a subject of {@code rdfc:javaImplementationOf} — the property
     * that says "this is the JVM implementation of that processor" — which is
     * exactly what this runner can execute, and what the advertised
     * {@code rdfc:handlesSubjectsOf} claims.
     *
     * @param processorConfigs the catalogues
     * @return the processors, deduplicated by IRI, first declaration winning
     */
    private static List<ProcessorDescription> extract(Collection<Path> processorConfigs) {
        List<ProcessorDescription> descriptions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Path file : processorConfigs) {
            Model model;
            try (InputStream in = Files.newInputStream(file)) {
                model = Rio.parse(in, file.toUri().toString(), RDFFormat.TURTLE);
            } catch (IOException | RuntimeException e) {
                LOGGER.warning("Skipping " + file + " while extracting processor descriptions: " + e);
                continue;
            }

            List<Resource> subjects = new ArrayList<>(
                    model.filter(null, Vocabulary.JAVA_IMPLEMENTATION_OF, null).subjects());
            // Sorted, so two runs of the same server advertise the same document
            subjects.sort((left, right) -> left.stringValue().compareTo(right.stringValue()));

            for (Resource subject : subjects) {
                String uri = subject.stringValue();
                if (!seen.add(uri)) {
                    continue;
                }
                descriptions.add(new ProcessorDescription(uri, string(model, subject, RDFS.LABEL),
                        string(model, subject, RDFS.COMMENT), file));
            }
        }

        return descriptions;
    }

    /**
     * The lexical value of a property of a subject, or null when it has none.
     *
     * @param model    the parsed catalogue
     * @param subject  the processor
     * @param property the property to read
     * @return the value, or null
     */
    private static String string(Model model, Resource subject, IRI property) {
        for (Value value : model.filter(subject, property, null).objects()) {
            return value.stringValue();
        }
        return null;
    }

    /**
     * Reads the bundled prelude.
     *
     * @return its text
     */
    private static String readPrelude() {
        try (InputStream in = IndexGenerator.class.getResourceAsStream("index_prelude.ttl")) {
            if (in == null) {
                throw new IllegalStateException("index_prelude.ttl is missing from the runner jar");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the bundled index prelude", e);
        }
    }
}
