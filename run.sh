#!/usr/bin/env bash
# Run the whole stack locally: Postgres (Docker), the backend on :8081 and the frontend on :5173.
# Ctrl-C stops everything. Set KEEP_DB=1 to leave Postgres running afterwards.
set -euo pipefail
cd "$(dirname "$0")"

missing=""
need() { command -v "$1" > /dev/null 2>&1 || missing="$missing  - $2\n"; }
need docker "Docker (https://docs.docker.com/get-docker/)"
need java "JDK 21+ (e.g. https://adoptium.net)"
need node "Node 22+ (https://nodejs.org)"
need npm "npm (comes with Node)"
if [ -n "$missing" ]; then
  printf 'Missing prerequisites:\n%b' "$missing" >&2
  exit 1
fi

java_major=$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)
if [ "${java_major:-0}" -lt 21 ]; then
  echo "JDK 21+ is required (found ${java_major:-unknown}). Set JAVA_HOME or PATH to a newer JDK." >&2
  exit 1
fi
node_major=$(node -p 'process.versions.node.split(".")[0]')
if [ "$node_major" -lt 22 ]; then
  echo "Node 22+ is required (found $node_major)." >&2
  exit 1
fi

if docker compose version > /dev/null 2>&1; then
  compose=(docker compose -f dev/docker-compose.yml)
elif command -v docker-compose > /dev/null 2>&1; then
  compose=(docker-compose -f dev/docker-compose.yml)
else
  echo "Docker Compose is missing (install the Docker Compose plugin)." >&2
  exit 1
fi
if ! docker info > /dev/null 2>&1; then
  echo "Docker is installed but not reachable: start Docker (or check you may use it without sudo)." >&2
  exit 1
fi

# Use sbt if installed, otherwise the bundled launcher (downloads sbt on first run).
export SBT="${SBT:-$(command -v sbt || echo "$PWD/sbtw")}"

pids=""
cleanup() {
  trap - INT TERM EXIT
  echo
  echo "Stopping..."
  for pid in $pids; do kill "$pid" 2> /dev/null || true; done
  wait 2> /dev/null || true
  if [ -z "${KEEP_DB:-}" ]; then "${compose[@]}" stop > /dev/null 2>&1 || true; fi
}
trap cleanup INT TERM EXIT

echo "==> Starting Postgres"
"${compose[@]}" up -d
until "${compose[@]}" exec -T postgres pg_isready -U postgres > /dev/null 2>&1; do sleep 1; done

# Reinstall frontend dependencies only when package-lock.json changed since the last install.
if [ ! -f frontend/node_modules/.package-lock.json ] ||
  [ frontend/package-lock.json -nt frontend/node_modules/.package-lock.json ]; then
  echo "==> Installing frontend dependencies"
  (cd frontend && npm ci)
fi

echo "==> Starting backend on :8081 (the first run builds it and downloads turbulence data)"
dev/run-backend.sh &
pids="$pids $!"

echo "==> Starting frontend on :5173"
(cd frontend && exec npm run dev -- --port 5173 --strictPort) &
pids="$pids $!"

echo "==> Open http://localhost:5173 once the backend logs that it is listening. Ctrl-C to stop."
# Stop as soon as either process dies, so a failed build doesn't leave the other one running.
while true; do
  for pid in $pids; do kill -0 "$pid" 2> /dev/null || exit 1; done
  sleep 1
done
