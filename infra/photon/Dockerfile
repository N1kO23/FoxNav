FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends zstd \
    && rm -rf /var/lib/apt/lists/*

ARG PHOTON_VERSION=1.3.0
ADD --chmod=644 https://github.com/komoot/photon/releases/download/${PHOTON_VERSION}/photon-${PHOTON_VERSION}.jar /opt/photon.jar
COPY --chmod=755 entrypoint.sh /entrypoint.sh

WORKDIR /photon
EXPOSE 2322
ENTRYPOINT ["/entrypoint.sh"]
