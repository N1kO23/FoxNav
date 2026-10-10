# Data sources

Where FoxNav's map and safety data comes from, starting with the Nordics. Every source needs attribution, and terms change, so check each dataset's licence before using it.

## Everywhere

| Data | Source | Licence |
|---|---|---|
| Roads, places, speed limits (`maxspeed`), speed cameras (`highway=speed_camera`) | [OpenStreetMap](https://www.openstreetmap.org), via [Geofabrik](https://download.geofabrik.de) extracts | [ODbL](https://www.openstreetmap.org/copyright): credit "© OpenStreetMap contributors"; share-alike for derived databases |
| Map tiles | [Protomaps](https://protomaps.com) daily planet builds | ODbL (OpenStreetMap data) |
| Search index | [Photon](https://github.com/komoot/photon) dumps by [GraphHopper](https://download1.graphhopper.com/public/) | ODbL |
| Legal default speed limits per country and road type | [osm-legal-default-speeds](https://github.com/westnordost/osm-legal-default-speeds) | see project |

If OSM-derived cameras and users' reports are merged into one database, ODbL share-alike may apply to that database. The plan is to publish the reports database under ODbL anyway.

## Nordics

| | Speed limits | Fixed speed cameras | Live events and conditions |
|---|---|---|---|
| 🇫🇮 Finland | [Digiroad](https://vayla.fi/vaylista/aineistot/digiroad/aineisto) (Väylävirasto, CC BY 4.0), including winter speed limits | About 1,000 camera poles, only a few active at a time. Police have published the active ones on the national traffic-situation service; check whether [Digitraffic](https://www.digitraffic.fi) still carries them. Also OSM. | [Digitraffic](https://www.digitraffic.fi) by Fintraffic (CC BY 4.0): traffic messages, road weather stations, weather cameras |
| 🇸🇪 Sweden | NVDB, via [Trafikverket's open API](https://data.trafikverket.se) | Trafikverket's open API: about 2,800 automatic speed cameras (ATK), CC0, free API key | Trafikverket's open API: incidents, road conditions |
| 🇳🇴 Norway | [NVDB API](https://api.vegdata.no), object type 105 *fartsgrense* (NLOD licence) | NVDB object type 162 *ATK-punkt*. Norway also has average-speed sections. | Statens vegvesen DATEX II feed |
| 🇩🇰 Denmark | OSM plus legal defaults | OSM plus user reports | National traffic data portal (DATEX II); needs checking |

### Things to handle

- **Winter speed limits.** Finland, Sweden and Norway lower limits on some roads in winter, with dates that vary by year and region. In OSM they appear as `maxspeed:conditional`; Digiroad has them as a separate dataset.
- **Average-speed sections** (Norway's *streknings-ATK*). These need a running-average display, not a point alert.
- **Showing camera locations is legal in all four countries.** Elsewhere it isn't always: Germany forbids drivers from using camera warnings, Switzerland bans them, and France allows only vague "danger zones". Camera alerts therefore have a per-country switch.
