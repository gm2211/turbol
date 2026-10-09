# One image for the whole app: the backend serves the API and the built frontend. Used by render.yaml.

FROM node:22-bookworm-slim AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build-only

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /src
COPY build.sbt ./
COPY project/ project/
# sbt from its GitHub release (bundles the launcher, so no Maven Central round trip just to start it).
RUN sbt_version=$(sed -n 's/^sbt.version=//p' project/build.properties) \
  && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${sbt_version}/sbt-${sbt_version}.tgz" | tar xz -C /opt
ENV PATH=/opt/sbt/bin:$PATH
# Render's builders have less memory than the 6 GB the repo's .sbtopts asks for.
RUN printf -- '-J-Xmx2G\n-J--add-opens=java.base/java.util=ALL-UNNAMED\n-J--add-opens=java.base/java.lang=ALL-UNNAMED\n' > .sbtopts \
  && sbt -batch update
COPY backend/ backend/
RUN sbt -batch backend/stage

FROM eclipse-temurin:21-jre
WORKDIR /opt/turbol
COPY --from=backend /src/backend/target/universal/stage/ ./
COPY --from=frontend /src/frontend/dist/ ./static/
COPY deployment/render/entrypoint.sh ./
ENV TURBOL_STATIC_DIR=/opt/turbol/static \
    TURBOL_DATA_DIR=/opt/turbol/var/data/turbulence \
    TURBOL_MIN_VOLUME_STEP=6 \
    JAVA_OPTS="-XX:MaxRAMPercentage=55 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081
CMD ["./entrypoint.sh"]
