# FoxNav

Navigation for car enthusiasts: turn-by-turn with speed limits and speed cameras, and **live convoys** where you see your friends' cars and speeds on the map as you drive. Open source, built on OpenStreetMap, and you can run your own server.

> **Status:** early development. Against the local backend, the Android app does:
> - free drive with speed limits;
> - search and route alternatives;
> - turn-by-turn with voice;
> - convoys: live cars and speeds, invite links and QR codes, quick messages and a shared destination.
>
> Convoys have been tested on the emulator with bots, not yet on a real drive. See the [roadmap](docs/architecture.md#roadmap).

## What it will do

- **Navigation:**
  - turn-by-turn voice guidance, or free drive with no destination;
  - speed limits with a speedometer and speed camera alerts;
  - Waze-style hazard and police reports.
- **Convoys:**
  - drive together with live positions and speeds;
  - a timing-tower view of the group;
  - a shared destination, regroup pins and one-tap quick messages;
  - later, a shared soundtrack: see what the others are playing and listen along;
  - end-to-end encrypted, so even the server can't see where you are.
- **Your server or ours:** the app talks to any FoxNav server. The whole backend runs from one Docker Compose file.
- **Enthusiast extras:** Android Auto (and later CarPlay), custom navigation voices, twisty-roads routing, meets and events.

## Repository

| Path | What |
|---|---|
| [`android/`](android/) | Android app (Kotlin, Jetpack Compose, [Ferrostar](https://github.com/stadiamaps/ferrostar), [MapLibre](https://maplibre.org)) |
| [`shared/`](shared/) | Kotlin Multiplatform code shared by the app and the server |
| [`server/`](server/) | Reference FoxNav server (Kotlin, Ktor) |
| [`protocol/`](protocol/) | The [protocol spec](protocol/SPEC.md) for compatible clients and servers |
| [`infra/`](infra/) | Local backend: routing, search, map tiles and the server in Docker Compose |
| [`tools/`](tools/) | [Convoy simulator](tools/convoy-sim/): bot cars for testing convoys |
| [`design/`](design/) | [Design direction](design/README.md) and the design tokens that drive the app theme and map styles |
| [`docs/`](docs/) | [Architecture](docs/architecture.md), [decision records](docs/adr/), [threat model](docs/threat-model.md), [data sources](docs/data-sources.md) |

## Getting started

You need JDK 21, plus [Android Studio](https://developer.android.com/studio) for the app and Docker for the backend. [CONTRIBUTING.md](CONTRIBUTING.md) has the details.

```sh
./gradlew check                     # all tests (plus Android lint once the SDK is set up)
./gradlew :server:run               # reference server on http://localhost:8080

infra/scripts/fetch-data.sh         # map data for the local backend (about 4 GB)
docker compose -f infra/compose.yaml up -d
infra/scripts/smoke-test.sh

./gradlew :tools:convoy-sim:installDist   # bot cars for testing convoys, see tools/convoy-sim/
```

The Android app is part of the build when an Android SDK is configured. Open the project in Android Studio and run the `app` configuration; debug builds use the local backend.

## Licence

The apps are GPL-3.0 (with an app-store exception), the server AGPL-3.0, and the protocol and shared code Apache-2.0. See [LICENSE.md](LICENSE.md). Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright).
