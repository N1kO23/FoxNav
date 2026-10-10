# 0004: GPL apps, AGPL server, Apache-2.0 protocol

**Status:** accepted, October 2026

## Context

FoxNav is a community open-source project. Forks of the app and hosted versions of the server should stay open, but anyone should be free to build compatible clients and servers.

Plain GPL is widely considered incompatible with app-store terms once other people's contributions are involved. The app may also link Google Play Services in its Play Store build.

## Decision

| Part | Licence |
|---|---|
| Apps (`android/`, `ios/`) | GPL-3.0-or-later plus an [additional permission](../../LICENSES/FoxNav-App-Exception.txt) for app-store distribution and Google Play Services |
| Server (`server/`) | AGPL-3.0-or-later |
| Protocol, shared code, infrastructure, docs | Apache-2.0 |

Contributions are made under the directory's licence, certified with a DCO sign-off (see [CONTRIBUTING.md](../../CONTRIBUTING.md)).

## Consequences

- The app-store permission is in place from the first commit, so every contribution comes with it and no contributor licence agreement is needed.
- Companies can build compatible clients and servers from the Apache-2.0 parts, but modified FoxNav apps and servers stay open.
- Ferrostar and MapLibre (BSD) are compatible with all of these licences.
