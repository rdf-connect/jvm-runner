#!/usr/bin/env bash
#
# End-to-end test, containerised: the same send -> echo -> log pipeline as
# run-e2e.sh, but with the runner server running as the image built from the
# repository's Dockerfile instead of as a local `java -jar`.
#
# It builds the test processors and copies the jar next to server.ttl (the
# directory compose mounts read-only at /config), brings the service up, waits
# for it to answer /health on the published port, drives remote_pipeline.ttl with
# the pinned `@rdfc/orchestrator-js` *from the host*, and asserts that both
# messages came out the far end of the chain. Prints PASS or FAIL and exits
# accordingly.
#
# The orchestrator runs on the host on purpose: server.ttl advertises
# `rdfc:hostname "localhost"`, so what this exercises is a containerised runner
# reached over published ports — the deployment the Docker section of the README
# describes.
#
# Usage: tests/e2e/run-e2e-docker.sh
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${E2E_DIR}/../.." && pwd)"

COMPOSE_FILE="${E2E_DIR}/docker-compose.yml"

# Must match server.ttl and the port mapping in docker-compose.yml. 4001 and not
# 50051, because the orchestrator binds its own gRPC server on 50051.
HTTP_PORT=3000
GRPC_PORT=4001
HEALTH_URL="http://localhost:${HTTP_PORT}/health"

ORCHESTRATOR_LOG="${E2E_DIR}/orchestrator.log"
SERVED_JAR="${E2E_DIR}/test-processor.jar"

READY_TIMEOUT=60
ORCHESTRATOR_TIMEOUT=120

COMPOSE_UP=""

compose() {
  docker compose -f "${COMPOSE_FILE}" "$@"
}

server_log() {
  echo "--- server log (container) ---" >&2
  compose logs --no-color jvm-runner >&2 2>/dev/null || true
}

cleanup() {
  if [ -n "${COMPOSE_UP}" ]; then
    compose down --remove-orphans >/dev/null 2>&1 || true
    COMPOSE_UP=""
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
    # Cannot tell; compose will complain itself if the port is taken
    return 1
  fi
}

command -v node >/dev/null 2>&1 || fail "Node.js is required to run the orchestrator"
command -v docker >/dev/null 2>&1 || fail "Docker is required to run the containerised server"
docker info >/dev/null 2>&1 || fail "the Docker daemon is not reachable"
compose version >/dev/null 2>&1 || fail "'docker compose' (v2) is required"

for port in "${HTTP_PORT}" "${GRPC_PORT}"; do
  if port_in_use "${port}"; then
    fail "port ${port} is already in use (a previous run's server?); free it and try again"
  fi
done

# The processor descriptions this directory serves name their jar relative to
# themselves, so the container has to find it inside the mounted /config. It is a
# build output, so it is built here and not baked into the image.
echo "==> Building the test processors"
"${REPO_ROOT}/gradlew" -p "${REPO_ROOT}" --quiet :test-processor:jar

PROCESSOR_JAR="${REPO_ROOT}/test-processor/build/libs/test-processor.jar"
[ -f "${PROCESSOR_JAR}" ] || fail "no processor jar at ${PROCESSOR_JAR}"
cp "${PROCESSOR_JAR}" "${SERVED_JAR}"

if [ ! -x "${E2E_DIR}/node_modules/.bin/rdfc" ]; then
  echo "==> Installing the pinned orchestrator"
  (cd "${E2E_DIR}" && npm install --silent)
fi

echo "==> Building the image and starting the container"
compose up --build -d
COMPOSE_UP="yes"

echo "==> Waiting for ${HEALTH_URL}"
ready=""
deadline=$((SECONDS + READY_TIMEOUT))
while [ "${SECONDS}" -lt "${deadline}" ]; do
  if [ -z "$(compose ps -q jvm-runner)" ]; then
    break
  fi
  if curl -fsS --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1; then
    ready="yes"
    break
  fi
  sleep 0.2
done

if [ -z "${ready}" ]; then
  server_log
  fail "the containerised runner server did not become healthy within ${READY_TIMEOUT}s"
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
    server_log
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
  server_log
  fail "the orchestrator exited with ${status}"
fi

# The log processor logs every message it reads, so these two lines are the
# evidence that both messages travelled send -> raw -> echo -> data -> log.
for message in Hello World; do
  if ! grep -q "Received message: ${message}" "${ORCHESTRATOR_LOG}"; then
    server_log
    fail "'${message}' never reached the log processor"
  fi
done

cleanup

echo "PASS: both messages travelled send -> echo -> log through the containerised remote runner"
