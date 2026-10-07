#!/usr/bin/env bash
# Build and run the backend against the local Postgres from docker-compose.yml.
# Turbulence data is cached under var/data/turbulence (override with TURBOL_DATA_DIR).
set -euo pipefail
cd "$(dirname "$0")/.."
${SBT:-sbt} backend/stage
export INSTALL_CONFIG_OVERRIDES_PATH="$PWD/dev/conf/install.yml"
export RUNTIME_CONFIG_OVERRIDES_PATH="$PWD/dev/conf/runtime.yml"
export APP_SECRETS_PATH="$PWD/dev/conf/dev-secrets.yml"
export TURBOL_DATA_DIR="${TURBOL_DATA_DIR:-$PWD/var/data/turbulence}"
exec backend/target/universal/stage/bin/backend "$@"
