# Local map stack

Everything the app needs from a backend, running on your machine behind one origin, `http://localhost:8080`:

| Path                               | Service                                               | What it does                                                            |
| ---------------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------- |
| `/.well-known/foxnav`              | FoxNav server                                         | Discovery document: tells the app where everything else is              |
| `/styles/{night,day}.json`         | FoxNav server                                         | MapLibre styles, filled in with the address the client used             |
| `/v1/ws`                           | FoxNav server                                         | Convoy relay (WebSocket), forwarding encrypted positions                |
| `/v1/reports`                      | FoxNav server                                         | Reports: police, accidents, roadworks... and "still there?"             |
| `/join`, `/web/*`                  | FoxNav server                                         | Convoy page: invite links open here, in any browser                     |
| `/datapacks/FI/cameras.json`       | Caddy (static)                                        | Speed cameras for the app to match on the phone, per region             |
| `/datapacks/FI/speedlimits.json`   | Caddy (static)                                        | Winter limits, and official limits where OSM has none                   |
| `/datapacks/FI/search/*`           | Caddy (static)                                        | Search pack: places and addresses, for searching offline                |
| `/routing-tiles/*`                 | Caddy (static)                                        | Valhalla's graph as tiles, for routing on the phone offline             |
| `/tiles/region/{z}/{x}/{y}.mvt`    | [go-pmtiles](https://github.com/protomaps/go-pmtiles) | Map tiles from the archive: cacheable, and downloadable for offline use |
| `/tiles/region.pmtiles`            | Caddy (static)                                        | The vector map archive itself, for `pmtiles://` clients                 |
| `/assets/fonts`, `/assets/sprites` | Caddy (static)                                        | Label fonts and map icons                                               |
| `/valhalla/*`                      | [Valhalla](https://github.com/valhalla/valhalla)      | Routing and turn-by-turn instructions                                   |
| `/photon/*`                        | [Photon](https://github.com/komoot/photon)            | Address and place search                                                |

The stack covers **Finland**, **Norway** and **Sweden**, any of them, or all of **Europe**: `fetch-data.sh` takes the regions to download, and serves every region it has. Official speed limits come from Digiroad in Finland and NVDB in Norway; other countries' packs are OpenStreetMap's for now (Sweden's Trafikverket data needs an API key). Other regions get added to `scripts/fetch-data.sh`.

## Requirements

- Docker with Compose v2, with the daemon running (on Arch: `sudo systemctl enable --now docker.socket`).
- For Finland alone, about 8 GB of disk and 4 GB of free RAM. The downloads are about 4 GB, of which the map tiles are 2.9 GB; the routing graph and search index built from them take the rest.
- For all three regions, about 20 GB of disk and 8 GB of free RAM. The downloads are about 12 GB, of which the map tiles are 8 GB.
- For Europe, about 200 GB of disk once built, and room for a rebuild next to it: 300 GB or more. 32 GB of RAM is comfortable: Valhalla's build takes 16 GB or more, and Photon wants `FOXNAV_PHOTON_MEMORY=12g` in `infra/.env`. The downloads are about 100 GB: the OpenStreetMap extract is 31 GB, the search dumps and the map tiles about 50 GB each. Valhalla's graph takes several hours to build on 8 cores, Photon's import an hour or more, and the data and search packs another few hours.

## Start it

From the repository root:

```sh
infra/scripts/fetch-data.sh                  # Finland: about 4 GB of downloads, resumable
infra/scripts/fetch-data.sh finland norway sweden    # or all three: about 12 GB
infra/scripts/fetch-data.sh europe           # or Europe: about 100 GB
docker compose -f infra/compose.yaml up -d
infra/scripts/smoke-test.sh
```

Without regions, `fetch-data.sh` updates the ones already downloaded. A region stays until its files are deleted (`data/osm/<region>-latest.osm.pbf`, `data/photon/<region>.jsonl.zst`, `data/datapacks/<code>/`), and the map tiles are cut again whenever the set of regions changes. Neighbouring extracts overlap at their borders, which Valhalla can't take, so they're merged into one for it (`data/valhalla/region-<key>.osm.pbf`, named after what went into it). Once Valhalla has built its graph, the next run of `fetch-data.sh` removes that copy, and the tile folder Valhalla made `valhalla_tiles.tar` from: it serves from the tar, and builds again only for extracts it hasn't built from.

**Europe** has the other three in it. With it, Valhalla's graph and the map tiles are Europe's, from one extract, and each of its 46 countries gets data packs and a search pack. Their packs come from what of the Europe extract they need, split by Geofabrik's country outlines (`data/osm/poly/`); Finland and Norway come along with their own extracts, as matching their official speed limits to the map takes every road. The search dumps are GraphHopper's, per country or a few countries together (`baltics`, `british-islands`...). For searching names in more languages than the Nordic ones, set `FOXNAV_PHOTON_LANGUAGES` in `infra/.env`, e.g. `fi,sv,nb,nn,se,en,de,fr,it,es,pl,nl`. The phones download every country's camera and speed limit packs, likely a few tens of megabytes for Europe; search packs and routing tiles only for the areas their drivers choose.

The first start takes a while because two services build their data:

- **Valhalla** builds its routing graph from the OpenStreetMap extracts. For Finland this takes about 5 minutes on an 8-core machine. It builds it again whenever the extracts change, a new region or newer data. Follow it with `docker compose -f infra/compose.yaml logs -f valhalla`.
- **Photon** imports the search index from every region's dump, which takes a few minutes, and again whenever the dumps change. Follow it with `docker compose -f infra/compose.yaml logs -f photon`.

Until they finish, their paths answer with `502`. Later starts take seconds.

Once Valhalla has built its graph, publish it for routing on the phone ([spec §3.3](../protocol/SPEC.md#33-routing-tiles)). This gzips every tile into `data/routing-tiles/`, about a minute and 330 MB for Finland:

```sh
docker compose -f infra/compose.yaml run --rm routing-tiles
```

Until then, `/routing-tiles/manifest.json` answers `404` and the app routes only through the server. Later runs of `fetch-data.sh` publish the graph themselves.

`fetch-data.sh` also builds each region's search pack for searching on the phone ([spec §3.4](../protocol/SPEC.md#34-search-packs)) from its Photon dump, about two minutes and 18 MB for Finland. To rebuild one after a new dump:

```sh
docker compose -f infra/compose.yaml run --rm search-pack                    # Finland
docker compose -f infra/compose.yaml run --rm search-pack --region NO /photon/norway.jsonl.zst /out
```

## Use it from the app

Debug builds of the Android app point at `http://10.0.2.2:8080`, which is how the emulator reaches your machine's `localhost`. For a phone on the same network, use `http://<your computer's LAN IP>:8080`. Debug builds allow plain HTTP; release builds don't. On Android 17 and newer, the app first asks for the _Nearby devices_ permission, because Android counts LAN addresses (and the emulator's 10.0.2.2) as the local network.

## HTTPS on your network

Browsers follow convoys only on https pages, or on `localhost`: the convoy page decrypts with WebCrypto, which browsers keep to secure contexts. For other devices on your network, the gateway also serves everything over HTTPS on port 8443, with certificates from Caddy's own local CA:

1. Put your computer's name on the network in `infra/.env`, e.g. `FOXNAV_LAN_HOST=mybox.lan`. Use a name the other devices resolve, such as the one your router's DNS gives it. Then run `docker compose -f infra/compose.yaml up -d gateway`.
2. On each device, download `http://<that name>:8080/local-ca.crt` and install it as a CA certificate. On Android, search Settings for _CA certificate_. On iOS, install the profile, then turn on full trust under _Settings → General → About → Certificate Trust Settings_. The CA is kept in the `caddy` volume, so each device needs this only once.
3. Point the app at `https://<that name>:8443`. Invite links carry the app's server address, so the convoy page then opens over HTTPS too. Debug builds trust user-installed CAs; release builds don't.

## Update the data

OpenStreetMap changes daily; refresh whenever you like:

- **Routing:** delete `data/osm/<region>-latest.osm.pbf` and re-run `fetch-data.sh`, which downloads it again and merges the extracts for Valhalla. The next start rebuilds the graph; then publish it again with `docker compose -f infra/compose.yaml run --rm routing-tiles`. The previous build stays published for phones still downloading it, and older ones are removed.
- **Search:** delete `data/photon/<region>.jsonl.zst` (for Europe, the dumps to refresh) and re-run `fetch-data.sh`. The next start re-imports, and the search packs are rebuilt from the new dumps; packs from unchanged dumps aren't.
- **Map tiles:** delete `data/tiles/region.pmtiles` and re-run `fetch-data.sh`.
- **Speed cameras and speed limits:** after new routing data, rebuild a region's data packs by deleting `data/datapacks/<code>/cameras.json` and re-running `fetch-data.sh`, or with `docker compose -f infra/compose.yaml run --rm datapacks` (Finland; see `compose.yaml` for the others). Where OpenStreetMap and the official limits disagree, the official ones win, winter limits included, unless you set `FOXNAV_SPEED_LIMITS_PREFER=osm`.
  - **Finland:** matching Digiroad's official limits to the map takes about 7 minutes. Fintraffic publishes Digiroad (now Digitraffic Road Network) a few times a year: re-running `fetch-data.sh` fetches a newer release when there is one, and rebuilds the packs from it. Release 2/2026 is the last in Digiroad's format; Road Network releases in Fintraffic's own format follow from spring 2027, and `fetch-data.sh` says when one is there, which needs a reader in `infra/datapacks/` first. When the road agency announces the winter dates, pass them, e.g. `... run --rm datapacks --region FI --digiroad /digiroad --season winter=10-27:04-12 --season summer=04-13:10-26 /osm/finland-latest.osm.pbf /out`.
  - **Norway:** `fetch-data.sh` downloads NVDB's speed limits, speed cameras and average speed sections through its open API (`scripts/nvdb-fetch.py`, about 15 minutes), again once the download is a month old, and rebuilds the packs from a new one. NVDB has its own dates for most winter limits.

## Map styles

`styles/night.json` and `styles/day.json` are templates generated from the [Protomaps basemap](https://docs.protomaps.com/basemaps/maplibre). `{{origin}}` in them is replaced per request, so the same style works from `localhost`, the emulator, your LAN or a public domain. To regenerate them:

```sh
cd infra/scripts/styles && npm install && npm run generate
```

These are starting points. The FoxNav look gets applied once the design is settled (see `design/`).

## Running a public server

The same stack works on a server. Its settings go in `infra/.env`, which stays out of git:

```sh
FOXNAV_NAME=FoxNav Helsinki                       # what the app and the convoy page call it
FOXNAV_PRIVACY_URL=https://example.org/privacy    # your privacy policy, linked from the app
FOXNAV_FINTRAFFIC=true                            # Fintraffic's traffic messages; false outside Finland
```

For Norway's traffic messages, from Statens vegvesen's DATEX II feed, [ask Statens vegvesen for an account](https://www.vegvesen.no/en/fag/technology/open-data/a-selection-of-open-data/what-is-datex/get-access/) and add `FOXNAV_VEGVESEN_USERNAME` and `FOXNAV_VEGVESEN_PASSWORD`. Drivers' reports are kept in the `reports` volume. Then `docker compose -f infra/compose.yaml up -d`, and users type your domain into the app, which reads `https://your.domain/.well-known/foxnav`.

### Behind a reverse proxy

With nginx (or another proxy) in front doing TLS, the gateway serves plain HTTP to it. The proxy tells it the address drivers used, so that map styles point at `https://your.domain`, and who each driver is, for the reports' rate limit. The gateway believes that only from the proxy:

```sh
FOXNAV_HTTP_PORT=127.0.0.1:8080        # reachable only from this machine, so only through the proxy
FOXNAV_TRUSTED_PROXIES=private_ranges  # the proxy's address as the gateway sees it
```

A proxy on the same machine reaches the gateway from Docker's network, a private address, hence `private_ranges`; with the port bound to `127.0.0.1` nothing else gets there. A proxy on another machine: its address instead, e.g. `FOXNAV_TRUSTED_PROXIES=192.168.1.5/32`, and `FOXNAV_HTTP_PORT` on an address it reaches.

The proxy passes the host, the scheme and WebSockets (for convoys) on. For nginx:

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 443 ssl;
    server_name your.domain;
    # ssl_certificate ...

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
    }
}
```

Check it with `infra/scripts/smoke-test.sh https://your.domain`, and that `https://your.domain/styles/night.json` names `https://` URLs. Ones with `http://` mean the gateway doesn't believe the proxy: check `FOXNAV_TRUSTED_PROXIES`, and that the proxy sends `X-Forwarded-Proto`.

### Without one

The gateway can get TLS certificates itself: in the `Caddyfile`, replace the HTTPS site's address (`{$FOXNAV_LAN_HOST:localhost}:8443`) with your domain, and remove its `tls internal` and the global `auto_https` option. Caddy then gets certificates from Let's Encrypt and redirects plain HTTP to HTTPS. Publish ports 80 and 443 instead of 8080 and 8443.

## Attribution

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) (ODbL), speed limits from [Digiroad (Digitraffic Road Network)](https://www.fintraffic.fi/fi/digitrafficroadnetwork) © Fintraffic (CC BY 4.0) and from [NVDB](https://nvdbapiles.atlas.vegvesen.no) © Statens vegvesen (NLOD 2.0), matched to OpenStreetMap, speed cameras from NVDB, map tiles from [Protomaps](https://protomaps.com) builds, search dumps from [GraphHopper](https://download1.graphhopper.com/public/), traffic messages from [Fintraffic / digitraffic.fi](https://www.digitraffic.fi) (CC BY 4.0) and Statens vegvesen (NLOD 2.0), and the Noto Sans fonts (SIL Open Font License). Anything shown to users must credit OpenStreetMap; the styles include the attribution text.
