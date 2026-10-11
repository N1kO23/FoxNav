#!/usr/bin/env python3
"""Builds FoxNav data packs (protocol/SPEC.md §3) from an OpenStreetMap extract.

    build-datapacks --region FI extract.osm.pbf output-dir

Writes into output-dir/FI/:
- cameras.json: fixed speed and red-light cameras, with the travel directions they enforce where
  OpenStreetMap knows them;
- speedlimits.json: limits for parts of the year (maxspeed:conditional), such as winter limits,
  which routing engines don't apply; with --digiroad (Finland), also the official limits, see
  digiroad.py: by default they win where the two sources disagree, winter limits included, as
  they're kept up to date by the road authorities; with --prefer osm, they only fill the roads
  OpenStreetMap has no limit for.

Map data © OpenStreetMap contributors, ODbL.
"""
import argparse
import collections
import datetime
import json
import math
import os
import re
import subprocess
import tempfile

import osmium

MONTHS = {name: index + 1 for index, name in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}
MONTH_LENGTHS = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]

# When "winter" and "summer" are, per region: OpenStreetMap leaves that to the reader. The dates
# move every year, and lowering the limits takes weeks, so winter here errs long: a winter limit
# shown a little early is a slower drive, a summer limit shown too long is a fine.
SEASONS = {
    "FI": {"winter": ("10-15", "04-30"), "summer": ("05-01", "10-14")},
}

DRIVABLE = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
            "living_street", "service", "road", "motorway_link", "trunk_link", "primary_link",
            "secondary_link", "tertiary_link"}

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


def split_conditions(value):
    """ "100 @ summer; 80 @ (Nov-Apr)" -> ["100 @ summer", "80 @ (Nov-Apr)"]: semicolons in brackets stay."""
    parts, depth, current = [], 0, ""
    for char in value:
        depth += {"(": 1, ")": -1}.get(char, 0)
        if char == ";" and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += char
    return [part.strip() for part in parts + [current] if part.strip()]


def month_day(month, day, end):
    """MM-DD; a month without a day means its first day, or its last at the end of a range."""
    number = MONTHS[month]
    day = int(day) if day else (MONTH_LENGTHS[number - 1] if end else 1)
    return f"{number:02d}-{day:02d}"


def date_range(condition, seasons):
    """(from, to, season) for the date conditions FoxNav understands; None for the rest (times of day, weekdays, wet…)."""
    condition = condition.strip()
    if condition.startswith("(") and condition.endswith(")"):
        condition = condition[1:-1].strip()
    if condition in ("winter", "summer"):
        span = seasons.get(condition)
        return (span[0], span[1], condition) if span else None
    month = "(" + "|".join(MONTHS) + ")"
    match = re.fullmatch(month + r"(?:\s+(\d{1,2}))?\s*-\s*" + month + r"(?:\s+(\d{1,2}))?", condition)
    if match:
        start, end = month_day(match[1], match[2], False), month_day(match[3], match[4], True)
        # Across the new year in the Nordics: a winter limit by another name.
        return start, end, "winter" if start > end else None
    match = re.fullmatch(r"(\d{4})\s+" + month + r"\s+(\d{1,2})\s*-\s*(\d{4})\s+" + month + r"\s+(\d{1,2})", condition)
    if match:
        return f"{match[1]}-{month_day(match[2], match[3], False)}", f"{match[4]}-{month_day(match[5], match[6], True)}", None
    return None


class ConditionalLimits(osmium.SimpleHandler):
    """Drivable ways with maxspeed:conditional. Limits for one direction only are left out:
    which way an edge runs along its OpenStreetMap way isn't something the app knows."""

    def __init__(self, seasons):
        super().__init__()
        self.seasons = seasons
        self.ways = []
        self.skipped = 0

    def way(self, way):
        value = way.tags.get("maxspeed:conditional")
        if not value or way.tags.get("highway") not in DRIVABLE:
            return
        conditional = []
        for part in split_conditions(value):
            limit, _, condition = part.partition("@")
            span = date_range(condition, self.seasons) if condition else None
            speed = parse_maxspeed(limit)
            if span is None or speed is None:
                self.skipped += 1
                continue
            start, end, season = span
            rule = {"maxspeed": speed, "from": start, "to": end}
            if season:
                rule["season"] = season
            conditional.append(rule)
        if conditional:
            entry = {"way": way.id}
            maxspeed = parse_maxspeed(way.tags.get("maxspeed"))
            if maxspeed:
                entry["maxspeed"] = maxspeed
            entry["conditional"] = conditional
            self.ways.append(entry)


def write_pack(output, region, name, pack):
    directory = os.path.join(output, region)
    os.makedirs(directory, exist_ok=True)
    target = os.path.join(directory, name)
    with open(target + ".part", "w", encoding="utf-8") as out:
        json.dump(pack, out, ensure_ascii=False, separators=(",", ":"))
    os.replace(target + ".part", target)
    return target


def parse_season(text):
    """winter=10-15:04-30 -> ("winter", ("10-15", "04-30"))"""
    match = re.fullmatch(r"(winter|summer)=(\d{2}-\d{2}):(\d{2}-\d{2})", text)
    if not match:
        raise argparse.ArgumentTypeError(f"expected winter=MM-DD:MM-DD or summer=MM-DD:MM-DD, not {text}")
    return match[1], (match[2], match[3])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--region", required=True, help="ISO 3166-1 alpha-2 code, e.g. FI")
    parser.add_argument("extract", help="OpenStreetMap extract (.osm.pbf) covering the region")
    parser.add_argument("output", help="directory for the packs")
    parser.add_argument("--digiroad", help="directory of Digiroad speed limit layers (Finland), see digiroad.py")
    parser.add_argument("--prefer", choices=("digiroad", "osm"), default="digiroad",
                        help="which source wins where both have a limit (default: digiroad, the official one)")
    parser.add_argument("--season", type=parse_season, action="append", default=[],
                        help="when winter or summer is in this region, e.g. winter=10-15:04-30 (repeatable)")
    args = parser.parse_args()
    region = args.region.upper()
    seasons = dict(SEASONS.get(region, {}))
    seasons.update(dict(args.season))

    with tempfile.TemporaryDirectory() as tmp:
        filtered = os.path.join(tmp, "enforcement.osm.pbf")
        # Keeps the nodes the relations refer to, so their places are known.
        subprocess.run(["osmium", "tags-filter", args.extract, "n/highway=speed_camera", "r/type=enforcement",
                        "-o", filtered, "--overwrite"], check=True)
        collected = Collector()
        collected.apply_file(filtered)

        conditional_ways = os.path.join(tmp, "conditional.osm.pbf")
        subprocess.run(["osmium", "tags-filter", args.extract, "w/maxspeed:conditional", "-R",
                        "-o", conditional_ways, "--overwrite"], check=True)
        limits = ConditionalLimits(seasons)
        limits.apply_file(conditional_ways)

        digiroad_limits, digiroad_winter, extra_attribution = {}, {}, None
        if region == "FI" and args.digiroad and os.path.isdir(args.digiroad) and os.listdir(args.digiroad):
            import digiroad
            digiroad_limits, digiroad_winter = digiroad.conflate(args.digiroad, args.extract, tmp)
            extra_attribution = digiroad.ATTRIBUTION

    header = {
        "format": 1,
        "region": region,
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "attribution": "© OpenStreetMap contributors, ODbL",
    }
    camera_list = cameras(collected)
    target = write_pack(args.output, region, "cameras.json", {**header, "cameras": camera_list})
    with_bearings = sum(1 for camera in camera_list if "bearings" in camera)
    print(f"{target}: {len(camera_list)} cameras, {with_bearings} with known directions")

    # One entry per way: OpenStreetMap's conditional limits, then what Digiroad adds or replaces.
    entries = {entry["way"]: entry for entry in limits.ways}
    fill = {}
    for way, found in digiroad_limits.items():
        if found["tagged"] and not (args.prefer == "digiroad" and found["osm"] is not None):
            continue
        if found["osm"] == found["main"] and not found["sections"]:
            continue
        if not found["tagged"] and not found["sections"]:
            fill[way] = found["main"]
            continue
        entry = entries.setdefault(way, {"way": way})
        if found["osm"] is not None:
            entry["maxspeed"] = found["osm"]
        if found["main"] != found["osm"]:
            entry["limit"] = found["main"]
        if found["sections"]:
            entry["sections"] = found["sections"]
    winter = seasons.get("winter")
    if digiroad_winter and not winter:
        print(f"No winter dates for {region}: Digiroad's winter limits were left out. Pass --season.")
    official_winter = {"from": winter[0], "to": winter[1], "season": "winter"} if winter else None
    for way, (limit, osm) in sorted(digiroad_winter.items()) if winter else []:
        entry = entries.get(way, {})
        other = entry.get("limit") or osm or fill.get(way)
        if not other or limit >= other:
            continue
        # OpenStreetMap's own winter limit for the way gives way to the official one, unless it's preferred.
        own_winter = [rule for rule in entry.get("conditional", []) if rule.get("season") == "winter"]
        if own_winter and args.prefer == "osm":
            continue
        entry = entries.setdefault(way, {"way": way})
        if osm is not None:
            entry["maxspeed"] = osm
        others = [rule for rule in entry.get("conditional", []) if rule.get("season") != "winter"]
        entry["conditional"] = others + [{"maxspeed": limit, **official_winter}]
    ways = sorted(entries.values(), key=lambda entry: entry["way"])
    # Ways by limit, each list sorted and stored as differences from the previous ID: compact,
    # since there can be hundreds of thousands.
    by_limit = collections.defaultdict(list)
    for way, limit in fill.items():
        by_limit[limit].append(way)
    fill_lists = {}
    for limit, ids in sorted(by_limit.items()):
        ids.sort()
        fill_lists[str(limit)] = [ids[0]] + [b - a for a, b in zip(ids, ids[1:])]
    pack = {**header, "ways": ways}
    if digiroad_limits:
        pack["attribution"] = header["attribution"] + "; " + extra_attribution
        if fill_lists:
            pack["fill"] = fill_lists
    target = write_pack(args.output, region, "speedlimits.json", pack)
    if not seasons:
        print(f"No winter or summer dates for {region}: limits for those were left out. Pass --season.")
    sectioned = sum(1 for entry in ways if "sections" in entry)
    replaced = sum(1 for entry in ways if "limit" in entry and "maxspeed" in entry)
    print(f"{target}: {len(ways)} ways with their own entry ({sectioned} in sections, {replaced} with OpenStreetMap's limit replaced), "
          f"{len(fill)} more filled where OpenStreetMap has no limit; {limits.skipped} conditions not understood")


if __name__ == "__main__":
    main()
