# Build context: the repository root (see infra/compose.yaml).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY protocol protocol
COPY shared shared
COPY server server
RUN ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home foxnav && mkdir -p /var/lib/foxnav && chown foxnav /var/lib/foxnav
COPY --from=build /src/server/build/install/foxnav-server /opt/foxnav-server
USER foxnav
ENV FOXNAV_STYLES_DIR=/srv/styles
EXPOSE 8080
ENTRYPOINT ["/opt/foxnav-server/bin/foxnav-server"]
