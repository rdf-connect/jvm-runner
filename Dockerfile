# Build stage: build the runner's fat jar.
#
# Gradle 8.14 on JDK 17, matching the wrapper in gradle/wrapper (8.14.3). The
# modules themselves target a Java 11 toolchain (see the root build.gradle),
# which the foojay resolver configured in settings.gradle downloads inside this
# stage — so the JDK of the base image does not have to be the JDK the classes
# are compiled for.
FROM gradle:8.14-jdk17 AS build
WORKDIR /src

# Only what the fat jar is built from. `test-processor` is a test fixture that is
# never built here, but settings.gradle includes it, so its directory has to
# exist for the build to configure; it is a handful of source files.
COPY settings.gradle build.gradle gradle.properties ./
COPY types ./types
COPY runner ./runner
COPY test-processor ./test-processor

# `--no-daemon`: nothing is left to reuse the daemon after this layer.
# The archive carries the project version in its name, so pin it to one path here
# instead of globbing in the runtime stage.
RUN gradle --no-daemon :runner:shadowJar \
 && cp runner/build/libs/*-all.jar /runner.jar

# Runtime stage: just a JRE and the jar.
FROM eclipse-temurin:17-jre-jammy

COPY --from=build /runner.jar /app/runner.jar

# The configuration document, the processor descriptions it whitelists and the
# processor jars they name are all mounted here; the server derives what it
# serves over HTTP from where that document lies, so the whole set has to be
# inside this one directory. Making it the working directory also means a
# relative argument (`server.ttl`) resolves against it.
WORKDIR /config

# 3000 is the HTTP port the shipped configurations use; 50051 is the runner's
# default gRPC port. Both are documentation only — the ports a container actually
# listens on are the ones in the mounted server.ttl (the end-to-end bed uses
# 4001 for gRPC, see tests/e2e/docker-compose.yml), and publishing them is the
# `-p`/`ports:` of whoever runs the image.
EXPOSE 3000 50051

ENTRYPOINT ["java", "-jar", "/app/runner.jar", "server"]
CMD ["/config/server.ttl"]
