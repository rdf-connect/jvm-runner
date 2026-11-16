package io.github.rdfc.server;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * The RDF-Connect terms this server reads and writes.
 *
 * One place for them, because the same IRIs are used on both sides: the server
 * configuration is parsed with them and the index document is generated with
 * them, and a typo in a namespace string that only appears in one of the two is
 * invisible until an orchestrator quietly finds nothing.
 */
public final class Vocabulary {
    /** The RDF-Connect namespace, the one the orchestrator and every runner use. */
    public static final String RDFC = "https://w3id.org/rdf-connect#";

    private static final ValueFactory FACTORY = SimpleValueFactory.getInstance();

    /** Type of the subject that carries this server's configuration. */
    public static final IRI JVM_RUNNER_SERVER = FACTORY.createIRI(RDFC, "JvmRunnerServer");

    /** Port the HTTP server (index, files, dashboard) listens on. */
    public static final IRI HTTP_PORT = FACTORY.createIRI(RDFC, "httpPort");

    /** Port the orchestrator opens its TCP/gRPC connections on. */
    public static final IRI GRPC_PORT = FACTORY.createIRI(RDFC, "grpcPort");

    /** Host name this server advertises itself under in the index document. */
    public static final IRI HOSTNAME = FACTORY.createIRI(RDFC, "hostname");

    /** How many finished runners the dashboard keeps showing. */
    public static final IRI HISTORY_SIZE = FACTORY.createIRI(RDFC, "historySize");

    /** A Turtle file declaring processors this server hosts, repeatable. */
    public static final IRI PROCESSOR_CONFIG = FACTORY.createIRI(RDFC, "processorConfig");

    /** Marks a processor as implemented on the JVM; the index is built around it. */
    public static final IRI JAVA_IMPLEMENTATION_OF = FACTORY.createIRI(RDFC, "javaImplementationOf");

    /** Type of a runner an orchestrator reaches over a plain TCP connection. */
    public static final IRI TCP_RUNNER = FACTORY.createIRI(RDFC, "TcpRunner");

    /** Says which processors a runner can execute. */
    public static final IRI HANDLES_SUBJECTS_OF = FACTORY.createIRI(RDFC, "handlesSubjectsOf");

    /** The {@code host:port} an orchestrator connects to. */
    public static final IRI GRPC = FACTORY.createIRI(RDFC, "grpc");

    /** Type every advertised processor gets. */
    public static final IRI PROCESSOR = FACTORY.createIRI(RDFC, "Processor");

    private Vocabulary() {
    }
}
