#!/usr/bin/env python3
"""Publishes the routing graph's tiles for routing on the phone (protocol/SPEC.md §3.3).

    publish-routing-tiles valhalla_tiles/ output-dir

Writes every tile of Valhalla's graph gzipped into output-dir/<build>/ (the same paths, plus .gz,
which is what Valhalla's own tile_url fetches) and output-dir/manifest.json, which lists them with
their sizes. The build is a hash of the tiles: publishing the same graph again changes nothing, and
tiles from different builds, which don't fit together, never share a directory. The previous build
stays for clients still downloading it; older ones are removed.

Map data © OpenStreetMap contributors, ODbL.
"""
import argparse
import datetime
import gzip
import hashlib
import json
import multiprocessing
import os
import shutil
import struct

# Valhalla's tile size per hierarchy level, in degrees: motorways and trunk roads, arterials, local roads.
LEVELS = {0: 4.0, 1: 1.0, 2: 0.25}
MANIFEST = "manifest.json"


def tiles_in(graph):
    """(level, tile ID, path relative to graph) of every tile, by level and ID."""
    found = []
    for level in LEVELS:
        for directory, _, names in os.walk(os.path.join(graph, str(level))):
            for name in names:
                if name.endswith(".gph"):
                    path = os.path.relpath(os.path.join(directory, name), graph)
                    # 2/000/864/819.gph is level 2's tile 864819.
                    found.append((level, int("".join(path[:-len(".gph")].split(os.sep)[1:])), path))
    return sorted(found)


def header(path):
    """The Valhalla version that built a tile, and the level and ID it says it has."""
    with open(path, "rb") as tile:
        head = tile.read(32)
    graph_id = struct.unpack_from("<Q", head, 0)[0] & ((1 << 46) - 1)
    return head[16:32].split(b"\0")[0].decode("ascii"), graph_id & 0x7, (graph_id >> 3) & ((1 << 22) - 1)


def digest(job):
    graph, (_, _, path) = job
    with open(os.path.join(graph, path), "rb") as tile:
        return hashlib.file_digest(tile, "sha256").digest()


def compress(job):
    """Gzips one tile into the build's directory; returns its size and its download size."""
    graph, target, (_, _, path) = job
    destination = os.path.join(target, path + ".gz")
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    with open(os.path.join(graph, path), "rb") as source, open(destination, "wb") as raw:
        # No name or time in the gzip header: the same tile always gives the same file.
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as packed:
            shutil.copyfileobj(source, packed, 1 << 20)
    return os.path.getsize(os.path.join(graph, path)), os.path.getsize(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("graph", help="Valhalla's tile directory (mjolnir.tile_dir), e.g. valhalla_tiles/")
    parser.add_argument("output", help="directory to publish the tiles in")
    args = parser.parse_args()

    tiles = tiles_in(args.graph)
    if not tiles:
        raise SystemExit(f"no tiles in {args.graph}: has Valhalla built its graph yet?")
    versions = set()
    for level, tile_id, path in tiles:
        version, header_level, header_id = header(os.path.join(args.graph, path))
        if (header_level, header_id) != (level, tile_id):
            raise SystemExit(f"{path} says it's level {header_level} tile {header_id}")
        versions.add(version)
    if len(versions) != 1:
        raise SystemExit(f"the tiles come from different Valhalla versions: {sorted(versions)}")

    with multiprocessing.Pool() as pool:
        whole = hashlib.sha256()
        for (_, _, path), tile_digest in zip(tiles, pool.map(digest, [(args.graph, tile) for tile in tiles])):
            whole.update(path.encode() + b"\0" + tile_digest)
        build = whole.hexdigest()[:16]

        manifest_path = os.path.join(args.output, MANIFEST)
        previous = None
        if os.path.exists(manifest_path):
            with open(manifest_path) as existing:
                previous = json.load(existing).get("build")
        if previous == build and os.path.isdir(os.path.join(args.output, build)):
            print(f"✓ routing tiles: build {build} already published")
            return

        part = os.path.join(args.output, build + ".part")
        shutil.rmtree(part, ignore_errors=True)
        sizes = pool.map(compress, [(args.graph, part, tile) for tile in tiles])
    shutil.rmtree(os.path.join(args.output, build), ignore_errors=True)
    os.rename(part, os.path.join(args.output, build))

    newest = max(os.path.getmtime(os.path.join(args.graph, path)) for _, _, path in tiles)
    manifest = {
        "format": 1,
        "engine": "valhalla",
        "version": versions.pop(),
        "build": build,
        "generated": datetime.datetime.fromtimestamp(newest, datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "attribution": "© OpenStreetMap contributors, ODbL",
        "levels": [{"level": level, "size": size} for level, size in LEVELS.items()],
        "tiles": {
            str(level): [[tile_id, size, download] for (tile_level, tile_id, _), (size, download) in zip(tiles, sizes) if tile_level == level]
            for level in LEVELS
        },
    }
    with open(manifest_path + ".tmp", "w") as out:
        json.dump(manifest, out, separators=(",", ":"))
    os.replace(manifest_path + ".tmp", manifest_path)

    # Keep this build and the one before it; drop older builds and leftovers of interrupted runs.
    for name in os.listdir(args.output):
        if name not in (MANIFEST, build, previous) and os.path.isdir(os.path.join(args.output, name)):
            shutil.rmtree(os.path.join(args.output, name))

    raw = sum(size for size, _ in sizes)
    download = sum(download for _, download in sizes)
    print(f"✓ routing tiles: build {build}, {len(tiles)} tiles, {raw / 1e6:.0f} MB ({download / 1e6:.0f} MB to download)")


if __name__ == "__main__":
    main()
