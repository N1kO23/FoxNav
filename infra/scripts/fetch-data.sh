#!/usr/bin/env bash
# Downloads what the local map stack needs into infra/data/. Safe to re-run: finished files are
# skipped and interrupted downloads resume.
#
#   osm/<region>-latest.osm.pbf        OpenStreetMap extracts (Geofabrik)
#   osm/poly/<area>.poly               Europe: its outline, for the map, and each country's, for its data packs
#   valhalla/region-<key>.osm.pbf      the extracts merged into one, for Valhalla's routing graph; removed
#                                      once Valhalla has built its graph from it
#   photon/<dump>.jsonl.zst            search index dumps (GraphHopper)
#   tiles/region.pmtiles               vector map tiles for all the regions, cut from the Protomaps planet build
#   assets/fonts, assets/sprites       label fonts and map icons (Protomaps basemaps-assets)
#   digiroad/<area>/                   Finland only: official speed limits from Digiroad, now
#                                      Fintraffic's Digitraffic Road Network (CC BY 4.0)
#   nvdb/                              Norway only: official speed limits and speed cameras from
#                                      NVDB, Statens vegvesen's road database (NLOD 2.0)
#   regions.env                        the regions' country codes, for the server
#
# Usage: infra/scripts/fetch-data.sh [region...]    regions: finland, norway, sweden, europe
# Without regions, it updates the ones already downloaded (Finland the first time). A region
# stays until its files are deleted: osm/<region>-latest.osm.pbf and its search dumps.
#
# Europe covers the other three: with it, the routing graph and the map are Europe's, and each of its
# countries gets data packs and a search pack. Finland and Norway come along with their own extracts,
# as matching their official speed limits to the map takes every road, which Europe's packs leave out.
set -euo pipefail

REGIONS=(finland norway sweden europe)

region_settings() { # region -> COUNTRY (none for Europe), PBF_URL, PHOTON_URL, and BBOX or POLY_URL
    PBF_URL=https://download.geofabrik.de/europe/$1-latest.osm.pbf
    PHOTON_URL=https://download1.graphhopper.com/public/europe/$1/photon-dump-$1-1.0-latest.jsonl.zst
    BBOX="" POLY_URL=""
    case "$1" in
        finland) COUNTRY=FI BBOX=19.0,59.3,31.6,70.1 ;;
        norway) COUNTRY=NO BBOX=4.0,57.9,31.2,71.2 ;;
        sweden) COUNTRY=SE BBOX=10.9,55.3,24.2,69.1 ;;
        europe) COUNTRY="" PBF_URL=https://download.geofabrik.de/europe-latest.osm.pbf PHOTON_URL="" POLY_URL=https://download.geofabrik.de/europe.poly ;;
        *)
            echo "Unknown region '$1'. Known regions: ${REGIONS[*]}" >&2
            exit 1
            ;;
    esac
}

# Europe's countries: their code, Geofabrik's name for them (whose outline cuts them out of the Europe
# extract for their data packs) and GraphHopper's search dump with them in, which some share. The
# Azores and the Crown Dependencies have no outline of their own here.
EUROPE=(
    "AD andorra andorra" "AL albania albania" "AT austria austria" "BA bosnia-herzegovina bosnia-herzegovina"
    "BE belgium belgium" "BG bulgaria bulgaria" "BY belarus belarus" "CH switzerland switzerland-liechtenstein"
    "CY cyprus cyprus" "CZ czech-republic czech-republic" "DE germany germany" "DK denmark denmark"
    "EE estonia baltics" "ES spain spain" "FI finland finland" "FO faroe-islands iceland-faroe"
    "FR france france-monacco" "GB united-kingdom british-islands" "GE georgia georgia" "GR greece greece"
    "HR croatia croatia" "HU hungary hungary" "IE ireland-and-northern-ireland ireland" "IS iceland iceland-faroe"
    "IT italy italy" "LI liechtenstein switzerland-liechtenstein" "LT lithuania baltics" "LU luxembourg luxemburg"
    "LV latvia baltics" "MC monaco france-monacco" "MD moldova moldova" "ME montenegro montenegro"
    "MK macedonia macedonia" "MT malta malta" "NL netherlands netherlands" "NO norway norway"
    "PL poland poland" "PT portugal portugal" "RO romania romania" "RS serbia serbia"
    "SE sweden sweden" "SI slovenia slovenia" "SK slovakia slovakia" "TR turkey turkey"
    "UA ukraine ukraine" "XK kosovo kosovo"
)
dump_url() { echo "https://download1.graphhopper.com/public/europe/$1/photon-dump-$1-1.0-latest.jsonl.zst"; }

PMTILES_VERSION=1.31.2
FONTSTACKS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")
DIGIROAD=https://tie.digitraffic.fi/roadnetwork
COMPOSE=(docker compose -f "$(dirname "$0")/../compose.yaml")

DATA=$(cd "$(dirname "$0")/.." && pwd)/data
# Every directory the containers mount, so Docker doesn't create them as root.
mkdir -p "$DATA"/{osm,osm/poly,valhalla,photon,tiles,assets,bin,routing-tiles,datapacks,digiroad,nvdb}

# Search dumps used to be one file, for Finland, the only region then; the extracts were Valhalla's.
if [ -f "$DATA/photon/import.jsonl.zst" ] && [ ! -f "$DATA/photon/finland.jsonl.zst" ]; then
    mv "$DATA/photon/import.jsonl.zst" "$DATA/photon/finland.jsonl.zst"
fi
for extract in "$DATA"/valhalla/*-latest.osm.pbf; do
    [ -f "$extract" ] && mv "$extract" "$DATA/osm/"
done

has() { # word list...: whether word is in the list
    local word=$1
    shift
    [[ " $* " == *" $word "* ]]
}

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
if has europe "${WANTED[@]}"; then
    for region in finland norway; do has "$region" "${WANTED[@]}" || WANTED+=("$region"); done
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
    if [ "$region" = europe ]; then
        download "$POLY_URL" "$DATA/osm/poly/europe.poly"
        # Search index dumps, some shared by several countries, and each country's outline.
        DUMPS=()
        for entry in "${EUROPE[@]}"; do
            read -r _ area dump <<<"$entry"
            [ -f "$DATA/osm/poly/$area.poly" ] || download "https://download.geofabrik.de/europe/$area.poly" "$DATA/osm/poly/$area.poly"
            has "$dump" "${DUMPS[@]}" || DUMPS+=("$dump")
        done
        for dump in "${DUMPS[@]}"; do download "$(dump_url "$dump")" "$DATA/photon/$dump.jsonl.zst"; done
    else
        # Search index dump. The Photon container imports every dump in data/photon/.
        download "$PHOTON_URL" "$DATA/photon/$region.jsonl.zst"
    fi
done

# Every region downloaded, also those from earlier runs: the stack serves them all.
ALL=()
for region in "${REGIONS[@]}"; do
    [ -f "$DATA/osm/$region-latest.osm.pbf" ] && ALL+=("$region")
done
CODES=$(
    for region in "${ALL[@]}"; do
        region_settings "$region"
        if [ "$region" = europe ]; then
            for entry in "${EUROPE[@]}"; do echo "${entry%% *}"; done
        else
            echo "$COUNTRY"
        fi
    done | sort -u | paste -sd, -
)
# Europe's extract has the others in: the routing graph and the map need it alone.
if has europe "${ALL[@]}"; then MAPPED=(europe); else MAPPED=("${ALL[@]}"); fi

# Valhalla builds its graph from every .pbf in data/valhalla/, and neighbouring extracts overlap at
# the border (and on ferries between them), which breaks the build: it gets them merged into one.
# The merged file is named after what went into it, as Valhalla only rebuilds for new file names,
# and it notes the names it built from (by their hash) once a build succeeds.
EXTRACTS=("${MAPPED[@]/#/$DATA/osm/}")
EXTRACTS=("${EXTRACTS[@]/%/-latest.osm.pbf}")
KEY=$(stat -c '%n %s %Y' "${EXTRACTS[@]}" | sha256sum | cut -c1-12)
BUILT=$(printf '%s' "/custom_files/region-$KEY.osm.pbf" | sha256sum | cut -d' ' -f1)
graph_built() { [ -f "$DATA/valhalla/valhalla_tiles.tar" ] && grep -q "$BUILT" "$DATA/valhalla/file_hashes.txt" 2>/dev/null; }
if graph_built; then
    echo "✓ routing graph already built from these extracts"
elif [ -f "$DATA/valhalla/region-$KEY.osm.pbf" ]; then
    echo "✓ extracts already merged for routing"
else
    # Valhalla serves its graph from valhalla_tiles.tar, which it makes only when there's none: the
    # old one would go on being served after the new graph is built. And the old tile folder would
    # keep tiles the new graph doesn't have.
    rm -rf "$DATA"/valhalla/*.osm.pbf "$DATA/valhalla/valhalla_tiles.tar" "$DATA/valhalla/valhalla_tiles"
    if [ ${#EXTRACTS[@]} -eq 1 ]; then
        ln "${EXTRACTS[0]}" "$DATA/valhalla/region-$KEY.osm.pbf" 2>/dev/null || cp "${EXTRACTS[0]}" "$DATA/valhalla/region-$KEY.osm.pbf"
    else
        echo "⚙ merging the extracts for routing"
        INPUTS=("${MAPPED[@]/#//osm/}")
        "${COMPOSE[@]}" run --rm --build osmium merge --overwrite "${INPUTS[@]/%/-latest.osm.pbf}" -o "/valhalla/region-$KEY.osm.pbf.part" -f pbf
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
    # The area as one GeoJSON MultiPolygon: Europe's outline, or the regions' boxes.
    AREA="$DATA/tiles/region.geojson"
    if [ "${MAPPED[*]}" = europe ]; then
        python3 -I - "$DATA/osm/poly/europe.poly" >"$AREA" <<'EOF'
import json, sys
# Osmosis polygon format: a name, then rings of "lon lat" lines, each ending in END; holes start with "!".
lines = [line.strip() for line in open(sys.argv[1]) if line.strip()][1:]
polygons, ring, hole = [], None, False
for line in lines:
    if ring is None:
        if line == "END":
            break
        ring, hole = [], line.startswith("!")
    elif line == "END":
        ring.append(ring[0])
        if hole:
            polygons[-1].append(ring)
        else:
            polygons.append([ring])
        ring = None
    else:
        lon, lat = (float(value) for value in line.split()[:2])
        ring.append([lon, lat])
json.dump({"type": "MultiPolygon", "coordinates": polygons}, sys.stdout)
EOF
    else
        for region in "${MAPPED[@]}"; do region_settings "$region"; echo "$BBOX"; done | python3 -c '
import json, sys
boxes = [[float(v) for v in line.split(",")] for line in sys.stdin if line.strip()]
polygons = [[[[w, s], [e, s], [e, n], [w, n], [w, s]]] for w, s, e, n in boxes]
json.dump({"type": "MultiPolygon", "coordinates": polygons}, sys.stdout)' >"$AREA"
    fi
    echo "↓ tiles for $CODES from Protomaps build $BUILD"
    "$PMTILES" extract "https://build.protomaps.com/$BUILD" "$DATA/tiles/region.pmtiles.part" --region="$AREA"
    mv "$DATA/tiles/region.pmtiles.part" "$DATA/tiles/region.pmtiles"
    echo "$CODES" >"$DATA/tiles/region.codes"
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
if has finland "${WANTED[@]}"; then
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
        echo "$RELEASE" >"$DATA/digiroad.new/.complete"
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
if has norway "${WANTED[@]}"; then
    BEFORE=$(cat "$DATA/nvdb/.complete" 2>/dev/null || true)
    python3 -I "$(dirname "$0")/nvdb-fetch.py" "$DATA/nvdb"
    [ "$(cat "$DATA/nvdb/.complete" 2>/dev/null || true)" = "$BEFORE" ] || rm -f "$DATA/datapacks/NO/cameras.json"
fi

# A country's search pack for searching on the phone (protocol/SPEC.md §3.4), from the Photon dump
# with its places in. Built again only after a newer dump.
search_pack() { # code dump
    local manifest="$DATA/datapacks/$1/search/manifest.json"
    if [ -f "$manifest" ] && [ "$manifest" -nt "$DATA/photon/$2.jsonl.zst" ]; then
        echo "✓ $1 search pack already built"
        return
    fi
    echo "⚙ $1 search pack"
    "${COMPOSE[@]}" run --rm --build search-pack --region "$1" "/photon/$2.jsonl.zst" /out || return 1
    # Unchanged places leave the pack as it was: note that it's up to date with this dump.
    touch "$manifest"
}

OWN=() # countries with an extract of their own here
for region in "${WANTED[@]}"; do
    [ "$region" = europe ] && continue
    region_settings "$region"
    OWN+=("$COUNTRY")
    # Speed cameras and speed limits from the OpenStreetMap extract and the national database
    # (protocol/SPEC.md §3). Delete the files to rebuild after fetching newer data.
    if [ -f "$DATA/datapacks/$COUNTRY/cameras.json" ]; then
        echo "✓ $COUNTRY data packs already built"
    else
        echo "⚙ $COUNTRY data packs"
        "${COMPOSE[@]}" run --rm --build datapacks --region "$COUNTRY" --digiroad /digiroad --nvdb /nvdb \
            --prefer "${FOXNAV_SPEED_LIMITS_PREFER:-official}" "/osm/$region-latest.osm.pbf" /out
    fi
    search_pack "$COUNTRY" "$region"
done

# Europe's other countries: their data packs from what of the Europe extract they need, speed
# cameras, enforcement relations and ways with conditional limits (all that the packs take where
# there's no official data), split by country. A small file, so splitting it is quick; it goes when
# the packs are built. One country failing doesn't stop the others.
if has europe "${WANTED[@]}"; then
    PENDING=()
    for entry in "${EUROPE[@]}"; do
        read -r code _ _ <<<"$entry"
        if has "$code" "${OWN[@]}"; then continue; fi
        if [ -f "$DATA/datapacks/$code/cameras.json" ]; then echo "✓ $code data packs already built"; else PENDING+=("$entry"); fi
    done
    FAILED=()
    if [ ${#PENDING[@]} -gt 0 ]; then
        echo "⚙ cutting what ${#PENDING[@]} countries' data packs need out of the Europe extract"
        rm -rf "$DATA/osm/split"
        mkdir -p "$DATA/osm/split"
        "${COMPOSE[@]}" build -q osmium datapacks
        "${COMPOSE[@]}" run --rm osmium tags-filter /osm/europe-latest.osm.pbf \
            n/highway=speed_camera r/type=enforcement w/maxspeed:conditional -o /osm/split/packs.osm.pbf --overwrite
        {
            echo '{"directory": "/osm/split", "extracts": ['
            for entry in "${PENDING[@]}"; do
                read -r code area _ <<<"$entry"
                echo "{\"output\": \"$code.osm.pbf\", \"polygon\": {\"file_name\": \"/osm/poly/$area.poly\", \"file_type\": \"poly\"}},"
            done | sed '$ s/,$//'
            echo ']}'
        } >"$DATA/osm/split/extracts.json"
        # Simple: a way on a border goes to both countries, with each one's part.
        "${COMPOSE[@]}" run --rm osmium extract --strategy simple --config /osm/split/extracts.json /osm/split/packs.osm.pbf --overwrite
        for entry in "${PENDING[@]}"; do
            read -r code _ _ <<<"$entry"
            echo "⚙ $code data packs"
            "${COMPOSE[@]}" run --rm datapacks --region "$code" --prefer "${FOXNAV_SPEED_LIMITS_PREFER:-official}" \
                "/osm/split/$code.osm.pbf" /out || FAILED+=("$code")
        done
        rm -rf "$DATA/osm/split"
    fi
    for entry in "${EUROPE[@]}"; do
        read -r code _ dump <<<"$entry"
        if has "$code" "${OWN[@]}"; then continue; fi
        search_pack "$code" "$dump" || FAILED+=("$code")
    done
    [ ${#FAILED[@]} -eq 0 ] || echo "✗ no data or search packs for ${FAILED[*]}: see above, and run again to retry" >&2
fi

# The routing graph's tiles for routing on the phone (protocol/SPEC.md §3.3). Valhalla builds the graph
# when it starts, so after new extracts there's nothing to publish yet: run the routing-tiles service
# once it has (see infra/README.md). Until Valhalla notes the extracts it built from, the tiles may be
# a failed or unfinished build, which phones mustn't get. Publishing an unchanged graph is a no-op.
if graph_built; then
    echo "⚙ routing tiles"
    "${COMPOSE[@]}" run --rm --build routing-tiles
    # Valhalla serves the graph from valhalla_tiles.tar, and the phones' tiles come from it too. The
    # folder Valhalla made the tar from, and the extract it built the graph from, are copies: it builds
    # again only for an extract it hasn't built from, or with neither a tar nor a folder. The folder
    # stays if the tar is short of tiles, as from an interrupted start: delete the tar to rebuild then.
    if [ -d "$DATA/valhalla/valhalla_tiles" ]; then
        IN_FOLDER=$(find "$DATA/valhalla/valhalla_tiles" -name '*.gph' | wc -l)
        IN_TAR=$(tar -tf "$DATA/valhalla/valhalla_tiles.tar" | grep -c '\.gph$' || true)
        if [ "$IN_FOLDER" -eq "$IN_TAR" ]; then
            rm -rf "$DATA/valhalla/valhalla_tiles"
            echo "✓ removed the routing graph's tile folder: valhalla_tiles.tar has all $IN_TAR tiles"
        else
            echo "! valhalla_tiles.tar has $IN_TAR of the $IN_FOLDER tiles Valhalla built: keeping the folder" >&2
        fi
    fi
    if compgen -G "$DATA/valhalla/*.osm.pbf" >/dev/null; then
        rm -f "$DATA"/valhalla/*.osm.pbf
        echo "✓ removed the merged extract the routing graph was built from"
    fi
else
    echo "✗ Valhalla hasn't built its graph from these extracts yet: start the stack, then publish it with" >&2
    echo "  docker compose -f infra/compose.yaml run --rm routing-tiles, or run this again" >&2
fi

# The regions the server has data for, in its discovery document.
echo "FOXNAV_REGIONS=$CODES" >"$DATA/regions.env"

echo "Done. Data for $CODES is in $DATA ($(du -sh "$DATA" | cut -f1))."
