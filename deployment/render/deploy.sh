#!/usr/bin/env bash
# Deploy turbol to Render's free tier from the command line, via the Render API (no dashboard clicks).
# Creates the turbol-db Postgres and the turbol web service if they don't exist, then deploys develop and waits.
# Same layout as render.yaml. Safe to re-run: it reuses what already exists and just triggers a new deploy.
#
# Usage: RENDER_API_KEY=rnd_... deployment/render/deploy.sh
# Optional: RENDER_OWNER_ID (defaults to your first workspace), RENDER_REGION (default oregon).
set -euo pipefail

: "${RENDER_API_KEY:?Set RENDER_API_KEY (Render dashboard > Account Settings > API Keys)}"
command -v jq > /dev/null || { echo "jq is required (brew install jq / apt install jq)" >&2; exit 1; }

api_url=https://api.render.com/v1
repo=https://github.com/gm2211/turbol
branch=develop
region=${RENDER_REGION:-oregon}
db_name=turbol-db
service_name=turbol

api() { # api METHOD PATH [JSON]
  curl --fail-with-body -sS -X "$1" "$api_url$2" -H "Authorization: Bearer $RENDER_API_KEY" -H "Accept: application/json" \
    ${3:+-H "Content-Type: application/json" --data "$3"}
}

owner=${RENDER_OWNER_ID:-}
[ -n "$owner" ] || owner=$(api GET /owners | jq -r '.[0].owner.id')
echo "==> Workspace $owner"

db_id=$(api GET "/postgres?name=$db_name&ownerId=$owner" | jq -r '.[0].postgres.id // empty')
if [ -z "$db_id" ]; then
  echo "==> Creating Postgres $db_name (free)"
  db_id=$(api POST /postgres "$(jq -n --arg owner "$owner" --arg region "$region" --arg name "$db_name" \
    '{name: $name, ownerId: $owner, plan: "free", version: "16", region: $region,
      databaseName: "turbol", databaseUser: "turbol"}')" | jq -r .id)
fi
echo "==> Postgres $db_id"
until [ "$(api GET "/postgres/$db_id" | jq -r .status)" = available ]; do
  echo "    waiting for the database to become available..."
  sleep 10
done

# internalConnectionString: postgresql://USER:PASSWORD@HOST[:PORT]/DATABASE
conn=$(api GET "/postgres/$db_id/connection-info" | jq -r .internalConnectionString)
if [[ ! "$conn" =~ ^postgres(ql)?://([^:]+):([^@]+)@([^:/]+)(:([0-9]+))?/(.+)$ ]]; then
  echo "Unexpected connection string format from Render" >&2
  exit 1
fi
env_vars=$(jq -n --arg user "${BASH_REMATCH[2]}" --arg password "${BASH_REMATCH[3]}" --arg host "${BASH_REMATCH[4]}" \
  --arg port "${BASH_REMATCH[6]:-5432}" --arg db "${BASH_REMATCH[7]}" \
  '[{key: "DB_HOST", value: $host}, {key: "DB_PORT", value: $port}, {key: "DB_NAME", value: $db},
    {key: "DB_USER", value: $user}, {key: "DB_PASSWORD", value: $password}]')

service_id=$(api GET "/services?name=$service_name&type=web_service&ownerId=$owner" | jq -r '.[0].service.id // empty')
if [ -z "$service_id" ]; then
  echo "==> Creating web service $service_name (free, Docker, $branch)"
  created=$(api POST /services "$(jq -n --arg owner "$owner" --arg region "$region" --arg name "$service_name" \
    --arg repo "$repo" --arg branch "$branch" --argjson env "$env_vars" \
    '{type: "web_service", name: $name, ownerId: $owner, repo: $repo, branch: $branch,
      autoDeployTrigger: "commit", envVars: $env,
      serviceDetails: {runtime: "docker", plan: "free", region: $region, healthCheckPath: "/api/config",
        envSpecificDetails: {dockerfilePath: "./Dockerfile", dockerContext: "."}}}')")
  service_id=$(jq -r .service.id <<< "$created")
  deploy_id=$(jq -r .deployId <<< "$created")
else
  echo "==> Updating env vars and deploying existing service $service_id"
  api PUT "/services/$service_id/env-vars" "$env_vars" > /dev/null
  deploy_id=$(api POST "/services/$service_id/deploys" '{}' | jq -r .id)
fi
url=$(api GET "/services/$service_id" | jq -r .serviceDetails.url)
echo "==> Deploy $deploy_id of $service_id ($url). The first build takes ~10 min."

while true; do
  status=$(api GET "/services/$service_id/deploys/$deploy_id" | jq -r .status)
  case "$status" in
    live)
      echo "==> Live: $url (turbulence data loads ~2 min after start)"
      exit 0
      ;;
    build_failed | update_failed | canceled | pre_deploy_failed | deactivated)
      echo "==> Deploy $status. Logs: https://dashboard.render.com/web/$service_id/deploys/$deploy_id" >&2
      exit 1
      ;;
    *)
      echo "    $status..."
      sleep 20
      ;;
  esac
done
