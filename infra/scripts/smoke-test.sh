#!/usr/bin/env bash
# Checks that every service of the local stack answers through the gateway.
# Usage: infra/scripts/smoke-test.sh [base-url]     (default http://localhost:8080)
set -uo pipefail

BASE=${1:-http://localhost:8080}
failures=0

check() { # description, command...
    local description=$1
    shift
    if output=$("$@" 2>&1); then
        echo "✓ $description${output:+: $output}"
    else
        echo "✗ $description${output:+: $output}"
        failures=$((failures + 1))
    fi
}

json() { # url, python expression over `d` -> printed value (empty/false fails)
    curl -fsS --max-time 30 "$1" | python3 -c "import json, sys; d = json.load(sys.stdin); v = $2; print(v) if v else sys.exit(1)"
}

route() { # from-lat from-lon to-lat to-lon
    curl -fsS --max-time 60 "$BASE/valhalla/route" -H 'Content-Type: application/json' \
        -d "{\"locations\":[{\"lat\":$1,\"lon\":$2},{\"lat\":$3,\"lon\":$4}],\"costing\":\"auto\"}" \
        | python3 -c 'import json, sys; s = json.load(sys.stdin)["trip"]["summary"]; print("%.0f km, %.0f min" % (s["length"], s["time"] / 60))'
}

# A route and a place to search for in each region the stack can serve.
region_samples() { # code -> ROUTE (name, from-lat, from-lon, to-lat, to-lon) and PLACE
    case "$1" in
        FI) ROUTE=("Helsinki -> Tampere" 60.1699 24.9384 61.4978 23.7610) PLACE=Tampere ;;
        NO) ROUTE=("Oslo -> Lillehammer" 59.9139 10.7522 61.1153 10.4662) PLACE=Bergen ;;
        SE) ROUTE=("Stockholm -> Uppsala" 59.3293 18.0686 59.8586 17.6389) PLACE=Göteborg ;;
        *) ROUTE=() PLACE= ;;
    esac
}

# The convoy endpoint must upgrade to a WebSocket through the gateway (101 Switching Protocols).
realtime_upgrade() {
    local path code
    path=$(json "$BASE/.well-known/foxnav" 'd.get("realtime")') || { echo "no realtime endpoint in the discovery document"; return 1; }
    # After the upgrade the connection stays open, so curl always ends on its timeout.
    code=$(curl -s --max-time 2 -o /dev/null -w '%{http_code}' "$BASE$path" \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
        -H 'Sec-WebSocket-Key: Zm94bmF2LXNtb2tlLXRlc3Q=' -H 'Sec-WebSocket-Protocol: foxnav.v0')
    [ "$code" = 101 ] && echo "$path upgrades" || { echo "HTTP $code from $path"; return 1; }
}

range_request() {
    local code
    code=$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' -r 0-126 "$BASE/tiles/region.pmtiles")
    [ "$code" = 206 ] && echo "HTTP 206" || { echo "HTTP $code"; return 1; }
}

check "discovery document" json "$BASE/.well-known/foxnav" 'd["name"] + " (styles: " + ", ".join(d.get("styles", {})) + ")"'
check "night style" json "$BASE/styles/night.json" 'd["sources"]["protomaps"]["tiles"][0]'
check "map tile" curl -fsS --max-time 30 -o /dev/null "$BASE/tiles/region/14/9330/4741.mvt"
check "map tiles (range request)" range_request
check "fonts" curl -fsS --max-time 30 -o /dev/null "$BASE/assets/fonts/Noto%20Sans%20Regular/0-255.pbf"
check "sprites" json "$BASE/assets/sprites/v4/dark.json" 'f"{len(d)} icons"'
check "convoy realtime endpoint" realtime_upgrade

# Each region the discovery document lists: routing, search and its data packs.
REGIONS=$(json "$BASE/.well-known/foxnav" '" ".join(d.get("regions", [])) or "-"' 2>/dev/null || echo -)
for code in $REGIONS; do
    [ "$code" = - ] && break
    region_samples "$code"
    if [ ${#ROUTE[@]} -gt 0 ]; then
        check "routing: ${ROUTE[0]}" route "${ROUTE[@]:1}"
        check "search: $PLACE" json "$BASE/photon/api?q=$PLACE&limit=1" 'd["features"][0]["properties"]["name"]'
    fi
    check "camera pack: $code" json "$BASE/datapacks/$code/cameras.json" \
        'str(len(d["cameras"])) + " cameras, " + str(sum(1 for c in d["cameras"] if c["kind"] == "average_speed")) + " average speed sections"'
    check "speed limit pack: $code" json "$BASE/datapacks/$code/speedlimits.json" 'str(len(d["ways"])) + " ways with their own limits"'
    check "search pack: $code" json "$BASE/datapacks/$code/search/manifest.json" 'str(sum(c[1] for c in d["cells"])) + " places in " + str(len(d["cells"])) + " cells"'
done
# Across a border, where the regions' extracts were merged for the routing graph.
if [[ " $REGIONS " == *" NO "* && " $REGIONS " == *" SE "* ]]; then
    check "routing: Oslo -> Stockholm" route 59.9139 10.7522 59.3293 18.0686
fi
check "routing tiles" json "$BASE/routing-tiles/manifest.json" '"build " + d["build"] + ", " + str(sum(len(t) for t in d["tiles"].values())) + " tiles"'
check "reports" json "$BASE/v1/reports" 'str(len(d["reports"])) + " reports (" + str(sum(1 for r in d["reports"] if r.get("source"))) + " official)"'

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed. Services still building their data answer with 502; see infra/README.md."
    exit 1
fi
echo "All services are up."
