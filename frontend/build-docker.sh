#!/bin/bash

# 1. Compiles the Vue.js app for production
npm run build

# 2. Generates the Caddyfile to serve the Vue.js app
cat <<EOF >Caddyfile
:80 {
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
FROM caddy:2.6.4-alpine

# Copy the Vue.js app dist to the image
COPY dist /usr/share/caddy

# Add the Caddyfile to the image
COPY Caddyfile /etc/caddy/Caddyfile

# Expose port 80
EXPOSE 80
EOF

# 4. Creates a Docker image named turbol and sets the version using git tag
tag=$(git describe --tag)
docker build -t turbol:"${tag}" .
