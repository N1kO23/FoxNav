#!/usr/bin/env python3
"""Builds FoxNav data packs (protocol/SPEC.md §3) from an OpenStreetMap extract.

    build-datapacks --region FI extract.osm.pbf output-dir

Writes output-dir/FI/cameras.json: fixed speed and red-light cameras, with the travel directions
they enforce where OpenStreetMap knows them. Map data © OpenStreetMap contributors, ODbL.
"""
import argparse
import datetime
import json
import math
import os
import re
import subprocess
import tempfile

import osmium

COMPASS = {name: index * 22.5 for index, name in enumerate(
    "N NNE NE ENE E ESE SE SSE S SSW SW WSW W WNW NW NNW".split())}


def parse_direction(value):
    """A bearing in degrees or a compass point. forward/backward would need the road's geometry: unknown for now."""
    if not value:
        return None
    value = value.strip().upper()
    if value in COMPASS:
        return COMPASS[value]
    try:
        return float(value) % 360
    except ValueError:
        return None


def parse_maxspeed(value):
    """km/h from "50", "50 km/h" or "30 mph"; None for anything else (zones, "signals", …)."""
    match = re.match(r"^\s*(\d+)\s*(mph)?", value or "")
    if not match:
        return None
    speed = int(match.group(1))
    if match.group(2):
        speed = round(speed * 1.609)
    return speed if 5 <= speed <= 200 else None


def bearing(start, end):
    """Initial bearing from start to end, (lat, lon) in degrees, clockwise from north."""
    lat1, lat2 = math.radians(start[0]), math.radians(end[0])
    delta = math.radians(end[1] - start[1])
    y = math.sin(delta) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(delta)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


class Collector(osmium.SimpleHandler):
    """Camera nodes, enforcement relations, and where every node is."""

    def __init__(self):
        super().__init__()
        self.locations = {}
        self.camera_tags = {}
        self.relations = []

    def node(self, node):
        if node.location.valid():
            self.locations[node.id] = (node.location.lat, node.location.lon)
        if node.tags.get("highway") == "speed_camera":
            self.camera_tags[node.id] = {tag.k: tag.v for tag in node.tags}

    def relation(self, relation):
        if relation.tags.get("type") != "enforcement":
            return
        members = {}
        for member in relation.members:
            if member.type == "n" and member.role in ("device", "from", "to"):
                members.setdefault(member.role, member.ref)
        self.relations.append(({tag.k: tag.v for tag in relation.tags}, members))


def kind_of(enforcement):
    """speed, red_light, or None for enforcement we don't warn about (checks, tolls, …)."""
    kinds = set((enforcement or "").split(";"))
    if "maxspeed" in kinds or "average_speed" in kinds:
        return "speed"
    if "traffic_signals" in kinds:
        return "red_light"
    return None


def cameras(collected):
    found = {}

    def add(node_id, kind, direction, maxspeed):
        location = collected.locations.get(node_id)
        if location is None:
            return
        camera = found.setdefault(node_id, {"kind": kind, "bearings": set(), "maxspeed": None, "unknown_direction": False})
        if direction is None:
            camera["unknown_direction"] = True
        else:
            camera["bearings"].add(round(direction) % 360)
        camera["maxspeed"] = camera["maxspeed"] or maxspeed

    # Enforcement relations say which way traffic is checked: from "from" towards "to".
    for tags, members in collected.relations:
        kind = kind_of(tags.get("enforcement"))
        device = members.get("device")
        if kind is None or device is None:
            continue
        places = {role: collected.locations.get(node) for role, node in members.items()}
        start = places.get("from") or places.get("device")
        end = places.get("to") if places.get("to") else (places.get("device") if places.get("from") else None)
        direction = bearing(start, end) if start and end and start != end else None
        node_tags = collected.camera_tags.get(device, {})
        add(device, kind, direction, parse_maxspeed(tags.get("maxspeed")) or parse_maxspeed(node_tags.get("maxspeed")))

    # Cameras mapped only as nodes.
    for node_id, tags in collected.camera_tags.items():
        if node_id in found:
            continue
        kind = kind_of(tags.get("enforcement")) or "speed"
        add(node_id, kind, parse_direction(tags.get("direction")), parse_maxspeed(tags.get("maxspeed")))

    result = []
    for node_id, camera in sorted(found.items()):
        lat, lon = collected.locations[node_id]
        entry = {"id": f"osm/node/{node_id}", "lat": round(lat, 6), "lon": round(lon, 6), "kind": camera["kind"]}
        # A camera with any unknown direction might watch every way: no bearings then.
        if camera["bearings"] and not camera["unknown_direction"]:
            entry["bearings"] = sorted(camera["bearings"])
        if camera["maxspeed"]:
            entry["maxspeed"] = camera["maxspeed"]
        result.append(entry)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--region", required=True, help="ISO 3166-1 alpha-2 code, e.g. FI")
    parser.add_argument("extract", help="OpenStreetMap extract (.osm.pbf) covering the region")
    parser.add_argument("output", help="directory for the packs")
    args = parser.parse_args()
    region = args.region.upper()

    with tempfile.TemporaryDirectory() as tmp:
        filtered = os.path.join(tmp, "enforcement.osm.pbf")
        # Keeps the nodes the relations refer to, so their places are known.
        subprocess.run(["osmium", "tags-filter", args.extract, "n/highway=speed_camera", "r/type=enforcement",
                        "-o", filtered, "--overwrite"], check=True)
        collected = Collector()
        collected.apply_file(filtered)

    pack = {
        "format": 1,
        "region": region,
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "attribution": "© OpenStreetMap contributors, ODbL",
        "cameras": cameras(collected),
    }
    directory = os.path.join(args.output, region)
    os.makedirs(directory, exist_ok=True)
    target = os.path.join(directory, "cameras.json")
    with open(target + ".part", "w", encoding="utf-8") as out:
        json.dump(pack, out, ensure_ascii=False, separators=(",", ":"))
    os.replace(target + ".part", target)
    with_bearings = sum(1 for camera in pack["cameras"] if "bearings" in camera)
    print(f"{target}: {len(pack['cameras'])} cameras, {with_bearings} with known directions")


if __name__ == "__main__":
    main()
