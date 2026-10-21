"""Official speed limits, matched to OpenStreetMap ways: the part every national source shares.

A road authority's database (Finland's Digiroad, Norway's NVDB, ...) has a limit for nearly every
public road, but its segments share neither geometry nor IDs with OpenStreetMap. So each way is
sampled every 20 m, and a sample takes the limit of an official segment within 10 m that runs the
same way (within 30°). A way needs at least 60 % of its samples to have found one. Its main limit
is the most common one; stretches of 60 m or more with another limit (a village's 50 on a long
rural road) become sections with their own geometry. Shorter blips are noise near junctions. If
that still leaves more than 20 % of the samples disagreeing, the way is left alone.

A source is an object with:
- `name`: for the log;
- `crs`: the coordinate system its segments are in, a projected one in metres, e.g. "EPSG:3067";
- `areas()`: yields `(name, layers)` one area at a time (to keep memory in check), where layers
  maps a rule to the area's segments with that rule, as `(lines, limits)`: numpy arrays of shapely
  LineStrings and of km/h. The rule is `None` for the plain limits, or a conditional rule (a dict
  as in protocol/SPEC.md §3.2: `from`, `to`, and optionally `season`, `days`, `times`), frozen as
  a tuple of its items so it can be a key. A season without dates (Digiroad's winter limits) has
  `from` and `to` left out; build.py fills them in per region.
"""
import collections
import subprocess

import numpy as np
import osmium
import shapely
import shapely.ops
from pyproj import Transformer

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
MIN_KMH, MAX_KMH = 5, 200


def segments(lines, limits):
    """(lines, limits) as numpy arrays, keeping plausible limits; None when nothing is left."""
    kept = [(line, limit) for line, limit in zip(lines, limits) if isinstance(limit, int) and MIN_KMH <= limit <= MAX_KMH]
    if not kept:
        return None
    return np.array([line for line, _ in kept]), np.array([limit for _, limit in kept])


def rule_key(rule):
    """A conditional rule (dict) as a dict key, its lists (of lists, for times) as tuples; None stays None."""
    def frozen(value):
        return tuple(frozen(item) for item in value) if isinstance(value, list) else value

    return None if rule is None else tuple(sorted((key, frozen(value)) for key, value in rule.items()))


def rule_of(key):
    """The rule dict back from rule_key."""
    def thawed(value):
        return [thawed(item) for item in value] if isinstance(value, tuple) else value

    return None if key is None else {name: thawed(value) for name, value in key}


class Ways(osmium.SimpleHandler):
    """Roads in one area: ID, tags that matter, and geometry in the source's metres."""

    def __init__(self, to_source):
        super().__init__()
        self.to_source = to_source
        self.ids, self.tagged, self.maxspeed, self.conditional, self.coords = [], [], [], [], []

    def way(self, way):
        try:
            points = [(node.lon, node.lat) for node in way.nodes]
        except osmium.InvalidLocationError:
            return
        if len(points) < 2:
            return
        xs, ys = self.to_source.transform([p[0] for p in points], [p[1] for p in points])
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


def match(points, headings, layer):
    """Each sample's limit: 0 where none was found, -1 where two different ones were (one per
    direction, or two roads), which doesn't count either way."""
    lines, limits = layer
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


def section_line(line, start, end, to_wgs84):
    """The stretch of [line] between [start] and [end] metres, simplified, as [[lon, lat], ...]."""
    part = shapely.simplify(shapely.ops.substring(line, start, end), SIMPLIFY_M)
    xs, ys = to_wgs84.transform(*np.array(part.coords).T)
    return [[round(x, 5), round(y, 5)] for x, y in zip(xs, ys)]


def conflate(source, extract, work, log=print):
    """The source's limits for the OpenStreetMap ways in [extract], keyed by way ID.

    Returns (limits, conditional):
    - limits: for every way the source has a clear limit for, a dict with "main" (km/h),
      "sections" ([{"maxspeed", "line"}], stretches with another limit), "osm" (OpenStreetMap's
      limit as a number, if it has one) and "tagged" (whether OpenStreetMap has any limit, number
      or not);
    - conditional: rule key -> {way: (km/h, OpenStreetMap's limit or None)}, for ways without
      maxspeed:conditional in OpenStreetMap where the rule's limit is the same all along.
    """
    to_source = Transformer.from_crs("EPSG:4326", source.crs, always_xy=True)
    to_wgs84 = Transformer.from_crs(source.crs, "EPSG:4326", always_xy=True)
    roads = f"{work}/official-roads.osm.pbf"
    subprocess.run(["osmium", "tags-filter", extract, f"w/highway={ROADS}", "-o", roads, "--overwrite"], check=True)
    limits, conditional, support = {}, collections.defaultdict(dict), {}
    agreement = collections.Counter()
    for area, layers in source.areas():
        present = [layer for layer in layers.values() if layer is not None]
        if not present:
            continue
        bounds = np.array([shapely.total_bounds(lines) for lines, _ in present])
        west, south = to_wgs84.transform(bounds[:, 0].min() - 100, bounds[:, 1].min() - 100)
        east, north = to_wgs84.transform(bounds[:, 2].max() + 100, bounds[:, 3].max() + 100)
        clipped = f"{work}/official-area.osm.pbf"
        subprocess.run(["osmium", "extract", "-b", f"{west},{south},{east},{north}", roads, "-o", clipped, "--overwrite", "--no-progress"], check=True)
        ways = Ways(to_source)
        ways.apply_file(clipped, locations=True)
        if not ways.ids:
            continue
        points, owners, headings, lengths, lines, positions = sample(ways)
        offsets = np.concatenate([[0], np.cumsum(np.bincount(owners, minlength=len(ways.ids)))])
        for rule, layer in layers.items():
            if layer is None:
                continue
            values = match(points, headings, layer)
            for way in np.unique(owners[values != 0]):
                span = slice(offsets[way], offsets[way + 1])
                found = profile(values[span], positions[span], lengths[way])
                if found is None:
                    continue
                main, sections = found
                way_id, osm = ways.ids[way], ways.maxspeed[way]
                osm = int(osm) if osm and osm.isdigit() else None
                strength = int(np.count_nonzero(values[span]))
                if rule is not None:
                    if not sections and not ways.conditional[way] and strength > support.get((rule, way_id), 0):
                        conditional[rule][way_id] = (main, osm)
                        support[(rule, way_id)] = strength
                    continue
                if osm is not None and not sections:
                    agreement[osm == main] += lengths[way]
                if strength > support.get(way_id, 0):
                    support[way_id] = strength
                    limits[way_id] = {
                        "main": main,
                        "sections": [{"maxspeed": limit, "line": section_line(lines[way], start, end, to_wgs84)} for start, end, limit in sections],
                        "osm": osm,
                        "tagged": ways.tagged[way],
                    }
        plain = layers.get(None)
        log(f"  {source.name} {area}: {0 if plain is None else len(plain[0])} segments, {len(limits)} ways so far")
    total = agreement[True] + agreement[False]
    if total:
        log(f"  {source.name} agrees with OpenStreetMap on {100 * agreement[True] / total:.0f}% of the length where both have one limit for a way")
    return limits, dict(conditional)
