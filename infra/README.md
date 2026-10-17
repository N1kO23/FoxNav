# Local map stack

Everything the app needs from a backend, running on your machine behind one origin, `http://localhost:8080`:

| Path                               | Service                                          | What it does                                                |
| ---------------------------------- | ------------------------------------------------ | ----------------------------------------------------------- |
| `/.well-known/foxnav`              | FoxNav server                                    | Discovery document: tells the app where everything else is  |
| `/styles/{night,day}.json`         | FoxNav server                                    | MapLibre styles, filled in with the address the client used |
| `/v1/ws`                           | FoxNav server                                    | Convoy relay (WebSocket), forwarding encrypted positions    |
| `/v1/reports`                      | FoxNav server                                    | Reports: police, accidents, roadworks… and "still there?"   |
| `/join`, `/web/*`                  | FoxNav server                                    | Convoy page: invite links open here, in any browser         |
| `/datapacks/FI/cameras.json`       | Caddy (static)                                   | Speed cameras for the app to match on the phone             |
| `/datapacks/FI/speedlimits.json`   | Caddy (static)                                   | Winter limits, and official limits where OSM has none       |
| `/datapacks/FI/search/*`           | Caddy (static)                                   | Search pack: places and addresses, for searching offline    |
| `/routing-tiles/*`                 | Caddy (static)                                   | Valhalla's graph as tiles, for routing on the phone offline |
| `/tiles/region/{z}/{x}/{y}.mvt`    | [go-pmtiles](https://github.com/protomaps/go-pmtiles) | Map tiles from the archive: cacheable, and downloadable for offline use |
| `/tiles/region.pmtiles`            | Caddy (static)                                   | The vector map archive itself, for `pmtiles://` clients     |
| `/assets/fonts`, `/assets/sprites` | Caddy (static)                                   | Label fonts and map icons                                   |
| `/valhalla/*`                      | [Valhalla](https://github.com/valhalla/valhalla) | Routing and turn-by-turn instructions                       |
| `/photon/*`                        | [Photon](https://github.com/komoot/photon)       | Address and place search                                    |

The stack currently covers **Finland**. Other regions get added to `scripts/fetch-data.sh`.

## Requirements

- Docker with Compose v2, with the daemon running (on Arch: `sudo systemctl enable --now docker.socket`).
- About 8 GB of disk and 4 GB of free RAM for Finland. The downloads are about 4 GB, of which the map tiles are 2.9 GB; the routing graph and search index built from them take the rest.

## Start it

From the repository root:

```sh
infra/scripts/fetch-data.sh                  # about 4 GB of downloads, resumable
docker compose -f infra/compose.yaml up -d
infra/scripts/smoke-test.sh
```

The first start takes a while because two services build their data:

- **Valhalla** builds its routing graph from the OpenStreetMap extract. For Finland this takes about 5 minutes on an 8-core machine. Follow it with `docker compose -f infra/compose.yaml logs -f valhalla`.
- **Photon** imports the search index, which takes a few minutes. Follow it with `docker compose -f infra/compose.yaml logs -f photon`.

Until they finish, their paths answer with `502`. Later starts take seconds.

Once Valhalla has built its graph, publish it for routing on the phone ([spec §3.3](../protocol/SPEC.md#33-routing-tiles)). This gzips every tile into `data/routing-tiles/`, about a minute and 330 MB for Finland:

```sh
docker compose -f infra/compose.yaml run --rm routing-tiles
```

Until then, `/routing-tiles/manifest.json` answers `404` and the app routes only through the server. Later runs of `fetch-data.sh` publish the graph themselves.

`fetch-data.sh` also builds the search pack for searching on the phone ([spec §3.4](../protocol/SPEC.md#34-search-packs)) from Photon's dump, about two minutes and 18 MB for Finland. To rebuild it after a new dump:

```sh
docker compose -f infra/compose.yaml run --rm search-pack
```

## Use it from the app

Debug builds of the Android app point at `http://10.0.2.2:8080`, which is how the emulator reaches your machine's `localhost`. For a phone on the same network, use `http://<your computer's LAN IP>:8080`. Debug builds allow plain HTTP; release builds don't. On Android 17 and newer, the app first asks for the *Nearby devices* permission, because Android counts LAN addresses (and the emulator's 10.0.2.2) as the local network.

## HTTPS on your network

Browsers follow convoys only on https pages, or on `localhost`: the convoy page decrypts with WebCrypto, which browsers keep to secure contexts. For other devices on your network, the gateway also serves everything over HTTPS on port 8443, with certificates from Caddy's own local CA:

1. Put your computer's name on the network in `infra/.env`, e.g. `FOXNAV_LAN_HOST=mybox.lan`. Use a name the other devices resolve, such as the one your router's DNS gives it. Then run `docker compose -f infra/compose.yaml up -d gateway`.
2. On each device, download `http://<that name>:8080/local-ca.crt` and install it as a CA certificate. On Android, search Settings for *CA certificate*. On iOS, install the profile, then turn on full trust under *Settings → General → About → Certificate Trust Settings*. The CA is kept in the `caddy` volume, so each device needs this only once.
3. Point the app at `https://<that name>:8443`. Invite links carry the app's server address, so the convoy page then opens over HTTPS too. Debug builds trust user-installed CAs; release builds don't.

## Update the data

OpenStreetMap changes daily; refresh whenever you like:

- **Routing:** delete `data/valhalla/` and re-run `fetch-data.sh`. The next start rebuilds the graph; then publish it again with `docker compose -f infra/compose.yaml run --rm routing-tiles`. The previous build stays published for phones still downloading it, and older ones are removed.
- **Search:** delete `data/photon/` and re-run `fetch-data.sh`. The next start re-imports, and the search pack is rebuilt from the new dump.
- **Map tiles:** delete `data/tiles/region.pmtiles` and re-run `fetch-data.sh`.
- **Speed cameras and speed limits:** after new routing data, rebuild the data packs with `docker compose -f infra/compose.yaml run --rm datapacks`. For Finland that includes matching Digiroad's official limits to the map, about 7 minutes; where OpenStreetMap and Digiroad disagree, Digiroad wins, winter limits included, unless you set `FOXNAV_SPEED_LIMITS_PREFER=osm`. Fintraffic publishes Digiroad (now Digitraffic Road Network) a few times a year: re-running `fetch-data.sh` fetches a newer release when there is one, and rebuilds the packs from it. When the road agency announces the winter dates, pass them, e.g. `… run --rm datapacks --region FI --digiroad /digiroad --season winter=10-27:04-12 --season summer=04-13:10-26 /osm/finland-latest.osm.pbf /out`.

## Map styles

`styles/night.json` and `styles/day.json` are templates generated from the [Protomaps basemap](https://docs.protomaps.com/basemaps/maplibre). `{{origin}}` in them is replaced per request, so the same style works from `localhost`, the emulator, your LAN or a public domain. To regenerate them:

```sh
cd infra/scripts/styles && npm install && npm run generate
```

These are starting points. The FoxNav look gets applied once the design is settled (see `design/`).

## Running a public server

The same stack works on a server:

1. In the `Caddyfile`, replace the HTTPS site's address (`{$FOXNAV_LAN_HOST:localhost}:8443`) with your domain, and remove its `tls internal` and the global `auto_https` option. Caddy then gets TLS certificates from Let's Encrypt automatically, and redirects plain HTTP to HTTPS. Publish ports 80 and 443 instead of 8080 and 8443.
2. Set `FOXNAV_NAME` and `FOXNAV_PRIVACY_URL` for the `server` service in `compose.yaml`. Drivers' reports are kept in the `reports` volume (`FOXNAV_REPORTS_FILE`); `FOXNAV_FINTRAFFIC=true` adds Fintraffic's traffic messages for Finland, fetched every 5 minutes. Turn it off for a server outside Finland.
3. Users type your domain into the app, and the app reads `https://your.domain/.well-known/foxnav`.

## Attribution

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL), speed limits from [Digiroad (Digitraffic Road Network)](https://www.fintraffic.fi/fi/digitrafficroadnetwork) © Fintraffic (CC BY 4.0), matched to OpenStreetMap, map tiles from [Protomaps](https://protomaps.com) builds, search dumps from [GraphHopper](https://download1.graphhopper.com/public/), traffic messages from [Fintraffic / digitraffic.fi](https://www.digitraffic.fi) (CC BY 4.0), and the Noto Sans fonts (SIL Open Font License). Anything shown to users must credit OpenStreetMap; the styles include the attribution text.
