# JVM Runner for RDF Connect

The **JvmRunner** executes processors implemented in the Java Virtual Machine (JVM).  
It allows you to integrate custom Java (or Kotlin, Scala, etc.) processors into an RDF-Connect streaming pipeline by providing a JAR and a class name.

## Overview

- **Runner type**: `rdfc:JvmRunner` (imported automatically).  
- **Processor definition**: Each processor must declare a JAR and a fully qualified Java class via `rdfc:javaImplementationOf`.  
- **Implementation requirement**: All processors extend the abstract class `io.github.rdfc.Processor<T>` and provide lifecycle methods.  
- **Packaging requirement**: Processors must include a descriptor RDF file (e.g., `index.ttl`) inside their JAR.  
- **Distribution option**: You can publish your processor JAR with [JitPack](https://jitpack.io) so it can be included as a dependency in pipelines.  
- **Deployment options**: The orchestrator can start the runner as a subprocess, or connect to it as a [remote runner server](#remote-runner-server) — on another machine, or [in Docker](#docker).  


## Using Processors in a Pipeline

To use your JVM processor in a pipeline:

1. Import the JvmRunner:

```turtle
<> owl:imports <https://javadoc.jitpack.io/com/github/rdf-connect/jvm-runner/runner/master-SNAPSHOT/runner-master-SNAPSHOT-index.jar>.
```

2. Link your processor to the runner:

```turtle
@prefix rdfc: <https://w3id.org/rdf-connect#>.
<> a rdfc:Pipeline;
   rdfc:consistsOf [
     rdfc:instantiates rdfc:JvmRunner;
     rdfc:processor <myProcessor>;
   ].
<myProcessor> a rdfc:TestProcessor.
```

Make sure you also import the processor. Check the documentation of your processor on how to install it.


## Remote runner server

Instead of letting the orchestrator spawn the runner as a local subprocess (`rdfc:JvmRunner` with `rdfc:command`),
the runner can be hosted as a long-running server — on another machine, or in Docker.
The server serves the runner definition and its processor descriptions over HTTP,
and instantiates a runner for every incoming orchestrator connection.

> [!WARNING]
> **Both ports are unauthenticated, and the gRPC port executes what a connecting orchestrator asks for.**
> Anything that can connect to `rdfc:grpcPort` can have the server load and run arbitrary JVM classes —
> from the jars it serves, or from jars it is told to download — and the HTTP port publicly serves the
> configured processor description files. This is the same posture as the js- and py-runners: it is meant
> for networks where every host is trusted. Bind or firewall both ports accordingly — a private Docker
> network, a VPN, or a loopback-only deployment — and never expose them to the public internet.

### Starting the server

The fat jar has two modes. The plain client mode, which the orchestrator's `CommandRunner` uses when it
starts the runner itself, and the `server` subcommand:

```shell
# client mode: dial an orchestrator and serve one runner IRI
java -jar runner-0.0.4-all.jar <orchestrator-host:port> <runner-uri>

# server mode: wait for orchestrators to connect
java -jar runner-0.0.4-all.jar server server.ttl
```

Create a Turtle configuration for the server:

```turtle
@prefix rdfc: <https://w3id.org/rdf-connect#>.

<> a rdfc:JvmRunnerServer;
  rdfc:httpPort 3000;                              # HTTP: index, processor descriptions, /health, /api/state, /dashboard
  rdfc:grpcPort 50051;                             # TCP: orchestrator-initiated runner connections
  rdfc:hostname "localhost";                       # the host the orchestrator must dial to reach rdfc:grpcPort
  rdfc:historySize 5;                              # finished runs kept on the dashboard (-1 keeps all, 0 keeps none)
  rdfc:processorConfig <./processors/echo.ttl>,    # one or more processor description files to serve
    <./processors/log.ttl>.
```

| Property               | Default       | Meaning                                                                                 |
|------------------------|---------------|-----------------------------------------------------------------------------------------|
| `rdfc:httpPort`        | `3000`        | Port serving the index, the whitelisted description files and the introspection endpoints. |
| `rdfc:grpcPort`        | `50051`       | Port orchestrator connections are accepted on. Advertised in the index as `rdfc:grpc "<hostname>:<grpcPort>"`. |
| `rdfc:hostname`        | `"localhost"` | The host advertised in that address. Must be resolvable **by the orchestrator**.          |
| `rdfc:historySize`     | `5`           | Finished runs kept on the dashboard; `-1` keeps all of them, `0` keeps none.               |
| `rdfc:processorConfig` | none          | Repeatable. A Turtle file describing processors this server hosts.                        |

Everything is optional. Relative IRIs — including every `rdfc:processorConfig` — resolve against the
**configuration document**, not against the working directory, so the same file works whether the server is
started from a shell, a service manager or a container. A `rdfc:processorConfig` has to be a file on this
machine (a relative path or a `file:` IRI); an `http:` one is refused. A server with no
`rdfc:processorConfig` still accepts runners, it just advertises no processors, and says so on startup.

The HTTP root maps onto the **directory of the configuration document**, and never anything above it. What
is served out of it is what the processor descriptions name: each `rdfc:processorConfig` and, transitively,
every `owl:imports <file:…>` they declare about themselves. An imported file that sits outside that
directory cannot be addressed under the root, so it is not advertised and not served — the server says so
once, at startup. Keep the shapes and ontologies your descriptions import under the configuration directory.

Ports are refused rather than guessed at: a port that is already in use ends the process with exit code `1`
and a line naming the property to change. Being called wrongly exits `2`.

`rdfc:hostname` is advertised verbatim, so an IPv6 address must be written with brackets
(`rdfc:hostname "[::1]"`), as the orchestrator's address parser expects them.

### Using the server from a pipeline

A pipeline uses the remote runner by importing the runner definition and the processor descriptions from
the server, and instantiating the served runner — which is always `<base>jvmRunner`:

```turtle
@prefix owl: <http://www.w3.org/2002/07/owl#>.
@prefix rdfc: <https://w3id.org/rdf-connect#>.

<> owl:imports <http://localhost:3000/>,                        # the runner definition
  <http://localhost:3000/processors/send.ttl>,                  # the processor descriptions
  <http://localhost:3000/processors/log.ttl>.

<> a rdfc:Pipeline;
  rdfc:consistsOf [
    rdfc:processor <sendProc>, <logProc>;
    rdfc:instantiates <http://localhost:3000/jvmRunner>;
  ].
```

The index the server generates describes that runner as `a rdfc:TcpRunner` with
`rdfc:grpc "<hostname>:<grpcPort>"` — the address built from the `rdfc:hostname` and `rdfc:grpcPort` of the
server configuration. The orchestrator dials exactly that address over plain TCP, writes the runner IRI
followed by a newline, and then **reverse-upgrades** the socket: it treats its own end as an incoming gRPC
connection. The server instantiates a runner that speaks the regular gRPC protocol over that same
connection — the runner never dials the orchestrator, so only the runner's ports need to be reachable.
**The host in the runner IRI plays no part in connectivity; it only says where the configuration was
imported from. It is `rdfc:hostname` that must be resolvable from the orchestrator.**

Every other IRI in the index is built from the address the request actually came in on (the `Host` header),
so a server behind a port mapping or a reverse proxy still hands out URLs the orchestrator can fetch.

### Served jars

A processor description served by this server names its jar relative to itself (`rdfc:jar <../my-processor.jar>`).
The orchestrator resolves that against the URL it fetched the description from and hands the runner an
absolute URL such as `http://localhost:3000/my-processor.jar` — which the HTTP side would refuse, because it
serves whitelisted Turtle and nothing else. So the runner **maps such a URL back onto the file on its own
disk**, under the directory holding the server configuration, instead of downloading it. Only URLs ending in
`.jar` that resolve, canonically, to a regular file inside that directory are mapped; everything else is
downloaded the ordinary way. Every substitution is logged.

The practical consequence: put the processor jars next to (or under) `server.ttl` and reference them
relatively from the description files.

### Introspection endpoints

Next to the served description files, the HTTP port answers:

| Endpoint      | What it serves                                                                 |
|---------------|--------------------------------------------------------------------------------|
| `/`           | The generated index: the `rdfc:TcpRunner` and every processor this server hosts. |
| `/health`     | `{"status":"ok","activeConnections":N}`.                                         |
| `/api/state`  | Per-runner status and channel statistics as JSON.                                |
| `/dashboard`  | A live HTML view of the same.                                                    |

The last two show the runners that are currently connected plus the last `rdfc:historySize` finished ones,
so a pipeline run stays visible after it completed. At most 32 orchestrator connections are served at once;
further ones are refused and logged.

### Logging

The server's own log verbosity comes from the `LOG_LEVEL` environment variable. The accepted names are the
orchestrator's, case-insensitive: `error`, `warn` (or `warning`), `info`, `http`, `verbose`, `debug` and
`silly`. Anything else, or nothing at all, means `info`. `verbose` and `debug` both open up the same range;
at those levels every served HTTP request is logged too, while gRPC's and Netty's own chatter is kept at
`info` so the interesting lines remain findable. This is independent of the pipeline logs, which are
forwarded to the orchestrator.

### A runnable example

[`tests/e2e`](tests/e2e) is a complete `send -> echo -> log` pipeline driven by the real orchestrator against
a real server; it doubles as the project's end-to-end test. `./tests/e2e/run-e2e.sh` builds everything,
starts the server, runs the pipeline and asserts on the result.


## Docker

The repository ships a [`Dockerfile`](Dockerfile) that builds the fat jar and packages the runner server.
Its entrypoint is already `java -jar /app/runner.jar server`, with `/config/server.ttl` as the default
argument, and `/config` as the working directory — so the image expects a configuration directory mounted
there, holding `server.ttl`, the processor description files **and** the processor jars they name:

```shell
docker build -t rdfc/jvm-runner .
docker run -p 3000:3000 -p 4001:4001 -v ./tests/e2e:/config:ro rdfc/jvm-runner
```

Or with the compose example, [`tests/e2e/docker-compose.yml`](tests/e2e/docker-compose.yml):

```shell
./gradlew :test-processor:jar
cp test-processor/build/libs/test-processor.jar tests/e2e/   # served out of /config
docker compose -f tests/e2e/docker-compose.yml up --build
```

Two ways to get processor jars into the container:

* **mount them** under `/config` and reference them relatively from the description files
  (`rdfc:jar <../my-processor.jar>`), which is what the end-to-end bed does — the runner then loads them off
  the mount instead of downloading them (see [Served jars](#served-jars)); or
* **extend the image** for a real deployment, so the jars are part of it rather than of the host:

  ```dockerfile
  FROM rdfc/jvm-runner
  COPY my-processor.jar server.ttl processors.ttl /config/
  ```

The image `EXPOSE`s `3000` and `50051`, but that is documentation only: what a container actually listens on
is whatever the mounted `server.ttl` says, and publishing those ports is the `-p`/`ports:` of whoever runs the
image. The end-to-end bed uses `rdfc:grpcPort 4001` and publishes `4001:4001`, because the orchestrator binds
its *own* gRPC server on `50051` by default.

Both published ports are unauthenticated (see the warning above): prefer letting the orchestrator reach the
server over a private compose network instead of publishing the ports on the host.

Remember to set `rdfc:hostname` in `server.ttl` to a name the **orchestrator** can resolve: `"localhost"` when
it runs on the Docker host and the ports are published, or the compose service name (e.g. `"jvm-runner"`)
when it runs inside the same compose network.


## Implementing a new processor

Processors must:

1. **Extend the abstract class** `io.github.rdfc.Processor<T>` where `T` is an `Args` class containing configuration fields.
2. **Provide a constructor** taking `(T arguments, java.util.logging.Logger logger)` — the runner builds the processor with the deserialized arguments and a logger that reports to the orchestrator.
3. **Implement the lifecycle methods**. Each returns a `CompletableFuture<?>` that completes when that phase is done; return `CompletableFuture.completedFuture(null)` for a phase with nothing to do.
   - `CompletableFuture<?> init()` — initialization, awaited before anything else runs.
   - `CompletableFuture<?> transform()` — processing of inputs from readers, called for each processor before produce, and *not* awaited before produce starts.
   - `CompletableFuture<?> produce()` — producing data, useful for a processor like a file reader. Called once every processor has been constructed.
4. **Define an `Args` class** with public fields matching the `sh:name`s of the SHACL shape; the runner deserializes the orchestrator's arguments straight onto them.

The returned futures are what carries backpressure, so returning them matters:

* `reader.buffers().on(...)` (also `strings()` and `streams()`) returns a future that completes when the
  channel closes. Registering a `Function<T, CompletableFuture<?>>` rather than a `Consumer<T>` makes the
  runner hold the incoming message until that future completes — a lambda that resolves to the `Consumer`
  overload instead silently drops the backpressure.
* `writer.chunk(...)`, `writer.chunks(...)` and `writer.close()` return futures that complete when the data
  has been acknowledged.

A minimal echo processor, reading a channel and forwarding every message to another:

```java
package org.example;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

import com.google.protobuf.ByteString;

import io.github.rdfc.IReader;
import io.github.rdfc.IWriter;
import io.github.rdfc.Processor;

public class EchoProcessor extends Processor<EchoProcessor.Args> {
    public static class Args {
        public IReader reader;
        public IWriter writer;
        public String additionalText;
    }

    public EchoProcessor(Args arguments, Logger logger) {
        super(arguments, logger);
    }

    @Override
    public CompletableFuture<?> init() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> transform() {
        // Declared, not written inline: this resolves to the Function overload, and the
        // returned future is what makes the runner wait before delivering the next message.
        Function<ByteString, CompletableFuture<?>> forward = buffer -> {
            this.logger.info("Echoing message: " + buffer.toStringUtf8());
            return this.arguments.writer.chunk(buffer);
        };

        return this.arguments.reader.buffers().on(forward)
                // The incoming channel closed, so the outgoing one can close too
                .thenCompose(end -> this.arguments.writer.close());
    }

    @Override
    public CompletableFuture<?> produce() {
        // Nothing to produce on its own: everything this writes is a reaction
        return CompletableFuture.completedFuture(null);
    }
}
```

Three more processors written against this API — a source, an echo and a sink — live in
[`test-processor/src/main/java/rdfc/test/`](test-processor/src/main/java/rdfc/test), and
[`tests/e2e`](tests/e2e) runs them as a real pipeline.

### Processor description file

The processor should be accompanied by a description file, often called `index.ttl`.

They require the following fields:
* `rdfc:javaImplementationOf` with value `rdfc:Processor`, indicating that this processor is a JavaProcessor,
* `rdfc:jar` pointing to the resulting jar, often `<>` pointing to the current jar  
* `rdfc:class` the fully qualified name of the processor
* A SHACL shape defining the required arguments.

For example, the following description file declares a processor with arguments `{ reader: Reader, writer: Writer, additionalText: string }`.
A matching implementation can be found on [GitHub](https://github.com/rdf-connect/template-processor-jvm/blob/main/src/main/java/org/example/Library.java).
```turtle
@prefix rdfc: <https://w3id.org/rdf-connect#>.
@prefix sh: <http://www.w3.org/ns/shacl#>.
@prefix xsd: <http://www.w3.org/2001/XMLSchema#>.

rdfc:TestProcessor rdfc:javaImplementationOf rdfc:Processor;
  rdfc:class "org.example.Library";
  rdfc:jar <file:./build/libs/my-processor-all.jar>.

[] a sh:NodeShape;
  sh:targetClass rdfc:TestProcessor;
  sh:property [
    sh:path rdfc:reader;
    sh:name "reader";
    sh:minCount 1;
    sh:maxCount 1;
    sh:class rdfc:Reader;
  ], [
    sh:path rdfc:writer;
    sh:name "writer";
    sh:minCount 1;
    sh:maxCount 1;
    sh:class rdfc:Writer;
  ], [
    sh:path rdfc:additionalText;
    sh:name "additionalText";
    sh:minCount 1;
    sh:maxCount 1;
    sh:datatype xsd:string;
  ].
```

### Build Instructions

To build a JVM processor for use with the JvmRunner:

#### Dependencies

Your `build.gradle` should include:

```gradle
plugins {
    id 'java'
    // The maintained fork of the old `com.github.johnrengelman.shadow`; this is the
    // version the runner itself builds its fat jar with.
    id 'com.gradleup.shadow' version '9.2.2'
    id 'maven-publish'
}

repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    implementation 'com.google.protobuf:protobuf-java:4.28.2'
    implementation 'com.github.rdf-connect.jvm-runner:types:master-SNAPSHOT'
}
```

#### Fat JAR Packaging

Use the Shadow plugin to produce a fat JAR that includes your processor and its descriptor:
``` gradle
tasks.named("shadowJar", Jar) {
    // add your processor descriptor (e.g., index.ttl) to the root of the jar
    from("index.ttl") {
        into("")
    }
}
```

The fat jar is built with `gradle shadowJar`


#### Publishing with JitPack

To make your processor available as a dependency from GitHub via [JitPack](https://jitpack.io), add the following to your `build.gradle`.

```gradle 
publishing {
    publications {
        maven(MavenPublication) {
            // publish the fat JAR
            artifact(tasks.shadowJar)
        }
    }
}
```

Then:

1. Push your code to GitHub
2. Users can then include your processor as a dependency like this:

```gradle
repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.<your-github-user>:<your-repo>:master-SNAPSHOT' // or a git hash or release
}
```



## Notes

* Args class fields must align with RDF properties defined in the SHACL shape.
* Descriptor file (e.g., index.ttl) must be packaged in the JAR.
* Fat JAR packaging ensures no dependency issues when running.
* Publishing with JitPack allows others to use your processor directly via GitHub.

