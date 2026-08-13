package io.github.rdfc.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * The Turtle configuration of a JVM runner server.
 *
 * The document declares one subject of type {@code rdfc:JvmRunnerServer} —
 * conventionally the document itself, {@code <>} — carrying the two ports, the
 * host name to advertise, how much history the dashboard keeps, and the
 * processor catalogues to serve:
 *
 * <pre>
 * &lt;&gt; a rdfc:JvmRunnerServer;
 *   rdfc:httpPort 3000;
 *   rdfc:grpcPort 50051;
 *   rdfc:hostname "localhost";
 *   rdfc:processorConfig &lt;./processors/echo.ttl&gt;.
 * </pre>
 *
 * Everything is optional; the defaults in this class are the same ones the
 * js- and py-runners use.
 *
 * <b>Relative IRIs resolve against the configuration document</b>, not against
 * the working directory: where the server happens to be started from is not
 * something a configuration file can know, and the same file has to work when
 * it is started from a service manager, a container, or a shell in a random
 * directory. Rio does that resolution while parsing, because the document is
 * parsed with its own {@code file:} URI as the base.
 */
public final class ServerConfig {
    private static final Logger LOGGER = Logger.getLogger(ServerConfig.class.getName());

    /** Port the HTTP server listens on when the configuration says nothing. */
    public static final int DEFAULT_HTTP_PORT = 3000;

    /** Port the orchestrator's TCP connections are accepted on by default. */
    public static final int DEFAULT_GRPC_PORT = 50051;

    /** Host name advertised in the index document by default. */
    public static final String DEFAULT_HOSTNAME = "localhost";

    /** How many finished runners the dashboard keeps by default. */
    public static final int DEFAULT_HISTORY_SIZE = 5;

    private final int httpPort;
    private final int grpcPort;
    private final String hostname;
    private final int historySize;
    private final List<Path> processorConfigs;
    private final Path configPath;
    private final Path configDir;

    private ServerConfig(int httpPort, int grpcPort, String hostname, int historySize,
            List<Path> processorConfigs, Path configPath) {
        this.httpPort = httpPort;
        this.grpcPort = grpcPort;
        this.hostname = hostname;
        this.historySize = historySize;
        this.processorConfigs = Collections.unmodifiableList(new ArrayList<>(processorConfigs));
        this.configPath = configPath;
        this.configDir = configPath.getParent();
    }

    /**
     * Reads a server configuration file.
     *
     * @param ttl the Turtle file to read
     * @return the configuration it declares
     * @throws ConfigException when the file cannot be read or parsed, when it
     *                         declares no {@code rdfc:JvmRunnerServer} or more than
     *                         one, when a port is not a number, or when a
     *                         {@code rdfc:processorConfig} does not point at a file
     *                         on this machine
     */
    public static ServerConfig parse(Path ttl) throws ConfigException {
        Path real;
        try {
            real = ttl.toRealPath();
        } catch (NoSuchFileException e) {
            throw new ConfigException("Server config not found: " + ttl.toAbsolutePath(), e);
        } catch (IOException e) {
            throw new ConfigException("Cannot read the server config " + ttl.toAbsolutePath() + ": " + e, e);
        }

        // The document's own URI is the base: `rdfc:processorConfig <./x.ttl>` has to
        // mean "next to this file", and only a base that points at this file gives
        // that. It doubles as the subject IRI of the conventional `<>` form.
        String publicId = real.toUri().toString();

        Model model;
        try (InputStream in = Files.newInputStream(real)) {
            model = Rio.parse(in, publicId, RDFFormat.TURTLE);
        } catch (IOException | RuntimeException e) {
            throw new ConfigException("Failed to parse server config " + real + ": " + e, e);
        }

        Resource subject = serverSubject(model, real);

        int httpPort = asInt(single(model, subject, Vocabulary.HTTP_PORT), "httpPort", DEFAULT_HTTP_PORT, real);
        int grpcPort = asInt(single(model, subject, Vocabulary.GRPC_PORT), "grpcPort", DEFAULT_GRPC_PORT, real);
        int historySize = asInt(single(model, subject, Vocabulary.HISTORY_SIZE), "historySize",
                DEFAULT_HISTORY_SIZE, real);
        String hostname = single(model, subject, Vocabulary.HOSTNAME)
                .map(Value::stringValue)
                .orElse(DEFAULT_HOSTNAME);

        Path configDir = real.getParent();
        List<Path> processorConfigs = new ArrayList<>();
        for (Value value : model.filter(subject, Vocabulary.PROCESSOR_CONFIG, null).objects()) {
            processorConfigs.add(toLocalPath(value, configDir, real));
        }
        // Sorted, so the order of the served catalogue does not depend on the order
        // the parser happened to hand the triples back in
        Collections.sort(processorConfigs);

        if (processorConfigs.isEmpty()) {
            // Not an error: a server with no catalogue still accepts runners, it just
            // advertises nothing. Worth a line, because it is almost always a mistake.
            LOGGER.warning("No rdfc:processorConfig in " + real + ": this server will advertise no processors");
        }

        return new ServerConfig(httpPort, grpcPort, hostname, historySize, processorConfigs, real);
    }

    /**
     * Finds the one subject that carries the configuration.
     *
     * More than one is refused rather than silently resolved: picking the "first"
     * of an unordered set means the server's ports depend on the parser's hashing,
     * and a file with two configurations in it is a mistake somebody wants to hear
     * about.
     *
     * @param model  the parsed document
     * @param source path of that document, for the message
     * @return the subject
     * @throws ConfigException when there is not exactly one
     */
    private static Resource serverSubject(Model model, Path source) throws ConfigException {
        List<Resource> subjects = new ArrayList<>(model.filter(null, RDF.TYPE, Vocabulary.JVM_RUNNER_SERVER)
                .subjects());

        if (subjects.isEmpty()) {
            throw new ConfigException("No rdfc:JvmRunnerServer found in " + source);
        }
        if (subjects.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Resource subject : subjects) {
                names.add(subject.stringValue());
            }
            Collections.sort(names);
            throw new ConfigException("More than one rdfc:JvmRunnerServer in " + source + ": " + String.join(", ",
                    names));
        }

        return subjects.get(0);
    }

    /**
     * The single object of a property, or empty when the property is absent.
     *
     * @param model    the parsed document
     * @param subject  the configuration subject
     * @param property the property to look up
     * @return the value, or empty
     */
    private static Optional<Value> single(Model model, Resource subject, IRI property) {
        return model.filter(subject, property, null).objects().stream().findFirst();
    }

    /**
     * Reads a configured value as an integer.
     *
     * The lexical form is what counts, not the datatype: {@code 3000},
     * {@code "3000"} and {@code "3000"^^xsd:integer} all say the same thing, and a
     * configuration file is not the place to be pedantic about that. Anything that
     * is not a number is reported with the property name and the value, so the line
     * to fix is obvious.
     *
     * @param value    the configured value, or empty
     * @param property local name of the property, for the message
     * @param fallback what an absent value means
     * @param source   path of the document, for the message
     * @return the number
     * @throws ConfigException when the value is not an integer
     */
    private static int asInt(Optional<Value> value, String property, int fallback, Path source)
            throws ConfigException {
        if (!value.isPresent()) {
            return fallback;
        }

        String text = value.get().stringValue();
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid rdfc:" + property + " in " + source
                    + ": expected an integer, got '" + text + "'", e);
        }
    }

    /**
     * Turns a configured processor catalogue reference into a path on this machine.
     *
     * Two forms are accepted, and they cover what a Turtle file can reasonably say:
     * a {@code file:} IRI — which is what a relative IRI such as
     * {@code <./processors.ttl>} has already become, resolved against the document
     * — and a plain string literal, which is resolved against the directory of the
     * configuration document. Any other scheme is refused: this server serves files
     * off its own disk, and an {@code http:} catalogue would have to be fetched,
     * which is a different feature and not one to fake by treating the URL as a
     * file name.
     *
     * @param value     the configured value
     * @param configDir directory of the configuration document
     * @param source    path of that document, for the message
     * @return the canonical path of an existing file
     * @throws ConfigException when the value is not a local reference, or the file
     *                         is not there
     */
    private static Path toLocalPath(Value value, Path configDir, Path source) throws ConfigException {
        Path path;

        if (value instanceof IRI) {
            String iri = value.stringValue();
            URI uri;
            try {
                uri = new URI(iri);
            } catch (URISyntaxException e) {
                throw new ConfigException("Invalid rdfc:processorConfig in " + source + ": " + iri, e);
            }

            String scheme = uri.getScheme();
            if (scheme == null) {
                path = configDir.resolve(uri.getPath());
            } else if ("file".equalsIgnoreCase(scheme)) {
                try {
                    path = Paths.get(uri);
                } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                    throw new ConfigException("Invalid rdfc:processorConfig in " + source + ": " + iri, e);
                }
            } else {
                throw new ConfigException("Invalid rdfc:processorConfig in " + source + ": " + iri
                        + " is not a file on this machine (only file: IRIs and relative paths are served)");
            }
        } else {
            // A plain literal such as "processors/echo.ttl": relative to the document,
            // for the same reason a relative IRI is
            path = configDir.resolve(value.stringValue());
        }

        try {
            // Canonical, because everything downstream — the whitelist, the serving
            // root, the HTTP handler's containment check — compares realpaths
            return path.toRealPath();
        } catch (NoSuchFileException e) {
            throw new ConfigException("rdfc:processorConfig in " + source + " does not exist: " + path, e);
        } catch (IOException e) {
            throw new ConfigException("Cannot read the rdfc:processorConfig " + path + " of " + source + ": " + e, e);
        }
    }

    /** @return the port the HTTP server listens on */
    public int httpPort() {
        return this.httpPort;
    }

    /** @return the port orchestrator connections are accepted on */
    public int grpcPort() {
        return this.grpcPort;
    }

    /** @return the host name advertised in the index document */
    public String hostname() {
        return this.hostname;
    }

    /** @return how many finished runners to keep, -1 for all of them */
    public int historySize() {
        return this.historySize;
    }

    /** @return the processor catalogues, canonical and sorted */
    public List<Path> processorConfigs() {
        return this.processorConfigs;
    }

    /** @return the canonical path of the configuration document */
    public Path configPath() {
        return this.configPath;
    }

    /** @return the directory the configuration document lives in */
    public Path configDir() {
        return this.configDir;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "ServerConfig{httpPort=%d, grpcPort=%d, hostname=%s, historySize=%d, processorConfigs=%s, configPath=%s}",
                this.httpPort, this.grpcPort, this.hostname, this.historySize, this.processorConfigs,
                this.configPath);
    }
}
