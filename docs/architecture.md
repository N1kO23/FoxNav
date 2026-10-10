# Architecture

FoxNav is a navigation app for car enthusiasts:

- Waze-style turn-by-turn navigation with speed limits, speed cameras and hazard reports;
- live **convoys**, where friends see each other's position and speed in real time;
- later, fully custom navigation voices.

Anyone can run a FoxNav server, and the app works with any compatible one.

This document explains how the pieces fit together and why. Decisions with a longer story have their own record in [`adr/`](adr/).

## Principles

- **Don't build what already exists well.** Turn-by-turn navigation comes from the [Ferrostar](https://github.com/stadiamaps/ferrostar) SDK, maps from [MapLibre](https://maplibre.org), and data from [OpenStreetMap](https://www.openstreetmap.org). FoxNav's own code goes into what makes it different: convoys, safety data, the enthusiast features and the look.
- **Protocol first.** "Use any server" means a written [protocol](../protocol/SPEC.md) plus a conformance test suite. The server in `server/` is one implementation of it, not the definition.
- **Servers are untrusted with location.** Convoy positions are end-to-end encrypted, so people can join a convoy on a server they know nothing about.
- **Speed stays private.** Members of a convoy see each other's live speed; nothing else does. No leaderboards, no top-speed stats, no speeds stored on servers.

## Components

```
ANDROID APP (Kotlin)                                  BACKENDS (every URL swappable)
Phone UI (Compose) + Android Auto UI
 └─ NavigationService (foreground, type=location)
     ├─ Ferrostar core + MapLibre map ──── HTTPS ───▶ Tiles: PMTiles + style JSON
     │                                ──── HTTPS ───▶ Routing: Valhalla · Search: Photon
     ├─ Convoy client (E2EE) ◀──────────── WSS ─────▶ FoxNav server (Ktor): discovery,
     ├─ Safety data (limits, cameras) ──── HTTPS ───▶   convoy relay, reports, data packs
     └─ Voice pipeline
shared/ (Kotlin Multiplatform): protocol · convoy logic · crypto · safety-data matching
        -> used by the app, the server, and later the iOS app
```

| Directory   | What                                                                                                    | Built with                                                           |
| ----------- | ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `android/`  | The Android app                                                                                         | Kotlin, Jetpack Compose, Ferrostar, MapLibre Native, Car App Library |
| `shared/`   | Code shared by the app and the server: protocol models, convoy logic, crypto, safety-data matching      | Kotlin Multiplatform (JVM target now, iOS later)                     |
| `server/`   | Reference FoxNav server                                                                                 | Kotlin, Ktor                                                         |
| `protocol/` | The protocol spec and `.proto` definitions                                                              |                                                                      |
| `infra/`    | A complete backend stack in Docker Compose: Valhalla, Photon, map tiles, the server, and Caddy in front |                                                                      |
| `ios/`      | Later: SwiftUI + Ferrostar iOS + the shared module                                                      |                                                                      |

### The app

- **Turn-by-turn** runs in Ferrostar's foreground service (type `location`), so it keeps going with the screen off.
  - Because the user starts navigation from the UI, the app only needs _while-in-use_ location permission, not background location.
- **Convoys** live in an app-wide `ConvoyController`, which owns the session (the client in `shared/`).
  - It feeds the session our position from the same location provider navigation uses, so a simulated drive shows up in the convoy too.
  - It reads quick messages aloud through the navigation voice.
  - `ConvoyService`, a second foreground service (type `location`, again started from the UI), keeps the convoy going with the screen off. Its notification has a Leave action.
  - On the map, other cars glide between their once-a-second reports (`MotionTrack`) as tinted arrows. A timing tower and a convoy sheet sit on top.
  - The tower floats between the screen's top and bottom panels, which report where they are, so it fits any screen and font size. It can be dragged to either side, folded to its header and steps aside while searching; its place is remembered.
  - Invites are `foxnav://join#…` links and QR codes. The app opens these links and scans the codes itself (CameraX and ZXing), since camera apps may not open `foxnav://` links.
  - It reconnects at once when the phone switches networks or gets one back, out of a tunnel for instance, instead of waiting for a timeout.
  - Removing someone changes the join token and rotates the key, so they can neither read along nor come back with their old link.
  - Navigation and the convoy each have their own service for now. One FoxNav service that owns navigation, the convoy and voice comes with Android Auto, which needs exactly that; the phone UI and the car screen then become views of it.
- **The screens** are Ferrostar's navigation view with FoxNav components swapped in:
  - the maneuver card, drive panel (speed, limit, arrival) and road name;
  - the free-drive overlay with search and route preview.

  The colours and fonts come from the design tokens.
- **Arriving ends the trip**, also when parking up to 30 m short of the destination (Ferrostar alone needs a few metres). The route line clears and the panel says where you arrived.
- **Over the limit** (3 km/h of tolerance), the speedometer turns red and the kin-kon chime plays once. It re-arms only after you're back at the limit.
- **Free drive is a first-class mode.** Without a destination, the app still follows you on the map and shows:
  - the road you're on and its speed limit;
  - cameras and reports ahead;
  - your convoy.

  Only turn-by-turn needs a route. Without one, speed limits come from matching the last few GPS fixes to the road network (Valhalla's `trace_attributes`), and later from on-device data packs so it works offline.

- Two build flavours: `foss` (no Google Play Services, for F-Droid) and `play` (Play Store).
- Ferrostar is pre-1.0, so its version is pinned and upgraded deliberately. Problems get fixed upstream rather than in a fork.

### The server

The reference server runs on Kotlin and Ktor. Convoy rooms live in memory, anything persistent goes in SQLite, and it ships as one Docker image. Today it serves the discovery document, the map styles and the convoy relay. Reports and data packs follow the roadmap below.

### Map services

All of these are open-source and self-hosted in `infra/`:

- **Routing:** [Valhalla](https://github.com/valhalla/valhalla). It produces voice and banner instructions in Finnish, Swedish, Norwegian and Danish, along with speed limits along the route.
- **Search:** [Photon](https://github.com/komoot/photon).
- **Map tiles:** a [PMTiles](https://docs.protomaps.com/pmtiles/) file cut from the [Protomaps](https://protomaps.com) planet build. It's a single static file with no tile server needed.
- **Styles:** MapLibre styles in `infra/styles/`, served by the FoxNav server. The server fills in the address each client used, so one style works everywhere.

## Pluggable backends

There are three layers, and each can be swapped on its own:

1. **Map:** a style URL.
2. **Routing and search:** Valhalla and Photon URLs. Other routing engines can plug in through Ferrostar's route adapters.
3. **FoxNav server:** convoys, reports and data packs.

The user types one domain. The app reads `https://<domain>/.well-known/foxnav`, which lists the URLs of all three layers (see [the spec](../protocol/SPEC.md#1-discovery)). An advanced settings screen can override single endpoints.

Some parts don't carry over to arbitrary servers cleanly. The design works around them:

- **Push notifications.** The FCM and APNs credentials belong to whoever publishes the app, so third-party servers can't push to store builds.
  - Convoys don't depend on push: invites travel as links or QR codes, and a phone in a convoy is connected anyway.
  - Push comes later, through an official relay plus UnifiedPush for the `foss` build.
- **Crowd reports split up across servers.** So reports are their own source, defaulting to the official server.
- **App Links only open domains the app declares.** So invite links always use the official link domain, with the convoy's actual server named inside the URL fragment.
- **Servers on the local network need a permission (Android 17+).** A home server or a LAN address requires the _Nearby devices_ permission (`ACCESS_LOCAL_NETWORK`).
  - The app asks only when the configured server resolves to a private, link-local or `.local` address.
  - It explains why first, because Android's own prompt talks about "nearby devices".

## Convoys

Details are in the [protocol spec](../protocol/SPEC.md#2-realtime-draft); this is the reasoning.

- **A WebSocket relay, not WebRTC** ([ADR 0002](adr/0002-convoy-transport.md)). Location updates are tiny, a server has to exist anyway, and it naturally holds the state that late joiners need.
- **About one update per second.** Phone GPS produces about one fix per second, so sending more often gains nothing. Receiving phones predict each car's position from its speed and heading and animate it, blending in corrections over about a second.
  - A car fades after 10 s without updates and shows as lost after 60 s (tunnels, dead zones).
  - A state that doesn't change still goes out every 5 s, so a parked car doesn't look lost.
  - A 10-car convoy uses single-digit megabytes per hour per phone.
- **End-to-end encrypted** ([ADR 0003](adr/0003-convoy-encryption.md), [threat model](threat-model.md)). The key travels only inside the invite link's fragment, and the server only forwards encrypted blobs.
- **No accounts in v1.** A member is a display name and a car (make, model, colour, body style). Friends are contacts saved from past convoys.
- **What members see:**
  - a timing tower in road order, with the gap to each car and their live speed. While navigating, gaps run along the route; otherwise they're distances as the crow flies, ahead or behind by your heading;
  - the destination someone is navigating to (the owner's first), to navigate there too, and a regroup pin;
  - big-button quick messages (police, hazard, slow down, fuel, break, pull over, all good), read aloud;
  - no typing while moving;
  - later: leader and tail ("sweeper") roles, via points, and alerts when someone falls behind or leaves the route.
- **Testing with bots.** [`tools/convoy-sim`](../tools/convoy-sim/) puts bot cars in a convoy, driving one behind the other along a real route, so one developer can test a full convoy.
- **Convoy soundtrack (later).** Members can opt in to share what they're playing. Title and artist go out as an encrypted `STATE` payload, and others can listen along.
  - Every member plays the track from their own music service. Audio is never streamed between phones, which would need music licences.
  - Listen along opens the same track at the sharer's position. It's matched across services by ISRC where needed.
  - Where a service's SDK allows control (e.g. Spotify App Remote, Apple MusicKit), the app keeps playback in sync, correcting drift with the relay's server clock.
  - On Android, reading what other apps play needs notification access; on iOS, only Apple Music and Spotify can be read.

## Speed limits, cameras and reports

- **Speed limits** come from OpenStreetMap through Valhalla.
  - Where a road has no limit, the app falls back to the country's legal default and shows it as "inferred".
  - Seasonal winter limits (Finland, Sweden, Norway) need special handling. National road databases improve coverage later.
  - Never show a confident wrong number.
- **Speed cameras** arrive as regional **data packs**: the app downloads them and matches cameras on the phone.
  - This works offline, and no server learns your route.
  - Alerts fire only on the camera's road, in the direction it watches.
- **Legality differs by country.** Camera alerts are legal in the Nordics, but Germany forbids drivers from using them, Switzerland bans them, and France allows only vague "danger zones". Camera alerts therefore have a per-country switch.
- **Reports** (police, accidents, hazards, roadworks, slippery roads, moose…) are crowd-sourced Waze-style:
  - each type expires after its own time;
  - drivers passing by get a "still there?" prompt.

  National traffic feeds seed them so the map is useful before there are many users.

Data sources per country are in [data-sources.md](data-sources.md).

## Voice

- **v1:** Ferrostar's spoken instructions go through Android's built-in text-to-speech.
  - They play as navigation-guidance audio, which lowers other audio while they play, so music gets quieter and guidance goes to the car speakers.
- **Custom voices later:** guidance stays _structured_ (maneuver type, distance, exit, road name) all the way to a `VoiceRenderer` interface. Voice packs, recorded voices and on-device neural TTS then plug in without touching navigation.
- Community voice packs need consent: no cloned voices of real people without their permission.

## Android Auto and CarPlay

These are part of the design from the start, not a later port:

- **Android Auto** uses the Car App Library's navigation template. The app draws the map surface itself with MapLibre, so convoy cars and camera icons appear on the car screen too.
- **CarPlay** (iOS phase) needs Apple's navigation entitlement; Ferrostar has a CarPlay UI package.

On the car screen only the map is ours, so the app's look has to live in the map style and markers.

## Look and feel

"Tokyo night drive": a dark, high-tech car-navigation feel with Japanese accents. That means a kitsune (fox) mark, vermilion and electric cyan on near-black, digital instrument typography, and a day mode for sunlight. The design direction, mockups and design tokens live in [`design/`](../design/). Colours come from the tokens, one set per theme, so user-customizable palettes later are just more themes.

## Roadmap

1. **Foundations:** repository, build, local backend stack, docs, design exploration. _(done)_
2. **Navigation MVP:** _(done, except a tested real-world drive)_
   - map, search, routes;
   - free drive: everything except turn-by-turn works without a destination;
   - Ferrostar turn-by-turn with voice;
   - speed limits and speedometer;
   - choosing a server.
3. **Convoy MVP:** _(in progress: everything below works against the local stack, on the emulator with bots, including an hour-long soak test. Still missing: a drive with real phones)_
   - the relay, end-to-end encryption, invites and QR codes;
   - live cars with motion prediction and the timing tower;
   - quick messages, a shared destination and a regroup point;
   - the convoy simulator and conformance tests.
4. **Safety data:** camera packs and alerts, national speed-limit data including winter limits, crowd reports.
5. **Car integration and reach:** Android Auto, offline map regions, a web convoy viewer, Play Store and F-Droid releases, then iOS and CarPlay.
6. **Enthusiast features:**
   - custom voices, push-to-talk convoy radio and the convoy soundtrack;
   - twisty-roads routing;
   - a road library, meets and events, drive logs;
   - custom colour palettes.
