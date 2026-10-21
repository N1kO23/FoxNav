# Data sources

Where FoxNav's map and safety data comes from, starting with the Nordics. Every source needs attribution, and terms change, so check each dataset's licence before using it.

## Everywhere

| Data | Source | Licence |
|---|---|---|
| Roads, places, speed limits (`maxspeed`), speed cameras (`highway=speed_camera`) | [OpenStreetMap](https://www.openstreetmap.org), via [Geofabrik](https://download.geofabrik.de) extracts | [ODbL](https://www.openstreetmap.org/copyright): credit "© OpenStreetMap contributors"; share-alike for derived databases |
| Map tiles | [Protomaps](https://protomaps.com) daily planet builds | ODbL (OpenStreetMap data) |
| Search index | [Photon](https://github.com/komoot/photon) dumps by [GraphHopper](https://download1.graphhopper.com/public/) | ODbL |
| Legal default speed limits per country and road type | [osm-legal-default-speeds](https://github.com/westnordost/osm-legal-default-speeds) | see project |

If OSM-derived cameras and users' reports are merged into one database, ODbL share-alike may apply to that database. The reports feed is public anyway (`GET /v1/reports`); the plan is to publish it under ODbL. Official reports in it keep their source and licence, and the app shows the source with each one.

## Nordics

| | Speed limits | Fixed speed cameras | Live events and conditions |
|---|---|---|---|
| 🇫🇮 Finland | [Digiroad](https://www.fintraffic.fi/fi/digitrafficroadnetwork), now Fintraffic's Digitraffic Road Network (CC BY 4.0), including winter speed limits. **Used**: ahead of OpenStreetMap's limits, winter ones included, matched by position (`infra/datapacks/official_limits.py`, read by `digiroad.py`); downloaded from [Fintraffic's platform](https://tie.digitraffic.fi/roadnetwork/), just the two speed limit layers. Digiroad moved from Väylävirasto to Fintraffic on 1 January 2026: release 2/2026 (September 2026) is the last in the old format. The next, Road Network releases in Fintraffic's own format (DATEX II, after the EU's RTTI regulation), are due in spring 2027 under `releases/` and `latest/`; as of October 2026 neither the data nor its model is published. It needs a reader next to `digiroad.py`, and `fetch-data.sh` says when the data is there. | About 1,000 camera poles, only a few active at a time. Police have published the active ones on the national traffic-situation service; check whether [Digitraffic](https://www.digitraffic.fi) still carries them. Also OSM. | [Digitraffic](https://www.digitraffic.fi) by Fintraffic (CC BY 4.0): traffic messages (**used**, from the `traffic-message/v2` API: they seed reports, credited as "Fintraffic" on each), road weather stations, weather cameras |
| 🇸🇪 Sweden | NVDB, via [Trafikverket's open API](https://data.trafikverket.se) | Trafikverket's open API: about 2,800 automatic speed cameras (ATK), CC0, free API key | Trafikverket's open API: incidents, road conditions |
| 🇳🇴 Norway | [NVDB](https://nvdbapiles.atlas.vegvesen.no) by Statens vegvesen (NLOD 2.0), open, no key. **Used**: object type 105 *Fartsgrense* on European, national, county and municipal roads, matched to OpenStreetMap like Digiroad's (`infra/datapacks/nvdb.py`), and 721 *Fartsgrense, variabel*: winter and summer limits with their own dates, and school zones on weekdays at set hours. Downloaded by `infra/scripts/nvdb-fetch.py`, monthly. | NVDB, **used**: 162 *ATK-punkt*, about 465 cameras, each with the direction it checks, and 823 *Streknings-ATK*, about 86 average speed sections. Also OSM, except where it maps an NVDB camera. | Statens vegvesen's [DATEX II](https://www.vegvesen.no/fag/teknologi/apne-data/et-utvalg-apne-data/hva-er-datex/) feed (NLOD 2.0), **used** with an account (`FOXNAV_VEGVESEN_USERNAME`, `FOXNAV_VEGVESEN_PASSWORD`), which Statens vegvesen gives on request: incidents, closures, hazards, and roadworks that hold up traffic |
| 🇩🇰 Denmark | OSM plus legal defaults | OSM plus user reports | National traffic data portal (DATEX II); needs checking |

### Things to handle

- **Winter speed limits.** Finland, Sweden and Norway lower limits on some roads in winter, with dates that vary by year and region. In OSM they appear as `maxspeed:conditional`, mostly as "100 @ winter" without dates; FoxNav's speed limit pack takes them from there and sets the dates per region (`infra/datapacks/build.py`). Digiroad has them as a separate dataset, and FoxNav prefers those; the agencies announce the dates each autumn.
- **Average-speed sections** (Norway's *streknings-ATK*). These need a running-average display, not a point alert: the app warns as a section comes up, then shows the driver's average over it so far and the distance to its end, red while it's over the limit. Sweden's and Finland's are point cameras only.
- **Limits at set hours.** School zones hold on school days at set hours. OpenStreetMap writes them as `30 @ (Mo-Fr 07:00-17:00)` and NVDB as type *Skole* with weekdays and times; the pack keeps both, and the app applies them by the phone's clock.
- **Showing camera locations is legal in all four countries.** Elsewhere it isn't always: Germany forbids drivers from using camera warnings, Switzerland bans them, and France allows only vague "danger zones". Camera alerts therefore have a per-country switch.
