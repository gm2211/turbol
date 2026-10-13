#!/usr/bin/env bash
# Container entrypoint for Render (see render.yaml): writes the backend's YAML config from the environment Render
# provides (PORT and the linked Postgres), starts nginx on PORT (it serves the frontend and passes /api on, see
# nginx.conf), then starts the backend behind it.
set -euo pipefail

public_port=${PORT:-8081}
backend_port=8082
dir=$(dirname "$0")
if command -v nginx > /dev/null 2>&1; then
  export TURBOL_TILE_DIR=/tmp/turbol-tiles
  mkdir -p "$TURBOL_TILE_DIR"
  sed -e "s/__PORT__/$public_port/" -e "s/__BACKEND_PORT__/$backend_port/" -e "s#__TILE_DIR__#$TURBOL_TILE_DIR#" \
    "$dir/nginx.conf" > /tmp/nginx.conf
  nginx -c /tmp/nginx.conf
else
  backend_port=$public_port
fi

conf=/tmp/turbol-conf
mkdir -p "$conf"
# YAML single-quoted scalar: double any single quote.
q() { printf "'%s'" "${1//\'/\'\'}"; }

cat > "$conf/install.yml" <<YAML
server:
  dev-mode: false
  port: $backend_port
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
exec "$dir/bin/backend" "$@"
