#!/usr/bin/env bash
#
# End-to-end test: run the send -> echo -> log pipeline against the real
# RDF-Connect orchestrator, with this runner in server mode.
#
# Builds the fat jar and the test processors, starts `runner-all.jar server
# server.ttl`, waits for it to answer /health, drives `remote_pipeline.ttl` with
# the pinned `@rdfc/orchestrator-js`, and asserts that both messages came out the
# far end of the chain. Prints PASS or FAIL and exits accordingly.
#
# Usage: tests/e2e/run-e2e.sh
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${E2E_DIR}/../.." && pwd)"

# Must match server.ttl. 4001 and not 50051, because the orchestrator binds its
# own gRPC server on 50051.
HTTP_PORT=3000
GRPC_PORT=4001
HEALTH_URL="http://localhost:${HTTP_PORT}/health"

SERVER_LOG="${E2E_DIR}/server.log"
ORCHESTRATOR_LOG="${E2E_DIR}/orchestrator.log"
SERVED_JAR="${E2E_DIR}/test-processor.jar"

READY_TIMEOUT=30
ORCHESTRATOR_TIMEOUT=120

SERVER_PID=""

cleanup() {
  if [ -n "${SERVER_PID}" ] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    # Give it the shutdown grace period, then insist
    for _ in $(seq 1 30); do
      kill -0 "${SERVER_PID}" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

fail() {
  echo
  echo "FAIL: $*" >&2
  exit 1
}

# Refuses to start rather than to fight over a port: a stale server from an
# earlier run would answer /health and make this test look like it passed.
port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    [ -n "$(lsof -iTCP:"${port}" -sTCP:LISTEN -t 2>/dev/null || true)" ]
  elif command -v nc >/dev/null 2>&1; then
    nc -z localhost "${port}" >/dev/null 2>&1
  else
    # Cannot tell; the server will complain itself if the port is taken
    return 1
  fi
}

for port in "${HTTP_PORT}" "${GRPC_PORT}"; do
  if port_in_use "${port}"; then
    fail "port ${port} is already in use (a previous run's server?); free it and try again"
  fi
done

command -v node >/dev/null 2>&1 || fail "Node.js is required to run the orchestrator"
command -v java >/dev/null 2>&1 || fail "Java is required to run the runner"

echo "==> Building the runner fat jar and the test processors"
"${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" --quiet :runner:shadowJar :test-processor:jar

FAT_JAR="$(ls -1 "${REPO_ROOT}"/runner/build/libs/*-all.jar 2>/dev/null | head -n 1 || true)"
[ -n "${FAT_JAR}" ] || fail "no fat jar in runner/build/libs (expected *-all.jar)"

PROCESSOR_JAR="${REPO_ROOT}/test-processor/build/libs/test-processor.jar"
[ -f "${PROCESSOR_JAR}" ] || fail "no processor jar at ${PROCESSOR_JAR}"

# Next to server.ttl, i.e. at the root of what the server serves: the processor
# descriptions name their jar relative to themselves, so the orchestrator hands
# the runner <http://localhost:3000/test-processor.jar> and the runner maps that
# back onto this file.
cp "${PROCESSOR_JAR}" "${SERVED_JAR}"

if [ ! -x "${E2E_DIR}/node_modules/.bin/rdfc" ]; then
  echo "==> Installing the pinned orchestrator"
  (cd "${E2E_DIR}" && npm install --silent)
fi

echo "==> Starting the runner server (log: ${SERVER_LOG})"
(cd "${E2E_DIR}" && exec java -jar "${FAT_JAR}" server server.ttl) >"${SERVER_LOG}" 2>&1 &
SERVER_PID=$!

echo "==> Waiting for ${HEALTH_URL}"
ready=""
deadline=$((SECONDS + READY_TIMEOUT))
while [ "${SECONDS}" -lt "${deadline}" ]; do
  if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    break
  fi
  if curl -fsS --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1; then
    ready="yes"
    break
  fi
  sleep 0.2
done

if [ -z "${ready}" ]; then
  echo "--- server log ---" >&2
  cat "${SERVER_LOG}" >&2 || true
  fail "the runner server did not become healthy within ${READY_TIMEOUT}s"
fi

echo "==> Running the pipeline with the orchestrator (log: ${ORCHESTRATOR_LOG})"
status=0
(cd "${E2E_DIR}" && ./node_modules/.bin/rdfc remote_pipeline.ttl) >"${ORCHESTRATOR_LOG}" 2>&1 &
ORCHESTRATOR_PID=$!

# Bounded, so a pipeline that hangs fails the test instead of the test runner
deadline=$((SECONDS + ORCHESTRATOR_TIMEOUT))
while kill -0 "${ORCHESTRATOR_PID}" 2>/dev/null; do
  if [ "${SECONDS}" -ge "${deadline}" ]; then
    kill -9 "${ORCHESTRATOR_PID}" 2>/dev/null || true
    echo "--- orchestrator output ---" >&2
    cat "${ORCHESTRATOR_LOG}" >&2 || true
    echo "--- server log ---" >&2
    cat "${SERVER_LOG}" >&2 || true
    fail "the orchestrator did not finish within ${ORCHESTRATOR_TIMEOUT}s"
  fi
  sleep 0.5
done
wait "${ORCHESTRATOR_PID}" || status=$?

echo
echo "--- orchestrator output ---"
cat "${ORCHESTRATOR_LOG}"
echo "---------------------------"
echo

if [ "${status}" -ne 0 ]; then
  echo "--- server log ---" >&2
  cat "${SERVER_LOG}" >&2 || true
  fail "the orchestrator exited with ${status}"
fi

# The log processor logs every message it reads, so these two lines are the
# evidence that both messages travelled send -> raw -> echo -> data -> log.
for message in Hello World; do
  if ! grep -q "Received message: ${message}" "${ORCHESTRATOR_LOG}"; then
    echo "--- server log ---" >&2
    cat "${SERVER_LOG}" >&2 || true
    fail "'${message}' never reached the log processor"
  fi
done

cleanup
SERVER_PID=""

echo "PASS: both messages travelled send -> echo -> log through the remote runner"
