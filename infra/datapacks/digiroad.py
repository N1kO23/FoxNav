"""Official Finnish speed limits from Digiroad (Väylävirasto, CC BY 4.0), matched to OpenStreetMap ways.

Digiroad has a limit for nearly every public road and street in Finland; OpenStreetMap has one for
about half of them. Digiroad's segments don't share geometry or IDs with OpenStreetMap, so each way
is sampled every 20 m, and a sample takes the limit of a Digiroad segment within 10 m that runs the
same way (within 30°). A way needs at least 60 % of its samples to have found one. Its main limit
is the most common one; stretches of 60 m or more with another limit (a village's 50 on a long
rural road) become sections with their own geometry. Shorter blips are noise near junctions. If
that still leaves more than 20 % of the samples disagreeing, the way is left alone. Where both
sources have a limit, they agree on about 90 % of road length (most differences are 30 vs 40 or
40 vs 50).

Expects Digiroad's K format as Shapefiles, one directory per area, as fetch-data.sh downloads them:
<dir>/<AREA>/DR_NOPEUSRAJOITUS_K.shp (limits) and DR_TALVINOPEUSRAJOITUS_K.shp (winter limits).
"""
import collections
import os
import subprocess

import numpy as np
import osmium
import shapefile
import shapely
import shapely.ops
from pyproj import Transformer

ATTRIBUTION = "speed limits from Digiroad © Väylävirasto, CC BY 4.0"

LIMITS = "DR_NOPEUSRAJOITUS_K"
WINTER_LIMITS = "DR_TALVINOPEUSRAJOITUS_K"

# Roads with speed limits worth knowing; service roads (parking, driveways) are left out.
ROADS = ("motorway,trunk,primary,secondary,tertiary,unclassified,residential,living_street,road,"
         "motorway_link,trunk_link,primary_link,secondary_link,tertiary_link")

STEP_M = 20.0
END_M = 8.0  # junctions are ambiguous: start and end this far in
MAX_DISTANCE_M = 10.0
MAX_ANGLE_DEG = 30.0
MIN_MATCHED = 0.6
MIN_AGREEING = 0.8
MIN_SECTION_SAMPLES = 3
SIMPLIFY_M = 5.0

# Digiroad is in ETRS-TM35FIN, in metres; OpenStreetMap is turned into it rather than the reverse.
TO_TM35 = Transformer.from_crs("EPSG:4326", "EPSG:3067", always_xy=True)
TO_WGS84 = Transformer.from_crs("EPSG:3067", "EPSG:4326", always_xy=True)


def read_segments(path):
    """(lines, limits) of a Digiroad speed limit layer, or None if the area doesn't have it."""
    if not os.path.exists(path + ".shp"):
        return None
    reader = shapefile.Reader(path)
    names = [field[0] for field in reader.fields[1:]]
    value_at = names.index("ARVO")
    lines, limits = [], []
    for shape, record in zip(reader.iterShapes(), reader.iterRecords()):
        limit = record[value_at]
        if len(shape.points) >= 2 and isinstance(limit, int) and 5 <= limit <= 200:
            lines.append(shapely.linestrings([point[:2] for point in shape.points]))
            limits.append(limit)
    return (np.array(lines), np.array(limits)) if lines else None


class Ways(osmium.SimpleHandler):
    """Roads in one area: ID, tags that matter, and geometry in metres."""

    def __init__(self):
        super().__init__()
        self.ids, self.tagged, self.maxspeed, self.conditional, self.coords = [], [], [], [], []

    def way(self, way):
        try:
            points = [(node.lon, node.lat) for node in way.nodes]
        except osmium.InvalidLocationError:
            return
        if len(points) < 2:
            return
        xs, ys = TO_TM35.transform([p[0] for p in points], [p[1] for p in points])
        tags = way.tags
        self.ids.append(way.id)
        self.tagged.append(any(key in tags for key in ("maxspeed", "maxspeed:forward", "maxspeed:backward")))
        self.maxspeed.append(tags.get("maxspeed"))
        self.conditional.append("maxspeed:conditional" in tags)
        self.coords.append(np.column_stack([xs, ys]))


def sample(ways):
    """Points along every way, which way each belongs to, the way's heading there (degrees), the
    way lengths, the lines, and how far along its way each point is."""
    lines = shapely.linestrings(np.concatenate(ways.coords), indices=np.repeat(np.arange(len(ways.coords)), [len(c) for c in ways.coords]))
    lengths = shapely.length(lines)
    short = lengths < 2 * END_M
    counts = np.where(short, 1, np.floor((lengths - 2 * END_M) / STEP_M).astype(int) + 1)
    owners = np.repeat(np.arange(len(lines)), counts)
    index = np.arange(len(owners)) - np.repeat(np.cumsum(counts) - counts, counts)
    length = lengths[owners]
    at = np.where(short[owners], length / 2, END_M + index * STEP_M)
    line = lines[owners]
    return shapely.line_interpolate_point(line, at), owners, heading(line, at, length), lengths, lines, at


def heading(lines, at, lengths):
    before = shapely.line_interpolate_point(lines, np.maximum(at - 2, 0))
    after = shapely.line_interpolate_point(lines, np.minimum(at + 2, lengths))
    return np.degrees(np.arctan2(shapely.get_y(after) - shapely.get_y(before), shapely.get_x(after) - shapely.get_x(before)))


def match(points, headings, segments):
    """Each sample's limit: 0 where none was found, -1 where two different ones were (one per
    direction, or two roads), which doesn't count either way."""
    lines, limits = segments
    tree = shapely.STRtree(lines)
    (sample_at, segment_at), _ = tree.query_nearest(points, max_distance=MAX_DISTANCE_M, return_distance=True, all_matches=True)
    values = np.zeros(len(points), dtype=int)
    if len(sample_at) == 0:
        return values
    matched = lines[segment_at]
    at = shapely.line_locate_point(matched, points[sample_at])
    turn = np.abs((headings[sample_at] - heading(matched, at, shapely.length(matched)) + 90) % 180 - 90)
    parallel = turn <= MAX_ANGLE_DEG
    for sample_index, limit in zip(sample_at[parallel], limits[segment_at[parallel]]):
        values[sample_index] = limit if values[sample_index] in (0, limit) else -1
    return values


def profile(values, positions, length):
    """(main limit, [(start m, end m, limit)]) along one way from its samples' limits, or None
    when they don't say enough."""
    if np.count_nonzero(values) < MIN_MATCHED * len(values):
        return None
    good = np.nonzero(values > 0)[0]
    if len(good) == 0:
        return None
    main = collections.Counter(values[good].tolist()).most_common(1)[0][0]
    # Runs of equal limits over the samples that found one; short runs of another limit are noise.
    runs = []  # [limit, first sample, last sample, count]
    for index in good:
        limit = int(values[index])
        if runs and runs[-1][0] == limit:
            runs[-1][2], runs[-1][3] = index, runs[-1][3] + 1
        else:
            runs.append([limit, index, index, 1])
    merged = []
    for run in runs:
        if run[0] != main and run[3] < MIN_SECTION_SAMPLES:
            run = [main, run[1], run[2], run[3]]
        if merged and merged[-1][0] == run[0]:
            merged[-1][2], merged[-1][3] = run[2], merged[-1][3] + run[3]
        else:
            merged.append(run)
    agreeing = sum(np.count_nonzero(values[run[1]:run[2] + 1] == run[0]) for run in merged)
    if agreeing < MIN_AGREEING * len(good):
        return None
    sections = []
    for number, (limit, first, last, _) in enumerate(merged):
        if limit == main:
            continue
        # The change is somewhere between two samples: halfway.
        start = 0.0 if number == 0 else (positions[merged[number - 1][2]] + positions[first]) / 2
        end = length if number == len(merged) - 1 else (positions[last] + positions[merged[number + 1][1]]) / 2
        sections.append((start, end, limit))
    return main, sections


def section_line(line, start, end):
    """The stretch of [line] between [start] and [end] metres, simplified, as [[lon, lat], …]."""
    part = shapely.simplify(shapely.ops.substring(line, start, end), SIMPLIFY_M)
    xs, ys = TO_WGS84.transform(*np.array(part.coords).T)
    return [[round(x, 5), round(y, 5)] for x, y in zip(xs, ys)]


def conflate(directory, extract, work, log=print):
    """Digiroad's limits for the OpenStreetMap ways in [extract], keyed by way ID.

    Returns (limits, winter_limits):
    - limits: for every way Digiroad has a clear limit for, a dict with "main" (km/h), "sections"
      ([{"maxspeed", "line"}], stretches with another limit), "osm" (OpenStreetMap's limit as a
      number, if it has one) and "tagged" (whether OpenStreetMap has any limit, number or not);
    - winter_limits: (winter km/h, OpenStreetMap's limit or None) for ways without
      maxspeed:conditional in OpenStreetMap, where Digiroad's winter limit is the same all along.
    """
    roads = os.path.join(work, "digiroad-roads.osm.pbf")
    subprocess.run(["osmium", "tags-filter", extract, f"w/highway={ROADS}", "-o", roads, "--overwrite"], check=True)
    limits, winter, support = {}, {}, {}
    agreement = collections.Counter()
    for area in sorted(os.listdir(directory)):
        layers = {name: read_segments(os.path.join(directory, area, name)) for name in (LIMITS, WINTER_LIMITS)}
        if layers[LIMITS] is None:
            continue
        min_x, min_y, max_x, max_y = shapely.total_bounds(layers[LIMITS][0])
        west, south = TO_WGS84.transform(min_x - 100, min_y - 100)
        east, north = TO_WGS84.transform(max_x + 100, max_y + 100)
        clipped = os.path.join(work, "digiroad-area.osm.pbf")
        subprocess.run(["osmium", "extract", "-b", f"{west},{south},{east},{north}", roads, "-o", clipped, "--overwrite", "--no-progress"], check=True)
        ways = Ways()
        ways.apply_file(clipped, locations=True)
        if not ways.ids:
            continue
        points, owners, headings, lengths, lines, positions = sample(ways)
        offsets = np.concatenate([[0], np.cumsum(np.bincount(owners, minlength=len(ways.ids)))])
        for name, segments in layers.items():
            if segments is None:
                continue
            values = match(points, headings, segments)
            for way in np.unique(owners[values != 0]):
                span = slice(offsets[way], offsets[way + 1])
                found = profile(values[span], positions[span], lengths[way])
                if found is None:
                    continue
                main, sections = found
                way_id, osm = ways.ids[way], ways.maxspeed[way]
                osm = int(osm) if osm and osm.isdigit() else None
                strength = int(np.count_nonzero(values[span]))
                if name == WINTER_LIMITS:
                    if not sections and not ways.conditional[way] and strength > support.get(("winter", way_id), 0):
                        winter[way_id] = (main, osm)
                        support[("winter", way_id)] = strength
                    continue
                if osm is not None and not sections:
                    agreement[osm == main] += lengths[way]
                if strength > support.get(way_id, 0):
                    support[way_id] = strength
                    limits[way_id] = {
                        "main": main,
                        "sections": [{"maxspeed": limit, "line": section_line(lines[way], start, end)} for start, end, limit in sections],
                        "osm": osm,
                        "tagged": ways.tagged[way],
                    }
        log(f"  Digiroad {area}: {len(layers[LIMITS][0])} segments, {len(limits)} ways so far")
    total = agreement[True] + agreement[False]
    if total:
        log(f"  Digiroad agrees with OpenStreetMap on {100 * agreement[True] / total:.0f}% of the length where both have one limit for a way")
    return limits, winter
