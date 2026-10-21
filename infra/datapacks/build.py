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

import official_limits

MONTHS = {name: index + 1 for index, name in enumerate("Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec".split())}
MONTH_LENGTHS = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]

# When "winter" and "summer" are, per region: OpenStreetMap leaves that to the reader. The dates
# move every year, and lowering the limits takes weeks, so winter here errs long: a winter limit
# shown a little early is a slower drive, a summer limit shown too long is a fine.
SEASONS = {
    "FI": {"winter": ("10-15", "04-30"), "summer": ("05-01", "10-14")},
    # NVDB gives most Norwegian winter limits their own dates, from November to mid-April.
    "NO": {"winter": ("10-15", "04-30"), "summer": ("05-01", "10-14")},
    # Swedish winter limits start as early as October.
    "SE": {"winter": ("10-01", "04-30"), "summer": ("05-01", "09-30")},
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
    """km/h from "50", "50 km/h" or "30 mph"; None for anything else (zones, "signals", ...)."""
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
    """speed, red_light, or None for enforcement we don't warn about (checks, tolls, ...)."""
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


def merge_cameras(official, mapped):
    """The official cameras, and OpenStreetMap's that aren't one of them: a mapped camera within
    MERGE_DISTANCE_M of an official one is taken to be the same, which shouldn't warn twice."""
    cells = collections.defaultdict(list)
    for camera in official:
        cells[(round(camera["lat"] * 100), round(camera["lon"] * 50))].append(camera)

    def near_official(camera):
        row, column = round(camera["lat"] * 100), round(camera["lon"] * 50)
        return any(distance_m(camera, other) <= MERGE_DISTANCE_M
                   for r in (row - 1, row, row + 1) for c in (column - 1, column, column + 1) for other in cells[(r, c)])

    return sorted(official + [camera for camera in mapped if not near_official(camera)], key=lambda camera: camera["id"])


MERGE_DISTANCE_M = 50.0


def distance_m(a, b):
    """Metres between two {lat, lon}, near enough for short distances."""
    x = math.radians(b["lon"] - a["lon"]) * math.cos(math.radians((a["lat"] + b["lat"]) / 2))
    y = math.radians(b["lat"] - a["lat"])
    return 6_371_000 * math.hypot(x, y)


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


MONTH = "(" + "|".join(MONTHS) + ")"
MONTH_RANGE = MONTH + r"(?:\s+(\d{1,2}))?\s*-\s*" + MONTH + r"(?:\s+(\d{1,2}))?"
WEEKDAYS = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
WEEKDAY = "(?:" + "|".join(WEEKDAYS) + ")"
WEEKDAY_LIST = WEEKDAY + r"(?:\s*-\s*" + WEEKDAY + r")?(?:\s*,\s*" + WEEKDAY + r"(?:\s*-\s*" + WEEKDAY + r")?)*"
HOURS = r"(?:[01]\d|2[0-3]):[0-5]\d\s*-\s*(?:(?:[01]\d|2[0-3]):[0-5]\d|24:00)"
HOURS_LIST = HOURS + r"(?:\s*,\s*" + HOURS + r")*"
DATES = r"winter|summer|\d{4}\s+" + MONTH + r"\s+\d{1,2}\s*-\s*\d{4}\s+" + MONTH + r"\s+\d{1,2}|" + MONTH_RANGE


def date_range(condition):
    """(from, to, season) for a date condition, without seasons; None for anything else."""
    match = re.fullmatch(MONTH_RANGE, condition)
    if match:
        start, end = month_day(match[1], match[2], False), month_day(match[3], match[4], True)
        # Across the new year in the Nordics: a winter limit by another name.
        return start, end, "winter" if start > end else None
    match = re.fullmatch(r"(\d{4})\s+" + MONTH + r"\s+(\d{1,2})\s*-\s*(\d{4})\s+" + MONTH + r"\s+(\d{1,2})", condition)
    if match:
        return f"{match[1]}-{month_day(match[2], match[3], False)}", f"{match[4]}-{month_day(match[5], match[6], True)}", None
    return None


def weekdays(text):
    """["Mo", "Tu", ...] from "Mo-Fr" or "Mo,We,Fr-Su", in week order."""
    days = set()
    for part in re.split(r"\s*,\s*", text):
        first, _, last = (day.strip() for day in part.partition("-"))
        start, end = WEEKDAYS.index(first), WEEKDAYS.index(last or first)
        days.update(WEEKDAYS[(start + step) % 7] for step in range((end - start) % 7 + 1))
    return [day for day in WEEKDAYS if day in days]


def condition_rule(condition, seasons):
    """The rule for a condition FoxNav understands (protocol/SPEC.md §3.2): dates or a season,
    and weekdays and hours, as in "Nov-Apr", "winter" or "(Sep-Jun Mo-Fr 07:00-17:00)". None for
    the rest (public holidays, wet roads, vehicles...)."""
    condition = condition.strip()
    if condition.startswith("(") and condition.endswith(")"):
        condition = condition[1:-1].strip()
    # "Not on public or school holidays", or the day before one ("PH -1 day off", as Swedish school
    # zones say): holding on them too errs towards the lower limit.
    while (shorter := re.sub(r"\s*[;,]\s*(PH|SH)(\s*[+-]\d+\s*days?)?\s+off\s*$", "", condition)) != condition:
        condition = shorter
    # Dates first, then weekdays, then hours, each optional.
    match = re.match(r"(" + DATES + r")(?=\s|$)", condition)
    dates, rest = (match[1], condition[match.end():].strip()) if match else (None, condition)
    match = re.match(WEEKDAY_LIST + r"(?=\s|$)", rest)
    days, rest = (match[0], rest[match.end():].strip()) if match else (None, rest)
    if rest and not re.fullmatch(HOURS_LIST, rest):
        return None
    hours = rest or None
    if not dates and not days and not hours:
        return None
    rule = {}
    if dates in ("winter", "summer"):
        span = seasons.get(dates)
        if not span:
            return None
        rule.update({"from": span[0], "to": span[1], "season": dates})
    elif dates:
        span = date_range(dates)
        if span is None:
            return None
        rule.update({"from": span[0], "to": span[1]})
        # Across the new year with hours or weekdays is a school year, not a winter.
        if span[2] and not days and not hours:
            rule["season"] = span[2]
    else:
        rule.update({"from": "01-01", "to": "12-31"})
    if days:
        rule["days"] = weekdays(days)
    if hours:
        rule["times"] = [[part.strip() for part in span.split("-")] for span in re.split(r"\s*,\s*", hours)]
    return rule


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
        maxspeed = parse_maxspeed(way.tags.get("maxspeed"))
        for part in split_conditions(value):
            limit, _, condition = part.partition("@")
            rule = condition_rule(condition, self.seasons) if condition else None
            speed = parse_maxspeed(limit)
            # Hours and weekdays lower a limit (school zones, rush hours); one that raises it,
            # such as "80 @ (10:00-06:00)" on a 60 road, is more likely a mapping slip than a
            # limit, and a wrong higher limit is the worse mistake.
            raises = rule is not None and ("days" in rule or "times" in rule) and maxspeed and speed and speed >= maxspeed
            if rule is None or speed is None or raises:
                self.skipped += 1
                continue
            conditional.append({"maxspeed": speed, **rule})
        if conditional:
            entry = {"way": way.id}
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


def official_source(region, args):
    """The national database whose limits go in, if one was given for the region and has data."""
    def has_data(directory):
        return directory and os.path.isdir(directory) and os.listdir(directory)

    if region == "FI" and has_data(args.digiroad):
        from digiroad import Digiroad
        return Digiroad(args.digiroad)
    if region == "NO" and has_data(args.nvdb):
        from nvdb import Nvdb
        return Nvdb(args.nvdb)
    return None


def dated(rule, seasons):
    """An official rule with its dates: those it came with, or its season's in this region. None if
    neither is known. Rules for times of day but not dates hold all year."""
    rule = dict(rule)
    if "from" not in rule:
        span = seasons.get(rule.get("season")) if rule.get("season") else ("01-01", "12-31")
        if span is None:
            return None
        rule["from"], rule["to"] = span
    return {key: rule[key] for key in ("from", "to", "season", "days", "times") if key in rule}


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
    parser.add_argument("--nvdb", help="directory of NVDB road objects (Norway), see nvdb.py")
    # "digiroad" was the official source's name when Finland was the only region with one.
    parser.add_argument("--prefer", choices=("official", "osm", "digiroad"), default="official",
                        help="which source wins where both have a limit (default: official, the national database)")
    parser.add_argument("--season", type=parse_season, action="append", default=[],
                        help="when winter or summer is in this region, e.g. winter=10-15:04-30 (repeatable)")
    args = parser.parse_args()
    if args.prefer == "digiroad":
        args.prefer = "official"
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

        official, official_conditional, extra_attribution = {}, {}, None
        source = official_source(region, args)
        if source is not None:
            official, official_conditional = official_limits.conflate(source, args.extract, tmp)
            extra_attribution = source.attribution

    header = {
        "format": 1,
        "region": region,
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "attribution": "© OpenStreetMap contributors, ODbL",
    }
    camera_list = cameras(collected)
    camera_header = dict(header)
    official_cameras = source.cameras() if source is not None and hasattr(source, "cameras") else []
    if official_cameras:
        camera_list = merge_cameras(official_cameras, camera_list)
        camera_header["attribution"] += "; " + source.camera_attribution
    target = write_pack(args.output, region, "cameras.json", {**camera_header, "cameras": camera_list})
    with_bearings = sum(1 for camera in camera_list if "bearings" in camera)
    sections = sum(1 for camera in camera_list if camera["kind"] == "average_speed")
    print(f"{target}: {len(camera_list)} cameras, {with_bearings} with known directions, {sections} average speed sections")

    # One entry per way: OpenStreetMap's conditional limits, then what the official source adds or replaces.
    entries = {entry["way"]: entry for entry in limits.ways}
    fill = {}
    for way, found in official.items():
        if found["tagged"] and not (args.prefer == "official" and found["osm"] is not None):
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
    for key, found in sorted(official_conditional.items(), key=lambda item: str(item[0])):
        rule = dated(official_limits.rule_of(key), seasons)
        if rule is None:
            print(f"No {official_limits.rule_of(key).get('season')} dates for {region}: {len(found)} of the official limits for it were left out. Pass --season.")
            continue
        for way, (limit, osm) in sorted(found.items()):
            entry = entries.get(way, {})
            other = entry.get("limit") or osm or fill.get(way)
            # Limits for part of the year or the day are lower ones: winter, summer, school hours.
            if not other or limit >= other:
                continue
            # OpenStreetMap's own limit for the season gives way to the official one, unless it's preferred.
            season = rule.get("season")
            same_season = [own for own in entry.get("conditional", []) if season and own.get("season") == season]
            if same_season and args.prefer == "osm":
                continue
            entry = entries.setdefault(way, {"way": way})
            if osm is not None:
                entry["maxspeed"] = osm
            others = [own for own in entry.get("conditional", []) if own not in same_season]
            entry["conditional"] = others + [{"maxspeed": limit, **rule}]
    for entry in entries.values():
        # The first rule that applies wins: where two apply at once, say school hours in winter, the lower limit.
        if "conditional" in entry:
            entry["conditional"].sort(key=lambda rule: rule["maxspeed"])
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
    if official:
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
