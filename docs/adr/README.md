# Architecture decision records

Short records of decisions that shape FoxNav, so nobody has to rediscover why. Each one says what was decided, why, and what it costs. To change a decision, add a new record that supersedes the old one.

| # | Decision |
|---|---|
| [0001](0001-navigation-sdk.md) | Build on Ferrostar and MapLibre instead of a commercial navigation SDK |
| [0002](0002-convoy-transport.md) | Convoys use a WebSocket relay, not WebRTC |
| [0003](0003-convoy-encryption.md) | Convoy payloads are end-to-end encrypted |
| [0004](0004-licensing.md) | GPL apps, AGPL server, Apache-2.0 protocol |
| [0005](0005-offline-routing.md) | Routing and road lookups also run on the phone, on the server's own graph |
| [0006](0006-offline-search.md) | Search works offline from search packs that come with road data |
