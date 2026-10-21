"""Official Norwegian speed limits, speed cameras and average speed sections from NVDB, the national
road database of Statens vegvesen (NLOD 2.0), as infra/scripts/nvdb-fetch.py downloads them.

- Speed limits (Fartsgrense, 105) go to official_limits.py to be matched to OpenStreetMap ways,
  one county at a time.
- Limits for part of the year or the day (Fartsgrense, variabel, 721) become conditional limits:
  winter and summer limits with their own dates, and school zones on weekdays at set hours.
  Environmental limits without dates (Miljøfartsgrense) are switched on as the air needs, which
  no pack can know, so they're left out, as is anything else this can't read for certain.
- Speed cameras (ATK-punkt, 162) check one direction, given relative to the road's metering
  direction: the road link a camera stands on turns that into a bearing.
- Average speed sections (Streknings-ATK, 823) become cameras of kind average_speed, with the
  stretch they measure, in the direction they check.

Directions: "Med metreringsretning" is the way the road reference runs (the road number's metres
counting up), which runs with the road link's own direction (MED) or against it (MOT). A camera
checks the link's direction, turned round once for checking against the road's metering and once
for a road that runs against its link: of the 300 cameras whose names say which town they check
traffic going to, 91 % then point within 90° of it, the rest mostly on winding roads (turning round
only for how the camera is placed gets 89 %, neither 67 %). A section's line runs the way it's
placed on the link, and its "Med" is the way its line runs: 85 % of the 61 named sections end
nearer their town that way, more than when the road reference is taken into account (80 %).
"""
import glob
import gzip
import json
import os
import re

import numpy as np
import shapely
from pyproj import Geod, Transformer

from official_limits import rule_key, segments

ATTRIBUTION = "speed limits from NVDB © Statens vegvesen, NLOD 2.0, matched to OpenStreetMap"
CAMERA_ATTRIBUTION = "speed cameras from NVDB © Statens vegvesen, NLOD 2.0"

# EPSG:5973, which NVDB serves, is ETRS89 / UTM 33N with heights: its horizontal part.
CRS = "EPSG:25833"
TO_WGS84 = Transformer.from_crs(CRS, "EPSG:4326", always_xy=True)
GEOD = Geod(ellps="WGS84")

SEASONS = {"Vinterfartsgrense": "winter", "Sommerfartsgrense": "summer"}
# "Hverdager" are Monday to Saturday in Norwegian road signs; "u/ lørdag" leaves Saturday out.
DAYS = {"Hverdager u/ lørdag": ["Mo", "Tu", "We", "Th", "Fr"], "Hverdager": ["Mo", "Tu", "We", "Th", "Fr", "Sa"]}
DATE = re.compile(r"^(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$")
TIME = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
WITH_METERING = "Med metreringsretning"
AGAINST_METERING = "Mot metreringsretning"
# A camera further than this from the road link it's placed on gets no direction.
MAX_LINK_DISTANCE_M = 30.0
SIMPLIFY_M = 5.0
# Parts of a section's line whose ends are this close join up; further apart, the line is broken.
JOIN_M = 50.0
# Two sections whose ends are this close measure the same stretch.
SAME_STRETCH_M = 200.0


def records(path):
    with gzip.open(path, "rt", encoding="utf-8") as lines:
        for line in lines:
            yield json.loads(line)


def lines_of(wkt):
    """The 2D line strings of a WKT LineString or MultiLineString; none for anything else."""
    if not wkt:
        return []
    geometry = shapely.force_2d(shapely.from_wkt(wkt))
    if geometry.geom_type == "LineString":
        return [geometry]
    if geometry.geom_type == "MultiLineString":
        return list(geometry.geoms)
    return []


def time_range(start, end):
    """["HH:MM", "HH:MM"] from NVDB's clock times; None for all day; False if unreadable."""
    if not start and not end:
        return None
    start, end = start or "00:00", end or "24:00"
    if end == "23:59":
        end = "24:00"
    if not TIME.match(start) or not (TIME.match(end) or end == "24:00"):
        return False
    # 00:00–24:00 is all day, and so, read kindly, is a range that starts where it ends.
    if (start, end) == ("00:00", "24:00") or start == end:
        return None
    return [start, end]


def rule_of(properties):
    """A conditional rule (protocol/SPEC.md §3.2) from a 721 object's properties, or None when it
    can't be read for certain."""
    kind = properties.get("Type")
    rule = {}
    if kind in SEASONS:
        rule["season"] = SEASONS[kind]
    start, end = properties.get("Dato, fra"), properties.get("Dato, til")
    if start or end:
        if not (start and end and DATE.match(start) and DATE.match(end)):
            return None
        rule["from"], rule["to"] = start, end
    elif kind not in SEASONS and kind != "Skole":
        return None  # environmental limits without dates
    if properties.get("Ukedag") and properties["Ukedag"] != "Alle dager":
        days = DAYS.get(properties["Ukedag"])
        if days is None:
            return None
        rule["days"] = days
    times = time_range(properties.get("Klokkeslett, fra"), properties.get("Klokkeslett, til"))
    if times is False:
        return None
    if times:
        rule["times"] = [times]
    # A school zone with neither days nor hours would hold day and night.
    if kind == "Skole" and "days" not in rule and "times" not in rule:
        return None
    return rule


def layer(objects, value="Fartsgrense"):
    lines, limits = [], []
    for item in objects:
        for line in lines_of(item.get("wkt")):
            lines.append(line)
            limits.append(item["properties"].get(value))
    return segments(lines, limits)


def line_bearing(line):
    """The bearing a [lon, lat] line starts off in, over its first 20 m or so."""
    first = line[0]
    following = next((point for point in line[1:] if GEOD.inv(first[0], first[1], point[0], point[1])[2] >= 20), line[-1])
    return round(GEOD.inv(first[0], first[1], following[0], following[1])[0] % 360)


def bearing(a, b):
    """Initial bearing from a to b, both (x, y) in UTM 33N, degrees clockwise from true north."""
    (lon1, lon2), (lat1, lat2) = TO_WGS84.transform([a[0], b[0]], [a[1], b[1]])
    forward, _, _ = GEOD.inv(lon1, lat1, lon2, lat2)
    return round(forward % 360)


def wgs84(x, y):
    lon, lat = TO_WGS84.transform(x, y)
    return round(float(lat), 6), round(float(lon), 6)


def chained(lines):
    """One line from a section's parts, in the order they join: as listed, or the other way
    round, as NVDB lists some. None when they don't join up."""
    if len(lines) == 1:
        return lines[0]
    if not lines:
        return None

    def joins(parts):
        return all(shapely.Point(a.coords[-1]).distance(shapely.Point(b.coords[0])) <= JOIN_M for a, b in zip(parts, parts[1:]))

    for parts in (lines, lines[::-1]):
        if joins(parts):
            return shapely.LineString([point for part in parts for point in part.coords])
    return None


class Nvdb:
    name = "NVDB"
    crs = CRS
    attribution = ATTRIBUTION
    camera_attribution = CAMERA_ATTRIBUTION

    def __init__(self, directory):
        self.directory = directory

    def areas(self):
        for path in sorted(glob.glob(os.path.join(self.directory, "105", "*.jsonl.gz"))):
            county = os.path.basename(path).split(".")[0]
            layers = {None: layer(records(path))}
            by_rule = {}
            variable = os.path.join(self.directory, "721", f"{county}.jsonl.gz")
            for item in records(variable) if os.path.exists(variable) else []:
                rule = rule_of(item["properties"])
                if rule is not None:
                    by_rule.setdefault(rule_key(rule), []).append(item)
            for key, items in by_rule.items():
                layers[key] = layer(items)
            yield f"county {county}", layers

    def cameras(self):
        """Speed cameras and average speed sections, as camera pack entries (protocol/SPEC.md §3.1)."""
        found = []
        path = os.path.join(self.directory, "162.jsonl.gz")
        for item in records(path) if os.path.exists(path) else []:
            point = shapely.force_2d(shapely.from_wkt(item["wkt"])) if item.get("wkt") else None
            if point is None or point.geom_type != "Point":
                continue
            lat, lon = wgs84(point.x, point.y)
            camera = {"id": f"nvdb/162/{item['id']}", "lat": lat, "lon": lon, "kind": "speed"}
            heading = self.camera_heading(item, point)
            if heading is not None:
                camera["bearings"] = [heading]
            found.append(camera)
        path = os.path.join(self.directory, "823.jsonl.gz")
        sections = [section for item in (records(path) if os.path.exists(path) else []) if (section := self.section(item)) is not None]
        return found + self.both_ways(sections)

    @staticmethod
    def both_ways(sections):
        """Sections come in pairs, one for each direction. A pair over the same stretch that both
        check the same way has one of them wrong; which one, the data doesn't say, but between them
        they cover both directions: the second is turned round."""
        def apart(a, b):
            return GEOD.inv(a[0], a[1], b[0], b[1])[2]

        for index, first in enumerate(sections):
            for second in sections[index + 1:]:
                same_way = apart(first["line"][0], second["line"][0]) <= SAME_STRETCH_M and apart(first["line"][-1], second["line"][-1]) <= SAME_STRETCH_M
                if same_way and abs((first["bearings"][0] - second["bearings"][0] + 180) % 360 - 180) <= 45:
                    line = second["line"][::-1]
                    second.update(lat=line[0][1], lon=line[0][0], line=line, bearings=[line_bearing(line)])
                    print(f"  NVDB: {first['id']} and {second['id']} both check one direction of the same stretch; {second['id']} turned round")
        return sections

    @staticmethod
    def camera_heading(item, point):
        """The direction a camera checks: its link's heading where it stands, turned round when it
        checks traffic against its metering direction, which is against the link's when the camera
        is placed against it. None when that's unknown or both ways."""
        # Type 162 spells it "Kontollretning" (sic); 823 "Kontrollretning".
        direction = item["properties"].get("Kontollretning") or item["properties"].get("Kontrollretning")
        link, placement = item.get("link"), next((p for p in item["placements"] if "relativPosisjon" in p), None)
        if direction not in (WITH_METERING, AGAINST_METERING) or not link or placement is None:
            return None
        lines = lines_of(link["wkt"])
        if len(lines) != 1 or link["end"] <= link["start"]:
            return None
        line = lines[0]
        along = (placement["relativPosisjon"] - link["start"]) / (link["end"] - link["start"]) * line.length
        here = line.interpolate(along)
        if here.distance(point) > MAX_LINK_DISTANCE_M:
            return None
        before, after = line.interpolate(max(along - 10, 0)), line.interpolate(min(along + 10, line.length))
        heading = bearing((before.x, before.y), (after.x, after.y))
        road_against_link = item.get("road_direction", placement.get("retning")) == "MOT"
        against = (direction == AGAINST_METERING) != road_against_link
        return (heading + 180) % 360 if against else heading

    @staticmethod
    def section(item):
        """An average speed section, as an entry of kind average_speed, in the direction it checks."""
        direction = item["properties"].get("Kontrollretning")
        line = chained(lines_of(item.get("wkt")))
        if direction not in (WITH_METERING, AGAINST_METERING) or line is None:
            return None
        coordinates = np.array(shapely.simplify(line, SIMPLIFY_M).coords)
        if direction == AGAINST_METERING:
            coordinates = coordinates[::-1]
        lons, lats = TO_WGS84.transform(coordinates[:, 0], coordinates[:, 1])
        wgs = [[round(float(x), 5), round(float(y), 5)] for x, y in zip(lons, lats)]
        return {
            "id": f"nvdb/823/{item['id']}",
            "lat": round(float(lats[0]), 6),
            "lon": round(float(lons[0]), 6),
            "kind": "average_speed",
            "bearings": [line_bearing(wgs)],
            "line": wgs,
        }
