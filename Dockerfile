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
# PretenureSizeThreshold: decoding allocates arrays of several MB per grid level; copying them in every young GC
# stopped the whole server for up to 300 ms of CPU (seconds on a tenth of a CPU). Allocated straight into the old
# generation, a GFS + GTG decode's total pause time fell from 1.3 s to 0.36 s and the longest from 300 ms to 40 ms.
ENV TURBOL_STATIC_DIR=/opt/turbol/static \
    TURBOL_DATA_DIR=/opt/turbol/var/data/turbulence \
    TURBOL_MIN_VOLUME_STEP=6 \
    MALLOC_ARENA_MAX=2 \
    TURBOL_GLOBAL_LAYER=on \
    JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:MaxHeapFreeRatio=30 -XX:MinHeapFreeRatio=10 -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=32m -Xss512k -Djdk.nio.maxCachedBufferSize=262144 -XX:+UseSerialGC -XX:PretenureSizeThreshold=1m -XX:+ExitOnOutOfMemoryError -Xlog:gc:stdout:time,uptime"
EXPOSE 8081
CMD ["./entrypoint.sh"]
