# Licensing

FoxNav is free software. Its parts use different licences, chosen so that the apps and the server stay open while anyone can build compatible clients and servers.

| Path | Licence | Why |
|---|---|---|
| `android/`, `ios/` | GPL-3.0-or-later with the [FoxNav app exception](LICENSES/FoxNav-App-Exception.txt) | Forks of the app stay open. The exception allows app-store distribution and linking Google Play Services. |
| `server/` | AGPL-3.0-or-later | Anyone running a modified server has to share their changes. |
| Everything else: `protocol/`, `shared/`, `design/`, `infra/`, `docs/` and the build files | Apache-2.0 | Anyone can implement the protocol and build compatible clients or servers. |

The full licence texts are in [LICENSES/](LICENSES/). Contributions are accepted under the licence of the directory they change; see [CONTRIBUTING.md](CONTRIBUTING.md).

The bundled fonts, Chakra Petch and IBM Plex Sans in `android/app/src/main/res/font/`, are under the SIL Open Font License 1.1: see `LICENSES/OFL-1.1-*.txt`.

Map data © OpenStreetMap contributors, available under the [Open Database License](https://www.openstreetmap.org/copyright).

Copyright © 2026 Niko Huuskonen and the FoxNav contributors.
