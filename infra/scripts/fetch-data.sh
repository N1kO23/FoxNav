#!/usr/bin/env bash
# Downloads what the local map stack needs into infra/data/. Safe to re-run: finished
# files are skipped and interrupted downloads resume.
#
#   valhalla/<region>-latest.osm.pbf   OpenStreetMap extract for routing (Geofabrik)
#   photon/import.jsonl.zst            search index dump (GraphHopper)
#   tiles/region.pmtiles               vector map tiles cut from the Protomaps planet build
#   assets/fonts, assets/sprites       label fonts and map icons (Protomaps basemaps-assets)
#   digiroad/<area>/                   Finland only: official speed limits from Digiroad, now
#                                      Fintraffic's Digitraffic Road Network (CC BY 4.0)
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
        DIGIROAD=https://tie.digitraffic.fi/roadnetwork
        ;;
    *)
        echo "Unknown region '$REGION'. Known regions: finland" >&2
        exit 1
        ;;
esac

PMTILES_VERSION=1.31.2
FONTSTACKS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")

DATA=$(cd "$(dirname "$0")/.." && pwd)/data
mkdir -p "$DATA"/{valhalla,photon,tiles,assets,bin,routing-tiles}

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

# Official speed limits, ahead of OpenStreetMap's where both have one. Digiroad moved from
# Väylävirasto to Fintraffic in 2026 (Digitraffic Road Network); its releases are zips per region,
# half a gigabyte each, of which only the two speed limit layers are needed: zip-extract.py reads
# just those with range requests (about 1 GB for Finland). A newer release replaces the old data,
# and the packs are rebuilt from it.
if [ -n "${DIGIROAD:-}" ]; then
    list() { curl -fsS --retry 3 "$DIGIROAD/?list-type=2&delimiter=/&prefix=$1"; }
    # The newest release in the old Digiroad K format (digiroad/2026_02 and so on). Fintraffic's
    # own format, under releases/ from 2027, needs its own reader.
    RELEASE=$(list digiroad/ | grep -oE 'digiroad/[0-9]{4}_[0-9]{2}/' | sort -u | tail -1 | cut -d/ -f2)
    if [ -z "$RELEASE" ]; then
        echo "✗ no Digiroad release found at $DIGIROAD; speed limits stay as they were" >&2
    elif [ "$(cat "$DATA/digiroad/.complete" 2>/dev/null)" = "$RELEASE" ]; then
        echo "✓ Digiroad $RELEASE speed limits already downloaded"
    else
        echo "↓ Digiroad $RELEASE speed limits (Fintraffic)"
        rm -rf "$DATA/digiroad.new"
        mkdir -p "$DATA/digiroad.new"
        for ZIP in $(list "digiroad/$RELEASE/Maakuntajako_digiroad_K/" | grep -oE '<Key>[^<]+\.zip</Key>' | sed -E 's|</?Key>||g'); do
            python3 -I "$(dirname "$0")/zip-extract.py" "$DIGIROAD/$ZIP" "$DATA/digiroad.new" DR_NOPEUSRAJOITUS_K DR_TALVINOPEUSRAJOITUS_K
            echo "  ✓ $(basename "$ZIP" .zip)"
        done
        echo "$RELEASE" > "$DATA/digiroad.new/.complete"
        rm -rf "$DATA/digiroad"
        mv "$DATA/digiroad.new" "$DATA/digiroad"
        # The packs were built from older limits.
        rm -f "$DATA/datapacks/$COUNTRY/cameras.json"
    fi
fi

# Speed cameras and speed limits from the OpenStreetMap extract and Digiroad (protocol/SPEC.md §3).
# Delete the files to rebuild after fetching newer data.
if [ -f "$DATA/datapacks/$COUNTRY/cameras.json" ]; then
    echo "✓ data packs already built"
else
    echo "⚙ data packs"
    mkdir -p "$DATA/datapacks"
    FOXNAV_REGION=$REGION FOXNAV_REGION_CODE=$COUNTRY \
        docker compose -f "$(dirname "$0")/../compose.yaml" run --rm --build datapacks
fi

# The routing graph's tiles for routing on the phone (protocol/SPEC.md §3.3). Valhalla builds the graph
# on its first start, so on a first run there's nothing to publish yet: run the routing-tiles service
# once it has (see infra/README.md). Publishing an unchanged graph is a no-op.
if [ -d "$DATA/valhalla/valhalla_tiles/0" ]; then
    echo "⚙ routing tiles"
    docker compose -f "$(dirname "$0")/../compose.yaml" run --rm --build routing-tiles
fi

echo "Done. Data is in $DATA ($(du -sh "$DATA" | cut -f1))."
