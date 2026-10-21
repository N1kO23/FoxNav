#!/usr/bin/env python3
"""Downloads what the data packs need from NVDB, Norway's national road database (Statens vegvesen,
NLOD 2.0), through its open read API (https://nvdbapiles.atlas.vegvesen.no, no key needed).

    nvdb-fetch.py output-dir

Writes into output-dir, one gzipped JSON object per line:
- 105/<county>.jsonl.gz  speed limits (Fartsgrense) on European, national, county and municipal
                         roads; private and forest roads are left out, being nearly nine tenths of
                         the objects and few of them roads anyone navigates;
- 721/<county>.jsonl.gz  limits for part of the year or the day (Fartsgrense, variabel): winter
                         and summer limits, school zones;
- 162.jsonl.gz           speed cameras (ATK-punkt), each with the road link it stands on, which
                         says which way the camera checks;
- 823.jsonl.gz           average speed sections (Streknings-ATK).
Geometry is in EPSG:5973 (ETRS89 / UTM 33N, with heights), in the road's metering direction.

A download goes into output-dir.new first, each file whole or not at all, so an interrupted run
carries on where it stopped, and replaces output-dir once complete. `.complete` holds the date of
the last full download; a run within 30 days of it does nothing.
Standard library only, like the other scripts that run on the host.
"""
import datetime
import fcntl
import gzip
import json
import os
import shutil
import sys
import time
import urllib.parse
import urllib.request

API = "https://nvdbapiles.atlas.vegvesen.no"
# NVDB asks every client to say who it is.
HEADERS = {"X-Client": "FoxNav-datapacks", "Accept": "application/json"}
ROAD_CATEGORIES = "E,R,F,K"
SRID = "5973"
MAX_AGE_DAYS = 30


def get(path, **params):
    url = f"{API}{path}?{urllib.parse.urlencode(params)}" if params else f"{API}{path}"
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=120) as response:
                return json.load(response)
        except (OSError, ValueError) as error:
            if attempt == 4:
                raise
            print(f"  retrying {path} ({error})", file=sys.stderr)
            time.sleep(5 * (attempt + 1))


def road_objects(type_id, located=False, **filters):
    """Every current object of a type, a page at a time; [located] adds where each sits on the road network."""
    params = {"inkluder": "egenskaper,geometri" + (",lokasjon" if located else ""), "srid": SRID, "antall": 1000, **filters}
    while True:
        page = get(f"/vegobjekter/api/v4/vegobjekter/{type_id}", **params)
        yield from page["objekter"]
        following = page["metadata"].get("neste")
        if page["metadata"]["returnert"] < page["metadata"]["sidestørrelse"] or not following:
            return
        params["start"] = following["start"]


def slim(item):
    """What the builder reads: the ID, properties by name, geometry and where it sits on the network:
    its placements on road links, and which way its road reference (road number and metres) runs
    along the link, the direction a camera's "Kontrollretning" is relative to."""
    location = item.get("lokasjon", {})
    road = (location.get("vegsystemreferanser") or [{}])[0].get("strekning", {})
    slimmed = {
        "id": item["id"],
        "properties": {prop["navn"]: prop.get("verdi") for prop in item.get("egenskaper", []) if prop.get("egenskapstype") != "Geometri"},
        "wkt": item.get("geometri", {}).get("wkt"),
        "placements": [
            {key: placement[key] for key in ("veglenkesekvensid", "relativPosisjon", "startposisjon", "sluttposisjon", "retning") if key in placement}
            for placement in location.get("stedfestinger", [])
        ],
    }
    if road.get("retning"):
        slimmed["road_direction"] = road["retning"]
    return slimmed


def write(path, items):
    """All of [items] to [path], or nothing: written to a .part file first."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    count = 0
    with gzip.open(path + ".part", "wt", encoding="utf-8") as out:
        for item in items:
            out.write(json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n")
            count += 1
    os.replace(path + ".part", path)
    return count


class Links:
    """The road links of the network, fetched as needed: the camera's link gives its direction."""

    def __init__(self):
        self.sequences = {}

    def at(self, sequence_id, position):
        """The current link of the sequence at a relative position: its geometry and span."""
        if sequence_id not in self.sequences:
            self.sequences[sequence_id] = get(f"/vegnett/api/v4/veglenkesekvenser/{sequence_id}", srid=SRID)
        links = [
            link for link in self.sequences[sequence_id].get("veglenker", [])
            if link["startposisjon"] <= position <= link["sluttposisjon"] and "sluttdato" not in link and link.get("geometri")
        ]
        # The road's centre line where there is one, rather than a single carriageway or lane.
        links.sort(key=lambda link: "Vegtrase" not in link.get("detaljnivå", ""))
        if not links:
            return None
        link = links[0]
        return {"wkt": link["geometri"]["wkt"], "start": link["startposisjon"], "end": link["sluttposisjon"]}


def with_link(camera, links):
    point = next((placement for placement in camera["placements"] if "relativPosisjon" in placement), None)
    if point is not None:
        camera["link"] = links.at(point["veglenkesekvensid"], point["relativPosisjon"])
    return camera


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    output = sys.argv[1].rstrip("/")
    # One download at a time: a second run waits, then finds the data recent enough.
    lock = open(output + ".lock", "w")
    fcntl.flock(lock, fcntl.LOCK_EX)
    marker = os.path.join(output, ".complete")
    fetched = datetime.date.fromisoformat(open(marker).read().strip()) if os.path.exists(marker) else None
    if fetched and (datetime.date.today() - fetched).days < MAX_AGE_DAYS:
        # Recent enough: only files that are missing (deleted to fetch again) are fetched, in place.
        final, today = output, fetched
    else:
        # A new download goes next to the old one, which stays in use until this one is complete.
        final, output, today = output, output + ".new", datetime.date.today()
    fetched_any = False

    counties = sorted(county["nummer"] for county in get("/omrader/api/v4/fylker"))
    for type_id, name, filters in ((105, "speed limits", {"vegsystemreferanse": ROAD_CATEGORIES}), (721, "limits for part of the year or day", {})):
        for county in counties:
            path = os.path.join(output, str(type_id), f"{county}.jsonl.gz")
            if os.path.exists(path):
                continue
            count = write(path, (slim(item) for item in road_objects(type_id, fylke=county, **filters)))
            print(f"  ✓ NVDB {name}, county {county}: {count}")
            fetched_any = True

    links = Links()
    path = os.path.join(output, "162.jsonl.gz")
    if not os.path.exists(path):
        count = write(path, (with_link(slim(item), links) for item in road_objects(162, located=True)))
        print(f"  ✓ NVDB speed cameras: {count}")
        fetched_any = True
    path = os.path.join(output, "823.jsonl.gz")
    if not os.path.exists(path):
        count = write(path, (slim(item) for item in road_objects(823, located=True)))
        print(f"  ✓ NVDB average speed sections: {count}")
        fetched_any = True

    if not fetched_any:
        print(f"✓ NVDB data from {fetched} is recent enough")
        return
    with open(os.path.join(output, ".complete"), "w") as out:
        out.write(today.isoformat())
    if output != final:
        shutil.rmtree(final, ignore_errors=True)
        os.replace(output, final)
    # A new marker makes fetch-data.sh rebuild the packs; filling in files keeps the date, so say so.
    print(f"✓ NVDB data downloaded into {final}")


if __name__ == "__main__":
    main()
