#!/bin/sh
# Imports the search index from /photon/import.jsonl.zst on first start, then serves it.
set -eu

if [ ! -d /photon/photon_data ]; then
    if [ ! -f /photon/import.jsonl.zst ]; then
        echo "No search data: run infra/scripts/fetch-data.sh first." >&2
        exit 1
    fi
    echo "Importing search index (takes a few minutes)..."
    zstd --stdout -d /photon/import.jsonl.zst \
        | java -jar /opt/photon.jar import -import-file - -languages "${PHOTON_LANGUAGES:-en}"
fi

exec java -jar /opt/photon.jar serve -listen-ip 0.0.0.0 -listen-port 2322 -cors-any
