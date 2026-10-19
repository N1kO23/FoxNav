# 0005: Routing and road lookups also run on the phone, on the server's own graph

**Status:** accepted, October 2026

## Context

Offline map areas show the map without signal, and speed cameras work offline from their data pack. Routes, turn-by-turn guidance and speed limits don't, for one reason: Valhalla runs only on the server. Speed limits depend on it too. The app finds the road under the car with Valhalla's `trace_attributes`, and the speed-limit pack (winter limits, Digiroad) is keyed by the OpenStreetMap way ID that returns. Off-grid convoys over LoRa (later) need routing without internet as well.

The options were:

- **Another engine on the phone**, such as GraphHopper. Its guidance, lanes and limits would differ from the server's, Ferrostar has no adapter for it, and FoxNav would keep two routing behaviours in step.
- **Valhalla fetching tiles itself as it needs them** (`tile_url`). That still needs a connection at the moment it fetches.
- **Shipping the whole graph.** Finland's is 841 MB.

## Decision

The app embeds Valhalla through [valhalla-mobile](https://github.com/Rallista/valhalla-mobile) (MIT, Valhalla 3.9). Servers publish their own graph as static tiles: a manifest plus gzipped tiles under a build ID ([spec §3.3](../../protocol/SPEC.md#33-routing-tiles)).

- **Same engine, same graph.** Routes and guidance are the server's, in the same languages, and so are the way IDs, so the speed-limit pack works unchanged. The app sends the phone's Valhalla the same JSON requests it sends the server's.
- **Routes ask the server first.** The phone's Valhalla takes over when there's no connection, when the server can't be reached, or when it fails (5xx). The server's own refusals, such as no route to a place, stand.
- **Road lookups ask the phone first,** wherever it has the local roads. They come every few seconds, and the phone gives the same answer in about 60 ms, without the network.
- **Which tiles the phone keeps.** All of them come from one build:
  - each offline map area brings the tiles under it, on every level;
  - a trip made available offline brings the tiles within 2 km of its route, kept for 30 days;
  - in settings, separately from map areas, drivers can download road data for all of a server's regions or for parts of them.
- **A new build replaces the whole set.** It downloads next to the old one and takes over once complete.

## What the trial showed

On the emulator, with the local stack's Finland graph, the phone's Valhalla answered exactly as the server's did. A route across Helsinki came back in 0.3 s, 5,015 m both ways, with Finnish guidance and the limits along it. Helsinki to Tampere took 0.7 s, 178,723 m both ways. Road lookups took 50–70 ms and returned the same ways and limits; on the Tampere motorway, the pack then gave the winter limit, 100. Two things shaped the design:

- **Road lookups need local-road tiles where the car is.** Valhalla finds the roads near a position through level 2's spatial index, even for motorways. Routes between areas work on levels 0 and 1 alone, but road lookups between them, and with them the pack's limits, don't. So a trip downloads level 2 along its whole route.
- **The connectivity check has to be off.** With only some tiles present, Valhalla's connectivity map, built from the tiles it has, calls places in different areas unconnected and makes startup take 2.9 s instead of 0.2 s. So `loki.use_connectivity` is off.

## Consequences

- **App size.** Valhalla adds about 7.5 MB of native code per ABI: arm64 grows from 18.5 MB to 26 MB. Per-ABI APKs and app bundles keep that to one ABI per phone.
- **Storage.** Road data outweighs map tiles in cities. Central Helsinki takes 72 MB on the phone (25 MB to download), the capital region 136 MB (48 MB), and Rovaniemi to Inari 21 MB (9 MB). All of Finland is 841 MB (332 MB).
- **Servers** publish one more set of static files, about 40 % of the graph's size, and keep the previous build for clients still downloading it.
- **Engine versions.** The tiles must be readable by the app's Valhalla, so only the same major version (3) is used. A server-side Valhalla upgrade that changes the tile format needs an app update first.
- **Release builds.** valhalla-mobile reads its config and errors with Moshi's reflection and ships no R8 rules for that, so `proguard-rules.pro` keeps those classes. Checked in October 2026: the device test (`RoadDataTest`) passed against a minified, obfuscated, non-debuggable build with release's R8 settings.
- **Pinning.** valhalla-mobile is young (0.6), so it's pinned and upgraded deliberately, like Ferrostar.
- **Search** stays online for now. An index on the phone is a separate decision.
