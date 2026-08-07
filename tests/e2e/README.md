# End-to-end test: echo pipeline

This directory is **not** an example for pipeline architects. It is a small end-to-end
test bed for **jvm-runner developers**.

It hosts a tiny pipeline whose processors (`../../test-processor/`) are implemented
*locally, in-tree*. That lets a runner developer change the runner/server implementation,
tweak the test processors alongside it if needed, and verify the whole `send -> echo ->
log` chain still works — all **without** having to publish a processor jar first.

> [!NOTE]
> Keeping processors in the runner's own repository like this is exactly what real
> pipeline architects should **not** do (published processor jars belong on JitPack or
> Maven Central, not inside the runner). It is done here only because this is a runner
> development test, not an example to copy.

The pipeline can be run two ways: **manually** during development, and **automatically**
with `run-e2e.sh`. Both use the real RDF-Connect orchestrator (`@rdfc/orchestrator-js`),
so they exercise the actual TTL parsing, the HTTP import of the runner and processor
definitions, and the remote (TCP) runner protocol — not a stub of any of it.

## Files

| File                        | Purpose                                                                        |
|-----------------------------|--------------------------------------------------------------------------------|
| `server.ttl`                | Runner server config (`httpPort 3000`, `grpcPort 4001`).                        |
| `processors/*.ttl`          | One processor description per processor, served by the runner over HTTP.       |
| `remote_pipeline.ttl`       | The pipeline the orchestrator runs.                                            |
| `package.json`              | Pins the `@rdfc/orchestrator-js` version used to drive the pipeline.           |
| `run-e2e.sh`                | The automated run: build, start, drive, assert, clean up.                       |

The processors themselves live in `../../test-processor/src/main/java/rdfc/test/`. They
are built into `test-processor.jar`, which `run-e2e.sh` copies **next to `server.ttl`** —
that is the root of what the server serves, and each `processors/*.ttl` names its jar
relative to itself (`<../test-processor.jar>`). The orchestrator resolves that against the
URL it read the description from, hands the runner
`http://localhost:3000/test-processor.jar`, and the runner maps that URL back onto the
file on its own disk instead of downloading it (the HTTP side serves only whitelisted
Turtle, so downloading it would 403).

> [!NOTE]
> The runner server uses `rdfc:grpcPort 4001` because the orchestrator binds its **own**
> gRPC server on `50051` by default; using `4001` for the runner avoids the clash.

## One-time setup

Install the orchestrator (creates `node_modules/`, which is git-ignored):

```shell
cd tests/e2e
npm install
```

## Run it manually (developer loop)

Build the fat jar and the processors, and put the processor jar where the server serves
it:

```shell
./gradlew :runner:shadowJar :test-processor:jar
cp test-processor/build/libs/test-processor.jar tests/e2e/
```

In one terminal, start the runner server:

```shell
cd tests/e2e
java -jar ../../runner/build/libs/runner-*-all.jar server server.ttl
```

In another terminal, run the pipeline with the orchestrator:

```shell
cd tests/e2e
npx rdfc remote_pipeline.ttl
```

You should see the messages flow through the chain:

```
[:sendProc, http://localhost:3000/jvmRunner] info: Sending message: Hello
[:echoProc, http://localhost:3000/jvmRunner] info: Echoing message: Hello
[:logProc, http://localhost:3000/jvmRunner] info: Received message: Hello
```

While it runs, `http://localhost:3000/dashboard` shows the connected runners, and
`http://localhost:3000/` is the index document the orchestrator imported.

Edit the processors (or the runner source), rebuild, and re-run to check your changes on
the go.

## Run it automatically

```shell
./tests/e2e/run-e2e.sh
```

It builds both jars, copies the processor jar into place, starts the server (logging to
`server.log`), waits for `/health`, runs the orchestrator against `remote_pipeline.ttl`
(logging to `orchestrator.log`), asserts that both messages reached the log processor,
stops the server and prints `PASS` or `FAIL`. It refuses to start when port 3000 or 4001
is already taken, rather than testing against whatever is listening there.

It is not wired into `./gradlew test`: it needs Node and a network fetch of the
orchestrator, and it starts real servers on fixed ports.

## The same pipeline without a server

`../../test_pipeline.ttl` is the CLI counterpart: the same `send -> echo -> log` chain,
but run by the orchestrator's `CommandRunner` (which starts a runner process) instead of
by a server it connects to. See the comment at the top of that file.
