#!/usr/bin/env python3
"""Builds a search pack (protocol/SPEC.md §3.4) from a Photon dump, for searching without a connection.

    build-search-pack --region FI import.jsonl.zst output-dir

Writes output-dir/FI/search/<build>/<cell>.json.gz, the region's places, streets, addresses and
named points of interest in cells of 0.25°, numbered like Valhalla's local-road tiles, and
output-dir/FI/search/manifest.json, which lists the cells. The build is a hash of the cells:
building from the same dump again changes nothing. The previous build stays for clients still
downloading it; older ones are removed.

Map data © OpenStreetMap contributors, ODbL.
"""
import argparse
import collections
import gzip
import hashlib
import json
import os
import shutil
import subprocess

CELL = 0.25
COLUMNS = int(360 / CELL)
MANIFEST = "manifest.json"

# What a driver searches for. Rivers, lakes, forests and boundaries are left out.
PLACES = {"city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood", "borough", "locality", "island", "square"}
STREETS = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
           "service", "road", "pedestrian"}
POI_KEYS = {"amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "historic", "building",
            "aeroway", "railway", "public_transport"}
NAME_VARIANTS = ("name:fi", "name:sv", "name:en", "name:se", "alt_name", "official_name", "short_name", "old_name")


def documents(dump):
    """Every place in a Photon dump (.jsonl or .jsonl.zst)."""
    if dump.endswith(".zst"):
        process = subprocess.Popen(["zstd", "-dc", dump], stdout=subprocess.PIPE)
        lines = process.stdout
    else:
        process, lines = None, open(dump, "rb")
    header = None
    for line in lines:
        record = json.loads(line)
        if record["type"] == "NominatimDumpFile":
            header = record["content"]
        elif record["type"] == "Place":
            for content in record["content"]:
                yield header, content
    if process and process.wait() != 0:
        raise SystemExit(f"zstd couldn't read {dump}")


def entry(place):
    """The pack's entry for one place: [kind, title, detail, lat, lon, weight, names?], or None to leave it out."""
    lon, lat = place["centroid"]
    names = place.get("name") or {}
    name = names.get("name")
    address = place.get("address") or {}
    street, city = address.get("street"), address.get("city") or address.get("county")
    number = place.get("housenumber")
    key, value = place.get("osm_key"), place.get("osm_value")

    if key == "place":
        if value not in PLACES or not name:
            return None
        kind, title = "place", name
        detail = address.get("state") if value in ("city", "town") else (city if city and city != name else address.get("state"))
    elif key == "highway":
        if value not in STREETS or not name:
            return None
        kind, title, detail = "street", name, city
    elif name and key in POI_KEYS:
        kind, title = "poi", name
        detail = ", ".join(part for part in (" ".join(p for p in (street, number) if p), city) if part)
    elif number and street:
        kind, title, detail = "address", f"{street} {number}", city
        name = None
    else:
        return None
    aka = sorted({names[k] for k in NAME_VARIANTS if names.get(k) and names[k] != name})
    if kind == "address":
        # Bilingual towns name their streets twice: Mannerheimintie, Mannerheimvägen.
        aka = sorted({f"{address[k]} {number}" for k in ("street:sv", "street:fi") if address.get(k) and address[k] != street})
    record = [kind, title, detail or "", round(lat, 5), round(lon, 5), round(place.get("importance") or 0, 3)]
    return record + [aka] if aka else record


def cell_of(lat, lon):
    return int((lat + 90) // CELL) * COLUMNS + int((lon + 180) // CELL)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--region", required=True, help="ISO 3166-1 alpha-2 code, e.g. FI: only its places go in")
    parser.add_argument("dump", help="Photon dump (import.jsonl.zst)")
    parser.add_argument("output", help="directory for the packs; the pack goes in <output>/<region>/search/")
    args = parser.parse_args()
    country = args.region.lower()

    cells = collections.defaultdict(list)
    streets = set()
    generated = None
    for header, place in documents(args.dump):
        if place.get("country_code") != country or not place.get("centroid"):
            continue
        generated = generated or (header or {}).get("data_timestamp")
        record = entry(place)
        if record is None:
            continue
        cell = cell_of(record[3], record[4])
        if record[0] == "street":
            # A street is many ways in OpenStreetMap: one entry per name and town in a cell.
            if (cell, record[1], record[2]) in streets:
                continue
            streets.add((cell, record[1], record[2]))
        cells[cell].append(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
    if not cells:
        raise SystemExit(f"no places for {args.region} in {args.dump}")

    packed = {}
    whole = hashlib.sha256()
    for cell in sorted(cells):
        body = ('{"format":1,"entries":[' + ",".join(cells[cell]) + "]}").encode()
        packed[cell] = gzip.compress(body, 9, mtime=0)
        whole.update(f"{cell}\0".encode() + hashlib.sha256(body).digest())
    build = whole.hexdigest()[:16]

    root = os.path.join(args.output, args.region.upper(), "search")
    os.makedirs(root, exist_ok=True)
    manifest_path = os.path.join(root, MANIFEST)
    previous = None
    if os.path.exists(manifest_path):
        with open(manifest_path) as existing:
            previous = json.load(existing).get("build")
    if previous == build and os.path.isdir(os.path.join(root, build)):
        print(f"✓ search pack {args.region}: build {build} already published")
        return

    part = os.path.join(root, build + ".part")
    shutil.rmtree(part, ignore_errors=True)
    os.makedirs(part)
    for cell, data in packed.items():
        with open(os.path.join(part, f"{cell}.json.gz"), "wb") as out:
            out.write(data)
    shutil.rmtree(os.path.join(root, build), ignore_errors=True)
    os.rename(part, os.path.join(root, build))
    manifest = {
        "format": 1,
        "region": args.region.upper(),
        "build": build,
        "generated": (generated or "")[:19] + "Z" if generated else None,
        "attribution": "© OpenStreetMap contributors, ODbL",
        "size": CELL,
        "cells": [[cell, len(cells[cell]), len(packed[cell])] for cell in sorted(cells)],
    }
    with open(manifest_path + ".tmp", "w") as out:
        json.dump(manifest, out, separators=(",", ":"))
    os.replace(manifest_path + ".tmp", manifest_path)
    for name in os.listdir(root):
        if name not in (MANIFEST, build, previous) and os.path.isdir(os.path.join(root, name)):
            shutil.rmtree(os.path.join(root, name))

    entries = sum(len(c) for c in cells.values())
    download = sum(len(d) for d in packed.values())
    print(f"✓ search pack {args.region}: build {build}, {entries:,} places in {len(cells)} cells ({download / 1e6:.0f} MB to download)")


if __name__ == "__main__":
    main()
