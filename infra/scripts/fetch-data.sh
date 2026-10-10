#!/usr/bin/env bash
# Downloads what the local map stack needs into infra/data/. Safe to re-run: finished
# files are skipped and interrupted downloads resume.
#
#   valhalla/<region>-latest.osm.pbf   OpenStreetMap extract for routing (Geofabrik)
#   photon/import.jsonl.zst            search index dump (GraphHopper)
#   tiles/region.pmtiles               vector map tiles cut from the Protomaps planet build
#   assets/fonts, assets/sprites       label fonts and map icons (Protomaps basemaps-assets)
#
# Usage: infra/scripts/fetch-data.sh [region]     regions: finland (default)
set -euo pipefail

REGION=${1:-finland}
case "$REGION" in
    finland)
        COUNTRY=FI
        PBF_URL=https://download.geofabrik.de/europe/finland-latest.osm.pbf
        PHOTON_URL=https://download1.graphhopper.com/public/europe/finland/photon-dump-finland-1.0-latest.jsonl.zst
        BBOX=19.0,59.3,31.6,70.1
        ;;
    *)
        echo "Unknown region '$REGION'. Known regions: finland" >&2
        exit 1
        ;;
esac

PMTILES_VERSION=1.31.2
FONTSTACKS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")

DATA=$(cd "$(dirname "$0")/.." && pwd)/data
mkdir -p "$DATA"/{valhalla,photon,tiles,assets,bin}

download() { # url destination
    if [ -f "$2" ]; then
        echo "✓ $(basename "$2") already downloaded"
        return
    fi
    echo "↓ $1"
    curl -fL --retry 3 -C - -o "$2.part" "$1"
    mv "$2.part" "$2"
}

# Routing graph source. Valhalla builds its tiles from every .pbf in data/valhalla/.
download "$PBF_URL" "$DATA/valhalla/$REGION-latest.osm.pbf"

# Search index dump. The Photon container imports it on first start.
download "$PHOTON_URL" "$DATA/photon/import.jsonl.zst"

# Map tiles: cut the region out of the latest daily Protomaps planet build.
PMTILES=$(command -v pmtiles || true)
if [ -z "$PMTILES" ]; then
    PMTILES="$DATA/bin/pmtiles"
    if [ ! -x "$PMTILES" ]; then
        case "$(uname -s)-$(uname -m)" in
            Linux-x86_64) ASSET=go-pmtiles_${PMTILES_VERSION}_Linux_x86_64.tar.gz ;;
            Linux-aarch64 | Linux-arm64) ASSET=go-pmtiles_${PMTILES_VERSION}_Linux_arm64.tar.gz ;;
            *) echo "Install the pmtiles CLI (https://github.com/protomaps/go-pmtiles) and re-run." >&2; exit 1 ;;
        esac
        echo "↓ pmtiles CLI $PMTILES_VERSION"
        curl -fsSL "https://github.com/protomaps/go-pmtiles/releases/download/v$PMTILES_VERSION/$ASSET" \
            | tar -xz -C "$DATA/bin" pmtiles
    fi
fi
if [ -f "$DATA/tiles/region.pmtiles" ]; then
    echo "✓ region.pmtiles already extracted"
else
    BUILD=$(curl -fsS https://build-metadata.protomaps.dev/builds.json \
        | python3 -c 'import json, sys; print(json.load(sys.stdin)[-1]["key"])')
    echo "↓ $REGION tiles from Protomaps build $BUILD"
    "$PMTILES" extract "https://build.protomaps.com/$BUILD" "$DATA/tiles/region.pmtiles.part" --bbox="$BBOX"
    mv "$DATA/tiles/region.pmtiles.part" "$DATA/tiles/region.pmtiles"
fi

# Fonts and sprites referenced by infra/styles/*.json.
if [ -d "$DATA/assets/sprites/v4" ]; then
    echo "✓ fonts and sprites already downloaded"
else
    echo "↓ fonts and sprites"
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    git clone -q --depth 1 --filter=blob:none --sparse https://github.com/protomaps/basemaps-assets "$TMP/assets"
    (cd "$TMP/assets" && git sparse-checkout set "${FONTSTACKS[@]/#/fonts/}" sprites/v4)
    mkdir -p "$DATA/assets/fonts"
    for stack in "${FONTSTACKS[@]}"; do cp -r "$TMP/assets/fonts/$stack" "$DATA/assets/fonts/"; done
    mkdir -p "$DATA/assets/sprites"
    cp -r "$TMP/assets/sprites/v4" "$DATA/assets/sprites/"
fi

# Speed cameras from the OpenStreetMap extract (protocol/SPEC.md §3). Delete the file to rebuild
# after fetching newer data.
if [ -f "$DATA/datapacks/$COUNTRY/cameras.json" ]; then
    echo "✓ data packs already built"
else
    echo "⚙ data packs"
    mkdir -p "$DATA/datapacks"
    FOXNAV_REGION=$REGION FOXNAV_REGION_CODE=$COUNTRY \
        docker compose -f "$(dirname "$0")/../compose.yaml" run --rm --build datapacks
fi

echo "Done. Data is in $DATA ($(du -sh "$DATA" | cut -f1))."
