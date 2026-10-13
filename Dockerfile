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
# GC on a tenth of a CPU, where every pause stops all requests for 10x its CPU time:
# - -Xms192m: starting at 7 MB, the heap grew (and shrank back) through full GCs. Prod's log after a deploy showed 56
#   full GCs in 5 minutes, 300-800 ms each, 24.6 s in all, while visitors waited.
# - PretenureSizeThreshold=1m with a small young generation (-Xmn24m): decoding allocates multi-MB grid arrays;
#   copying them in young GCs paused up to 300 ms of CPU. Locally, over 3 GTG + 3 GFS decodes with these settings:
#   longest pause 45 ms, 448 ms in all (prod's previous settings: 326 ms longest, 1201 ms in all).
# - MetaspaceSize=48m, SweeperThreshold=50: with the defaults, loading classes and compiling code each forced a full GC
#   (one "Metadata GC Threshold", three "CodeCache GC Threshold" in prod's first 5 minutes, 300-500 ms each, two of
#   them after the server went live). Code-cache unloading now waits for half of the 48 MB code cache.
ENV TURBOL_STATIC_DIR=/opt/turbol/static \
    TURBOL_DATA_DIR=/opt/turbol/var/data/turbulence \
    TURBOL_MIN_VOLUME_STEP=6 \
    MALLOC_ARENA_MAX=2 \
    TURBOL_GLOBAL_LAYER=on \
    JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:MaxHeapFreeRatio=30 -XX:MinHeapFreeRatio=10 -XX:MetaspaceSize=48m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m -XX:SweeperThreshold=50 -XX:MaxDirectMemorySize=32m -Xss512k -Djdk.nio.maxCachedBufferSize=262144 -Xms192m -Xmn24m -XX:+UseSerialGC -XX:PretenureSizeThreshold=1m -XX:+ExitOnOutOfMemoryError -Xlog:gc:stdout:time,uptime"
EXPOSE 8081
CMD ["./entrypoint.sh"]
