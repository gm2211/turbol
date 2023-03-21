#!/bin/bash
script_path="$(readlink -f "$0")"
script_dir="$(cd -P -- "$(dirname -- "$script_path")" && pwd -P)"
orig_dir="$(pwd)"

# -1. Check if docker buildx is installed
if ! docker buildx inspect multi-arch-builder > /dev/null 2>&1; then
  docker buildx create --use --name multi-arch-builder "$PWD"
fi

# 0. Go to the frontend directory
cd "${script_dir}" || exit

# 1. Compiles the Vue.js app for production
npm run build

# 2. Generates the Caddyfile to serve the Vue.js app
cat <<EOF >Caddyfile
:9000 {
  root * /usr/share/caddy
  file_server
  encode gzip
  log {
    output stdout
    format json
  }
}
EOF

# 3. Generates the Dockerfile to build the Docker image
cat <<EOF >Dockerfile
FROM caddy:2.6.4

# Copy the Vue.js app dist to the image
COPY dist /usr/share/caddy

# Add the Caddyfile to the image
COPY Caddyfile /etc/caddy/Caddyfile

# Expose port 9000
EXPOSE 9000
EOF

# 4. Creates a Docker image named turbol and sets the version using git tag
tag=$(git describe --tag --abbrev=0)
image_name="docker.io/gm2211/turbol-fe"
docker buildx build --platform=linux/arm64,linux/amd64 --push -t "${image_name}":"${tag}" .
docker buildx build --platform=linux/arm64,linux/amd64 --push -t "${image_name}:latest" .
docker push "${image_name}":"${tag}"
docker push "${image_name}:latest"

# 5. Go back to the original directory
cd "${orig_dir}" || exit