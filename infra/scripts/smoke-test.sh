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

route_helsinki_tampere() {
    curl -fsS --max-time 30 "$BASE/valhalla/route" -H 'Content-Type: application/json' \
        -d '{"locations":[{"lat":60.1699,"lon":24.9384},{"lat":61.4978,"lon":23.7610}],"costing":"auto"}' \
        | python3 -c 'import json, sys; s = json.load(sys.stdin)["trip"]["summary"]; print("%.0f km, %.0f min" % (s["length"], s["time"] / 60))'
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
check "night style" json "$BASE/styles/night.json" 'd["sources"]["protomaps"]["url"]'
check "map tiles (range request)" range_request
check "fonts" curl -fsS --max-time 30 -o /dev/null "$BASE/assets/fonts/Noto%20Sans%20Regular/0-255.pbf"
check "sprites" json "$BASE/assets/sprites/v4/dark.json" 'f"{len(d)} icons"'
check "routing: Helsinki -> Tampere" route_helsinki_tampere
check "search: Tampere" json "$BASE/photon/api?q=Tampere&limit=1" 'd["features"][0]["properties"]["name"]'
check "convoy realtime endpoint" realtime_upgrade
check "camera pack: FI" json "$BASE/datapacks/FI/cameras.json" 'str(len(d["cameras"])) + " cameras"'
check "reports" json "$BASE/v1/reports" 'str(len(d["reports"])) + " reports (" + str(sum(1 for r in d["reports"] if r.get("source"))) + " official)"'

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed. Services still building their data answer with 502; see infra/README.md."
    exit 1
fi
echo "All services are up."
