# Builds the data packs (protocol/SPEC.md §3) from the OpenStreetMap extract, and publishes the
# routing graph's tiles (§3.3, routing_tiles.py). Run on demand:
#   docker compose -f infra/compose.yaml run --rm datapacks
#   docker compose -f infra/compose.yaml run --rm routing-tiles
FROM debian:stable-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends osmium-tool python3-pyosmium \
        python3-numpy python3-shapely python3-pyproj python3-pyshp \
    && rm -rf /var/lib/apt/lists/*
COPY build.py digiroad.py routing_tiles.py /opt/datapacks/
ENTRYPOINT ["python3", "/opt/datapacks/build.py"]
