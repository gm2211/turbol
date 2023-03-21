FROM caddy:2.6.4

# Copy the Vue.js app dist to the image
COPY dist /usr/share/caddy

# Add the Caddyfile to the image
COPY Caddyfile /etc/caddy/Caddyfile

# Expose port 9000
EXPOSE 9000
