#!/bin/sh
# Imports the search index from every /photon/<region>.jsonl.zst on first start, then serves it.
# When the dumps change (a new region, or newer data), the index is imported again.
set -eu

DUMPS=$(ls /photon/*.jsonl.zst 2>/dev/null || true)
if [ -z "$DUMPS" ]; then
    echo "No search data: run infra/scripts/fetch-data.sh first." >&2
    exit 1
fi
# What the index was imported from: each dump's name, size and time.
IMPORTED=$(for dump in $DUMPS; do stat -c '%n %s %Y' "$dump"; done)
if [ -d /photon/photon_data ] && [ "$(cat /photon/photon_data/.foxnav-dumps 2>/dev/null)" != "$IMPORTED" ]; then
    # An index from before this was recorded came from Finland's dump alone.
    if [ ! -f /photon/photon_data/.foxnav-dumps ] && [ "$DUMPS" = /photon/finland.jsonl.zst ]; then
        echo "$IMPORTED" > /photon/photon_data/.foxnav-dumps
    else
        echo "The search dumps changed: importing the index again."
        rm -rf /photon/photon_data
    fi
fi

if [ ! -d /photon/photon_data ]; then
    echo "Importing search index from $(echo $DUMPS | xargs -n1 basename | paste -sd' ' -) (takes a few minutes)..."
    # One stream: Photon skips the headers of the dumps after the first.
    zstd --stdout -d $DUMPS \
        | java -jar /opt/photon.jar import -import-file - -languages "${PHOTON_LANGUAGES:-en}"
    echo "$IMPORTED" > /photon/photon_data/.foxnav-dumps
fi

exec java -jar /opt/photon.jar serve -listen-ip 0.0.0.0 -listen-port 2322 -cors-any
