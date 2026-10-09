#!/usr/bin/env bash
# Container entrypoint for Render (see render.yaml): writes the backend's YAML config from the environment Render
# provides (PORT and the linked Postgres), then starts the backend.
set -euo pipefail

conf=/tmp/turbol-conf
mkdir -p "$conf"
# YAML single-quoted scalar: double any single quote.
q() { printf "'%s'" "${1//\'/\'\'}"; }

cat > "$conf/install.yml" <<YAML
server:
  dev-mode: false
  port: ${PORT:-8081}
YAML
cat > "$conf/runtime.yml" <<YAML
database-config:
  admin-user: $(q "$DB_USER")
  database-name: $(q "$DB_NAME")
  hostname: $(q "$DB_HOST")
  port: ${DB_PORT:-5432}
logging:
  level-by-class-name: {}
  root-logger-level: info
YAML
cat > "$conf/secrets.yml" <<YAML
db-admin-password: $(q "$DB_PASSWORD")
YAML

export INSTALL_CONFIG_OVERRIDES_PATH="$conf/install.yml"
export RUNTIME_CONFIG_OVERRIDES_PATH="$conf/runtime.yml"
export APP_SECRETS_PATH="$conf/secrets.yml"
exec "$(dirname "$0")/bin/backend" "$@"
