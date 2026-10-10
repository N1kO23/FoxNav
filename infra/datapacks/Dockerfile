# Builds the data packs (protocol/SPEC.md §3) from the OpenStreetMap extract. Run on demand:
#   docker compose -f infra/compose.yaml run --rm datapacks
FROM debian:stable-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends osmium-tool python3-pyosmium \
    && rm -rf /var/lib/apt/lists/*
COPY build.py /usr/local/bin/build-datapacks
ENTRYPOINT ["python3", "/usr/local/bin/build-datapacks"]
