# Local map stack

Everything the app needs from a backend, running on your machine behind one origin, `http://localhost:8080`:

| Path                               | Service                                          | What it does                                                |
| ---------------------------------- | ------------------------------------------------ | ----------------------------------------------------------- |
| `/.well-known/foxnav`              | FoxNav server                                    | Discovery document: tells the app where everything else is  |
| `/styles/{night,day}.json`         | FoxNav server                                    | MapLibre styles, filled in with the address the client used |
| `/v1/ws`                           | FoxNav server                                    | Convoy relay (WebSocket), forwarding encrypted positions    |
| `/tiles/region.pmtiles`            | Caddy (static)                                   | Vector map data for the region                              |
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

## Use it from the app

Debug builds of the Android app point at `http://10.0.2.2:8080`, which is how the emulator reaches your machine's `localhost`. For a phone on the same network, use `http://<your computer's LAN IP>:8080`. Debug builds allow plain HTTP; release builds don't. On Android 17 and newer, the app first asks for the *Nearby devices* permission, because Android counts LAN addresses (and the emulator's 10.0.2.2) as the local network.

## Update the data

OpenStreetMap changes daily; refresh whenever you like:

- **Routing:** delete `data/valhalla/` and re-run `fetch-data.sh`. The next start rebuilds the graph.
- **Search:** delete `data/photon/` and re-run `fetch-data.sh`. The next start re-imports.
- **Map tiles:** delete `data/tiles/region.pmtiles` and re-run `fetch-data.sh`.

## Map styles

`styles/night.json` and `styles/day.json` are templates generated from the [Protomaps basemap](https://docs.protomaps.com/basemaps/maplibre). `{{origin}}` in them is replaced per request, so the same style works from `localhost`, the emulator, your LAN or a public domain. To regenerate them:

```sh
cd infra/scripts/styles && npm install && npm run generate
```

These are starting points. The FoxNav look gets applied once the design is settled (see `design/`).

## Running a public server

The same stack works on a server:

1. Replace `:8080` in the `Caddyfile` with your domain. Caddy then gets TLS certificates automatically; also publish ports 80 and 443 instead of 8080.
2. Set `FOXNAV_NAME` and `FOXNAV_PRIVACY_URL` for the `server` service in `compose.yaml`.
3. Users type your domain into the app, and the app reads `https://your.domain/.well-known/foxnav`.

## Attribution

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL), map tiles from [Protomaps](https://protomaps.com) builds, search dumps from [GraphHopper](https://download1.graphhopper.com/public/), and the Noto Sans fonts (SIL Open Font License). Anything shown to users must credit OpenStreetMap; the styles include the attribution text.
