#!/usr/bin/env python3
"""Publishes the routing graph's tiles for routing on the phone (protocol/SPEC.md §3.3).

    publish-routing-tiles valhalla_tiles.tar output-dir

Reads the tiles from the tar Valhalla serves them from (mjolnir.tile_extract), or from its tile
directory (mjolnir.tile_dir); both hold the same tiles, and the build is the same either way.

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
import tarfile

# Valhalla's tile size per hierarchy level, in degrees: motorways and trunk roads, arterials, local roads.
LEVELS = {0: 4.0, 1: 1.0, 2: 0.25}
MANIFEST = "manifest.json"


def tile_id(path):
    """2/000/864/819.gph is level 2's tile 864819."""
    return int("".join(path[:-len(".gph")].split("/")[1:]))


def tiles_in(graph):
    """(level, tile ID, path, (offset, size, mtime) in the tar or None) of every tile, by level and ID."""
    found = []
    if os.path.isdir(graph):
        for level in LEVELS:
            for directory, _, names in os.walk(os.path.join(graph, str(level))):
                for name in names:
                    if name.endswith(".gph"):
                        path = os.path.relpath(os.path.join(directory, name), graph).replace(os.sep, "/")
                        found.append((level, tile_id(path), path, None))
    else:
        # Uncompressed: each tile's bytes sit at an offset of their own, for the workers to read.
        with tarfile.open(graph) as tar:
            for member in tar:
                level = member.name.split("/")[0]
                if member.isfile() and member.name.endswith(".gph") and level.isdigit() and int(level) in LEVELS:
                    found.append((int(level), tile_id(member.name), member.name, (member.offset_data, member.size, member.mtime)))
    return sorted(found)


def read(graph, tile):
    _, _, path, where = tile
    if where is None:
        with open(os.path.join(graph, path), "rb") as source:
            return source.read()
    offset, size, _ = where
    with open(graph, "rb") as source:
        source.seek(offset)
        return source.read(size)


def modified(graph, tile):
    _, _, path, where = tile
    return os.path.getmtime(os.path.join(graph, path)) if where is None else where[2]


def header(data):
    """The Valhalla version that built a tile, and the level and ID it says it has."""
    graph_id = struct.unpack_from("<Q", data, 0)[0] & ((1 << 46) - 1)
    return data[16:32].split(b"\0")[0].decode("ascii"), graph_id & 0x7, (graph_id >> 3) & ((1 << 22) - 1)


def check(job):
    """A tile's header and digest."""
    graph, tile = job
    data = read(graph, tile)
    return header(data[:32]), hashlib.sha256(data).digest()


def compress(job):
    """Gzips one tile into the build's directory; returns its size and its download size."""
    graph, target, tile = job
    data = read(graph, tile)
    destination = os.path.join(target, tile[2] + ".gz")
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    with open(destination, "wb") as raw:
        # No name or time in the gzip header: the same tile always gives the same file.
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as packed:
            packed.write(data)
    return len(data), os.path.getsize(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("graph", help="Valhalla's tile tar (mjolnir.tile_extract) or directory (mjolnir.tile_dir)")
    parser.add_argument("output", help="directory to publish the tiles in")
    args = parser.parse_args()

    if not os.path.exists(args.graph):
        raise SystemExit(f"no {args.graph}: has Valhalla built its graph yet?")
    tiles = tiles_in(args.graph)
    if not tiles:
        raise SystemExit(f"no tiles in {args.graph}: has Valhalla built its graph yet?")

    with multiprocessing.Pool() as pool:
        versions = set()
        whole = hashlib.sha256()
        for (level, number, path, _), ((version, header_level, header_id), tile_digest) in zip(tiles, pool.map(check, [(args.graph, tile) for tile in tiles])):
            if (header_level, header_id) != (level, number):
                raise SystemExit(f"{path} says it's level {header_level} tile {header_id}")
            versions.add(version)
            whole.update(path.encode() + b"\0" + tile_digest)
        if len(versions) != 1:
            raise SystemExit(f"the tiles come from different Valhalla versions: {sorted(versions)}")
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

    newest = max(modified(args.graph, tile) for tile in tiles)
    manifest = {
        "format": 1,
        "engine": "valhalla",
        "version": versions.pop(),
        "build": build,
        "generated": datetime.datetime.fromtimestamp(newest, datetime.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "attribution": "© OpenStreetMap contributors, ODbL",
        "levels": [{"level": level, "size": size} for level, size in LEVELS.items()],
        "tiles": {
            str(level): [[number, size, download] for (tile_level, number, _, _), (size, download) in zip(tiles, sizes) if tile_level == level]
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
