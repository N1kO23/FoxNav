"""Official Finnish speed limits from Digiroad, for official_limits.py to match to OpenStreetMap.

Digiroad, the national road and street database, moved from Väylävirasto to Fintraffic on
1 January 2026 and becomes Digitraffic Road Network: Fintraffic publishes it (CC BY 4.0) at
https://tie.digitraffic.fi/roadnetwork/. The road authorities keep its limits up to date, winter
limits included, so they win over OpenStreetMap's where the two disagree (build.py --prefer).
Digiroad has a limit for nearly every public road and street in Finland; OpenStreetMap has one for
about half of them. Where both do, they agree on about 90 % of road length (most differences are
30 vs 40 or 40 vs 50).

This reads Digiroad's K format as Shapefiles, one directory per area, as fetch-data.sh downloads
them: <dir>/<AREA>/DR_NOPEUSRAJOITUS_K.shp (limits) and DR_TALVINOPEUSRAJOITUS_K.shp (winter
limits, without dates: build.py gives them the region's winter). Release 2/2026 is the last in
this format. Fintraffic's own Road Network format follows from spring 2027 (DATEX II, after the
EU's RTTI regulation); it needs a reader of its own, next to this one.
"""
import os

import shapefile
import shapely

from official_limits import rule_key, segments

# Fintraffic asks for the source, the licence and a note that the data was changed (here: matched to OpenStreetMap).
ATTRIBUTION = "speed limits from Digiroad (Digitraffic Road Network) © Fintraffic, CC BY 4.0, matched to OpenStreetMap"

LIMITS = "DR_NOPEUSRAJOITUS_K"
WINTER_LIMITS = "DR_TALVINOPEUSRAJOITUS_K"
WINTER = rule_key({"season": "winter"})


def read_layer(path):
    """(lines, limits) of a Digiroad speed limit layer, or None if the area doesn't have it."""
    if not os.path.exists(path + ".shp"):
        return None
    reader = shapefile.Reader(path)
    names = [field[0] for field in reader.fields[1:]]
    value_at = names.index("ARVO")
    lines, limits = [], []
    for shape, record in zip(reader.iterShapes(), reader.iterRecords()):
        if len(shape.points) >= 2:
            lines.append(shapely.linestrings([point[:2] for point in shape.points]))
            limits.append(record[value_at])
    return segments(lines, limits)


class Digiroad:
    name = "Digiroad"
    # ETRS-TM35FIN, in metres.
    crs = "EPSG:3067"
    attribution = ATTRIBUTION

    def __init__(self, directory):
        self.directory = directory

    def areas(self):
        for area in sorted(os.listdir(self.directory)):
            plain = read_layer(os.path.join(self.directory, area, LIMITS))
            if plain is None:
                continue
            yield area, {None: plain, WINTER: read_layer(os.path.join(self.directory, area, WINTER_LIMITS))}
