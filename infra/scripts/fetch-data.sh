#!/usr/bin/env bash
# Downloads what the local map stack needs into infra/data/. Safe to re-run: finished files are
# skipped and interrupted downloads resume.
#
#   osm/<region>-latest.osm.pbf        OpenStreetMap extracts (Geofabrik)
#   valhalla/region-<key>.osm.pbf      the extracts merged into one, for Valhalla's routing graph
#   photon/<region>.jsonl.zst          search index dumps (GraphHopper)
#   tiles/region.pmtiles               vector map tiles for all the regions, cut from the Protomaps planet build
#   assets/fonts, assets/sprites       label fonts and map icons (Protomaps basemaps-assets)
#   digiroad/<area>/                   Finland only: official speed limits from Digiroad, now
#                                      Fintraffic's Digitraffic Road Network (CC BY 4.0)
#   nvdb/                              Norway only: official speed limits and speed cameras from
#                                      NVDB, Statens vegvesen's road database (NLOD 2.0)
#   regions.env                        the regions' country codes, for the server
#
# Usage: infra/scripts/fetch-data.sh [region...]    regions: finland, norway, sweden
# Without regions, it updates the ones already downloaded (Finland the first time). A region
# stays until its files are deleted: osm/<region>-latest.osm.pbf and photon/<region>.jsonl.zst.
set -euo pipefail

REGIONS=(finland norway sweden)

region_settings() { # region -> COUNTRY, PBF_URL, PHOTON_URL, BBOX
    PBF_URL=https://download.geofabrik.de/europe/$1-latest.osm.pbf
    PHOTON_URL=https://download1.graphhopper.com/public/europe/$1/photon-dump-$1-1.0-latest.jsonl.zst
    case "$1" in
        finland) COUNTRY=FI BBOX=19.0,59.3,31.6,70.1 ;;
        norway) COUNTRY=NO BBOX=4.0,57.9,31.2,71.2 ;;
        sweden) COUNTRY=SE BBOX=10.9,55.3,24.2,69.1 ;;
        *)
            echo "Unknown region '$1'. Known regions: ${REGIONS[*]}" >&2
            exit 1
            ;;
    esac
}

PMTILES_VERSION=1.31.2
FONTSTACKS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")
DIGIROAD=https://tie.digitraffic.fi/roadnetwork
COMPOSE=(docker compose -f "$(dirname "$0")/../compose.yaml")

DATA=$(cd "$(dirname "$0")/.." && pwd)/data
# Every directory the containers mount, so Docker doesn't create them as root.
mkdir -p "$DATA"/{osm,valhalla,photon,tiles,assets,bin,routing-tiles,datapacks,digiroad,nvdb}

# Search dumps used to be one file, for Finland, the only region then; the extracts were Valhalla's.
if [ -f "$DATA/photon/import.jsonl.zst" ] && [ ! -f "$DATA/photon/finland.jsonl.zst" ]; then
    mv "$DATA/photon/import.jsonl.zst" "$DATA/photon/finland.jsonl.zst"
fi
for extract in "$DATA"/valhalla/*-latest.osm.pbf; do
    [ -f "$extract" ] && mv "$extract" "$DATA/osm/"
done

if [ $# -gt 0 ]; then
    for region in "$@"; do region_settings "$region"; done
    WANTED=("$@")
else
    WANTED=()
    for region in "${REGIONS[@]}"; do
        [ -f "$DATA/osm/$region-latest.osm.pbf" ] && WANTED+=("$region")
    done
    [ ${#WANTED[@]} -gt 0 ] || WANTED=(finland)
fi

download() { # url destination
    if [ -f "$2" ]; then
        echo "✓ $(basename "$2") already downloaded"
        return
    fi
    echo "↓ $1"
    curl -fL --retry 3 -C - -o "$2.part" "$1"
    mv "$2.part" "$2"
}

for region in "${WANTED[@]}"; do
    region_settings "$region"
    download "$PBF_URL" "$DATA/osm/$region-latest.osm.pbf"
    # Search index dump. The Photon container imports every dump in data/photon/.
    download "$PHOTON_URL" "$DATA/photon/$region.jsonl.zst"
done

# Every region downloaded, also those from earlier runs: the stack serves them all.
ALL=()
for region in "${REGIONS[@]}"; do
    [ -f "$DATA/osm/$region-latest.osm.pbf" ] && ALL+=("$region")
done
CODES=$(for region in "${ALL[@]}"; do region_settings "$region"; echo "$COUNTRY"; done | paste -sd, -)

# Valhalla builds its graph from every .pbf in data/valhalla/, and neighbouring extracts overlap at
# the border (and on ferries between them), which breaks the build: it gets them merged into one.
# The merged file is named after what went into it, as Valhalla only rebuilds for new file names.
EXTRACTS=("${ALL[@]/#/$DATA/osm/}")
EXTRACTS=("${EXTRACTS[@]/%/-latest.osm.pbf}")
KEY=$(stat -c '%n %s %Y' "${EXTRACTS[@]}" | sha256sum | cut -c1-12)
if [ -f "$DATA/valhalla/region-$KEY.osm.pbf" ]; then
    echo "✓ extracts already merged for routing"
else
    # Valhalla serves its graph from valhalla_tiles.tar, which it makes only when there's none: the
    # old one would go on being served after the new graph is built.
    rm -f "$DATA"/valhalla/*.osm.pbf "$DATA/valhalla/valhalla_tiles.tar"
    if [ ${#ALL[@]} -eq 1 ]; then
        ln "${EXTRACTS[0]}" "$DATA/valhalla/region-$KEY.osm.pbf" 2>/dev/null || cp "${EXTRACTS[0]}" "$DATA/valhalla/region-$KEY.osm.pbf"
    else
        echo "⚙ merging the extracts for routing"
        INPUTS=("${ALL[@]/#//osm/}")
        "${COMPOSE[@]}" run --rm --build osm-merge "${INPUTS[@]/%/-latest.osm.pbf}" -o "/valhalla/region-$KEY.osm.pbf.part" -f pbf
        mv "$DATA/valhalla/region-$KEY.osm.pbf.part" "$DATA/valhalla/region-$KEY.osm.pbf"
    fi
fi

# Map tiles: the regions cut out of the latest daily Protomaps planet build, as one archive. A
# different set of regions needs a new cut.
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
# Archives cut before there was more than one region are Finland's.
[ -f "$DATA/tiles/region.pmtiles" ] && [ ! -f "$DATA/tiles/region.codes" ] && echo FI > "$DATA/tiles/region.codes"
if [ -f "$DATA/tiles/region.pmtiles" ] && [ "$(cat "$DATA/tiles/region.codes")" = "$CODES" ]; then
    echo "✓ region.pmtiles already extracted"
else
    BUILD=$(curl -fsS https://build-metadata.protomaps.dev/builds.json \
        | python3 -c 'import json, sys; print(json.load(sys.stdin)[-1]["key"])')
    # The regions' boxes, as one GeoJSON MultiPolygon.
    AREA="$DATA/tiles/region.geojson"
    for region in "${ALL[@]}"; do region_settings "$region"; echo "$BBOX"; done | python3 -c '
import json, sys
boxes = [[float(v) for v in line.split(",")] for line in sys.stdin if line.strip()]
polygons = [[[[w, s], [e, s], [e, n], [w, n], [w, s]]] for w, s, e, n in boxes]
json.dump({"type": "MultiPolygon", "coordinates": polygons}, sys.stdout)' > "$AREA"
    echo "↓ tiles for $CODES from Protomaps build $BUILD"
    "$PMTILES" extract "https://build.protomaps.com/$BUILD" "$DATA/tiles/region.pmtiles.part" --region="$AREA"
    mv "$DATA/tiles/region.pmtiles.part" "$DATA/tiles/region.pmtiles"
    echo "$CODES" > "$DATA/tiles/region.codes"
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

# Finland's official speed limits, ahead of OpenStreetMap's where both have one. Digiroad moved
# from Väylävirasto to Fintraffic in 2026 (Digitraffic Road Network); its releases are zips per
# region, half a gigabyte each, of which only the two speed limit layers are needed: zip-extract.py
# reads just those with range requests (about 1 GB for Finland). A newer release replaces the old
# data, and the packs are rebuilt from it.
list() { curl -fsS --retry 3 "$DIGIROAD/?list-type=2&delimiter=/&prefix=$1"; }
if [[ " ${WANTED[*]} " == *" finland "* ]]; then
    # The newest release in the old Digiroad K format (digiroad/2026_02 and so on).
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
        rm -f "$DATA/datapacks/FI/cameras.json"
    fi
    # From spring 2027 Fintraffic publishes Road Network releases in its own format (DATEX II, after
    # the EU's RTTI regulation), which needs a reader next to infra/datapacks/digiroad.py. Until
    # then, and until someone writes it, the last Digiroad release stays in use: say so when newer
    # data is there.
    NEWER=$( (list releases/; list latest/) | grep -oE '<(Key|Prefix)>[^<]+</(Key|Prefix)>' | sed -E 's#</?(Key|Prefix)>##g' \
        | grep -v -E '^(releases|latest)/(\.keep)?$' || true)
    if [ -n "$NEWER" ]; then
        echo "! Fintraffic has published Road Network data in its new format, which FoxNav doesn't read yet:" >&2
        echo "$NEWER" | head -5 | sed 's/^/    /' >&2
        echo "  Finland's speed limits stay from Digiroad ${RELEASE:-(none)}. See infra/datapacks/digiroad.py." >&2
    fi
fi

# Norway's official speed limits and speed cameras, from NVDB's open API. Refreshed monthly;
# the packs are rebuilt from a new download.
if [[ " ${WANTED[*]} " == *" norway "* ]]; then
    BEFORE=$(cat "$DATA/nvdb/.complete" 2>/dev/null || true)
    python3 -I "$(dirname "$0")/nvdb-fetch.py" "$DATA/nvdb"
    [ "$(cat "$DATA/nvdb/.complete" 2>/dev/null || true)" = "$BEFORE" ] || rm -f "$DATA/datapacks/NO/cameras.json"
fi

for region in "${WANTED[@]}"; do
    region_settings "$region"
    # Speed cameras and speed limits from the OpenStreetMap extract and the national database
    # (protocol/SPEC.md §3). Delete the files to rebuild after fetching newer data.
    if [ -f "$DATA/datapacks/$COUNTRY/cameras.json" ]; then
        echo "✓ $COUNTRY data packs already built"
    else
        echo "⚙ $COUNTRY data packs"
        "${COMPOSE[@]}" run --rm --build datapacks --region "$COUNTRY" --digiroad /digiroad --nvdb /nvdb \
            --prefer "${FOXNAV_SPEED_LIMITS_PREFER:-official}" "/osm/$region-latest.osm.pbf" /out
    fi
    # The search pack for searching on the phone (protocol/SPEC.md §3.4), from the Photon dump.
    # Building from an unchanged dump is a no-op.
    echo "⚙ $COUNTRY search pack"
    "${COMPOSE[@]}" run --rm --build search-pack --region "$COUNTRY" "/photon/$region.jsonl.zst" /out
done

# The routing graph's tiles for routing on the phone (protocol/SPEC.md §3.3). Valhalla builds the graph
# when it starts, so after new extracts there's nothing to publish yet: run the routing-tiles service
# once it has (see infra/README.md). Valhalla notes the extracts' hash once a build succeeds; until
# then the tiles may be a failed or unfinished build, which phones mustn't get. Publishing an
# unchanged graph is a no-op.
BUILT=$(printf '%s' "/custom_files/region-$KEY.osm.pbf" | sha256sum | cut -d' ' -f1)
if [ -d "$DATA/valhalla/valhalla_tiles/0" ] && grep -q "$BUILT" "$DATA/valhalla/file_hashes.txt" 2>/dev/null; then
    echo "⚙ routing tiles"
    "${COMPOSE[@]}" run --rm --build routing-tiles
else
    echo "✗ Valhalla hasn't built its graph from these extracts yet: start the stack, then publish it with" >&2
    echo "  docker compose -f infra/compose.yaml run --rm routing-tiles" >&2
fi

# The regions the server has data for, in its discovery document.
echo "FOXNAV_REGIONS=$CODES" > "$DATA/regions.env"

echo "Done. Data for $CODES is in $DATA ($(du -sh "$DATA" | cut -f1))."
