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
     ├─ Valhalla on the phone          ──── HTTPS ───▶ Routing tiles (for offline)
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
  - There's one navigation core for the app's life (`AppGraph.ferrostarCore`), not one per screen. A trip carries on in the service when its screen closes; reopened, the screen picks it up again. A second core would keep navigating and talking unseen, its voice doubling the new trip's. Android Auto will show the same core.
- **Convoys** live in an app-wide `ConvoyController`, which owns the session (the client in `shared/`).
  - It feeds the session our position from the same location provider navigation uses, so a simulated drive shows up in the convoy too.
  - It reads quick messages aloud through the navigation voice.
  - `ConvoyService`, a second foreground service (type `location`, again started from the UI), keeps the convoy going with the screen off. Its notification has a Leave action.
  - On the map, other cars glide between their once-a-second reports (`MotionTrack`) as tinted arrows. A timing tower and a convoy sheet sit on top.
  - The tower floats between the screen's top and bottom panels, which report where they are, so it fits any screen and font size. It can be dragged to either side, folded to its header and steps aside while searching; its place is remembered.
  - Invites are `foxnav://join#...` links and QR codes. The app opens these links and scans the codes itself (CameraX and ZXing), since camera apps may not open `foxnav://` links.
  - It reconnects at once when the phone switches networks or gets one back, out of a tunnel for instance, instead of waiting for a timeout.
  - Removing someone changes the join token and rotates the key, so they can neither read along nor come back with their old link.
  - Navigation and the convoy each have their own service for now. One FoxNav service that owns navigation, the convoy and voice comes with Android Auto, which needs exactly that; the phone UI and the car screen then become views of it.
- **The screens** are Ferrostar's navigation view with FoxNav components swapped in:
  - the maneuver card, drive panel (speed, limit, arrival) and road name;
  - the free-drive overlay with search and route preview.

  The colours and fonts come from the design tokens.

- **Leaving the route reroutes** from where the car is, heading the way it's going. Ferrostar fetches the new route; `RouteDeviationCheck` tells it when, using `OffRouteCheck` in `shared/`:
  - more than 50 m off the line, by a fix accurate to 25 m or better;
  - or driving against the route for four seconds and 40 m at 15 km/h or more. A distance check alone never sees a driver who has turned round, since they stay on the line. Where the route itself comes back along the same road, both ways count as right.

  Course and speed come from the fixes, or from their movement when a phone doesn't report them. The maneuver card says "Off route" until the new route is in. A route started from somewhere else reroutes on the first fix.

- **The route preview follows the car.** Its routes are worked out again after every 50 m moved, at most every 10 s, so the route starts where you are when you press Start. A preview opened before the first fix waits for it.
- **Arriving ends the trip**, also when parking up to 30 m short of the destination (Ferrostar alone needs a few metres). The route line clears and the panel says where you arrived.
- **Over the limit** (3 km/h of tolerance), the speedometer turns red and the over-limit sound (the kin-kon unless chosen otherwise) plays once. It re-arms only after you're back at the limit.
- **Free drive is a first-class mode.** Without a destination, the app still follows you on the map and shows:
  - the road you're on and its speed limit;
  - cameras and reports ahead;
  - your convoy.

  Only turn-by-turn needs a route. Without one, speed limits come from matching the last few GPS fixes to the road network (Valhalla's `trace_attributes`), which the phone's own Valhalla answers wherever it has road data, and the server elsewhere ([ADR 0005](adr/0005-offline-routing.md)).

- Two build flavours: `foss` (no Google Play Services, for F-Droid) and `play` (Play Store).
- Ferrostar is pre-1.0, so its version is pinned and upgraded deliberately. Problems get fixed upstream rather than in a fork.

### The server

The reference server runs on Kotlin and Ktor. Convoy rooms live in memory, anything persistent goes in SQLite, and it ships as one Docker image. It serves the discovery document, the map styles, the convoy relay and reports. Reports live in memory too, with drivers' ones saved to a JSON file so a restart keeps them. Data packs are static files it points to.

### Map services

All of these are open-source and self-hosted in `infra/`:

- **Routing:** [Valhalla](https://github.com/valhalla/valhalla). It produces voice and banner instructions in Finnish, Swedish, Norwegian and Danish, along with speed limits along the route.
- **Search:** [Photon](https://github.com/komoot/photon), and search packs built from its dump for searching offline ([spec §3.4](../protocol/SPEC.md#34-search-packs)).
- **Map tiles:** a [PMTiles](https://docs.protomaps.com/pmtiles/) file cut from the [Protomaps](https://protomaps.com) planet build. It's a single static file with no tile server needed.
- **Routing tiles:** Valhalla's graph as static, gzipped tiles under a build ID ([spec §3.3](../protocol/SPEC.md#33-routing-tiles)), for routing on the phone. `infra/datapacks/routing_tiles.py` publishes them after Valhalla builds its graph.
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
- **End-to-end encrypted and signed** ([ADR 0003](adr/0003-convoy-encryption.md), [ADR 0007](adr/0007-convoy-member-keys.md), [threat model](threat-model.md)). The convoy key travels only inside the invite link's fragment, and the server only forwards encrypted frames. Each member has their own keys and signs every frame, so nobody can send as someone else, and a new key after a removal goes to each remaining member separately.
- **No accounts in v1.** A member is a display name and a car (make, model, colour, body style). Friends are contacts saved from past convoys.
- **What members see:**
  - a timing tower in road order, with the gap to each car and their live speed. While navigating, gaps run along the route; otherwise they're distances as the crow flies, ahead or behind by your heading;
  - the destination someone is navigating to (the owner's first), to navigate there too, and a regroup pin;
  - big-button quick messages (police, hazard, slow down, fuel, break, pull over, all good), read aloud;
  - roles each driver takes on themselves, for this convoy only: leading the way (LEAD) or sweeping up at the back (TAIL), shown in the timing tower, the app's and the browser's;
  - what someone's music is playing, if they choose to share it, with "Play along": the song in your own music app, through its media session without leaving FoxNav where Android allows (and then song after song), otherwise by asking a music app to search for it. Sharing needs notification access, the only way Android shows apps what's playing; FoxNav reads no notifications with it;
  - no typing while moving;
  - later: via points, and alerts when someone falls behind or leaves the route.
- **Off-grid convoys (later).** Cars out of mobile coverage could keep the convoy going over other links, such as LoRa through [Meshtastic](https://meshtastic.org) radios. A car that still has internet would act as a gateway to the relay. Frames are the same on every link, signed and at most 233 bytes, so gateways forward them as they are and the link only carries them ([ADR 0007](adr/0007-convoy-member-keys.md)). What fits:
  - LoRa carries about 1 kbit/s in packets of about 230 bytes, and EU rules limit how often a radio may send. A signed position frame is about 120 bytes, about 1.3 s on air with Meshtastic's default preset, so off-grid positions go out every 30 s or so, with motion prediction filling the gaps. Quick messages and regroup pins are tiny and fit as they are.
  - Map tiles, search and routes are far too big for LoRa. Driving without internet needs offline map regions and on-device routing instead.
  - Short-range links between nearby cars (Wi-Fi Direct or Wi-Fi Aware, Bluetooth) carry much more, and could pass full updates from car to car.
- **Following from a browser.** Invite links point at the server's convoy page (`/join`, spec §2.5) when it has one, so they open anywhere: the page offers the app, or follows the convoy itself, with a map, the timing tower and optional location sharing while the page is open. Friends without the app, iPhones included, can drive along until the iOS app exists. It's plain JavaScript modules in [`web/`](../web/), with no build step and no dependencies besides MapLibre GL JS and PMTiles, which the server build fetches pinned and hash-checked; its encoding and encryption are tested against vectors from the app's own code. A browser member who doesn't share a position shows as "watching".
- **Offline maps.** Drivers download areas (the one on screen, up to street level, zoom 14) through MapLibre's offline packs: the style, fonts, icons and tiles go into the app's database, and the map shows them without signal, stretching street-level tiles when zoomed in further. A city district is a few megabytes, plus about 6 MB of fonts with the first area. The app also starts without a connection, from the server's last discovery document, and keeps the last 200 MB of map it showed. Speed cameras work offline from their data pack. The local stack serves tiles as plain `{z}/{x}/{y}` URLs (go-pmtiles in front of the archive) because the offline downloader needs them.
  - An area is one pack per theme. The themes differ only in style and icons; the tiles and fonts are shared, also between areas.
  - **Update** deletes the area's packs, clears the map cache and downloads them again under the same ID, so the area keeps its road data. It doesn't use MapLibre's invalidate: that marks everything the packs hold, the shared style, fonts and icons too, as needing a check with the server, so offline the other theme's map went blank. At start, the app checks MapLibre's database for such marks, left by earlier versions, and if it finds any, downloads every area again once there's a connection (and Wi-Fi, with Wi-Fi only on).
- **Road data** ([ADR 0005](adr/0005-offline-routing.md)). Routes, guidance and speed limits work without a connection too, from Valhalla on the phone (valhalla-mobile) over the server's own graph tiles. Map areas bring the road data under them (a switch, on by default), the route preview offers to make a trip available offline (the tiles within 2 km of the route, kept 30 days), and Settings → Road data downloads all of the server's regions, their main roads (which join up other road data: a route's ends need local roads), or the area on screen.
  - `RoadData` keeps one build's tiles and fetches what's missing whenever there's a connection. A new build downloads next to the old one and takes over once complete.
  - Downloads, road data and map areas alike, carry on with the app in the background or closed, and after a restart. A WorkManager job (`OfflineDownloadWorker`) keeps the app's process alive while there's something to download: about ten minutes a run, then another until all is in. Started with no screen open, it gives road data its server from the last discovery document. No foreground service is needed, since every tile already downloaded stays.
  - **Download on Wi-Fi only** (a setting, off by default): off Wi-Fi, road data and map areas wait, saying so, and carry on once on Wi-Fi. The small manifests still come over any connection.
  - Routes ask the server first and fall back to the phone. Road lookups ask the phone first wherever it has the local roads: they come every few seconds, and the answer is the same.
  - Dense cities are heavy: central Helsinki is 72 MB on the phone (25 MB to download), the Rovaniemi–Helsinki trip 199 MB (75 MB).
  - Search comes with road data ([ADR 0006](adr/0006-offline-search.md)): the server's search pack, in the same 0.25° cells as the local-road tiles, goes into an SQLite full-text index on the phone. Search asks Photon first and the phone when there's no connection. All of Finland is 18 MB. Offline results are only places the phone can route to: its Valhalla checks the candidates from where the car is, in one matrix request. A second pass forgives a typo or two when the exact search finds too little, and areas and dropped pins get names from the index too.
  - Reports and convoys still need the server.
- **Testing with bots.** [`tools/convoy-sim`](../tools/convoy-sim/) puts bot cars in a convoy, driving one behind the other along a real route, so one developer can test a full convoy.
- **Convoy soundtrack (later).** Members can opt in to share what they're playing. Title and artist go out in an encrypted `SONG` frame, and others can listen along.
  - Every member plays the track from their own music service. Audio is never streamed between phones, which would need music licences.
  - Listen along opens the same track at the sharer's position. It's matched across services by ISRC where needed.
  - Where a service's SDK allows control (e.g. Spotify App Remote, Apple MusicKit), the app keeps playback in sync, correcting drift with the relay's server clock.
  - On Android, reading what other apps play needs notification access; on iOS, only Apple Music and Spotify can be read.

## Speed limits, cameras and reports

- **Speed limits** come from OpenStreetMap through Valhalla: from the route while navigating, and from matching the last few positions to the road network (`trace_attributes`, every 5 s) otherwise.
  - **Winter limits** (Finland, Sweden, Norway) are `maxspeed:conditional` in OpenStreetMap, which Valhalla ignores: it reports road 7's 120 all year where winter says 100. A **speed limit pack** ([spec §3.2](../protocol/SPEC.md#32-speed-limits-for-part-of-the-year)) lists the ways with limits for part of the year, about 5,000 in Finland (26 kB compressed). The road lookup gives the current way's ID, and today's limit wins, with a snowflake on the sign.
  - While navigating, a winter limit replaces the route's limit only when the route reports the way's summer value there, so a lookup a few seconds behind can't put the motorway's winter limit on its exit ramp.
  - OpenStreetMap mostly says just "winter". The dates move every year and region, so the pack builder errs long (Finland: 15 October to 30 April): a winter limit shown early is a slower drive, a summer limit shown late is a fine.
  - **Finland's official limits** come from **Digiroad**, the national road and street database, which Fintraffic took over from Väylävirasto in 2026 (Digitraffic Road Network). They cover the roads without a limit in OpenStreetMap (about 40 % of main-road length, and most streets) and, being kept up to date by the road authorities, replace OpenStreetMap's where the two disagree, winter limits included. The pack builder matches Digiroad's segments to OpenStreetMap ways by position and direction. A way gets its most common limit; stretches of 60 m or more with another limit, like a village's 50 on a 10 km rural road, become **sections** with their own line, and the app uses a section's limit while the car is on it. About 108,000 ways are filled whole and 5,600 in sections (513 kB compressed, with the winter limits). Where both sources have a limit they agree on 90 % of road length; most differences are 30 vs 40 or 40 vs 50.
  - **Norway's official limits** come from **NVDB**, Statens vegvesen's road database (NLOD 2.0), through its open API, matched the same way: the limits on European, national, county and municipal roads, and the limits for part of the year or the day, which NVDB has its own dates and hours for: winter and summer limits, and school zones.
  - **Fintraffic's own format** replaces Digiroad's from spring 2027 (DATEX II, after the EU's RTTI regulation), with neither the data nor its model published yet. The matching is source-neutral (`infra/datapacks/official_limits.py`), with a reader per database (`digiroad.py`, `nvdb.py`), so the new format is one more reader; until it's written, Finland keeps Digiroad's last release (2/2026), and `fetch-data.sh` says when newer data is there.
  - **Which source wins** where both have a limit is the server operator's choice: the official one by default, or OpenStreetMap (`FOXNAV_SPEED_LIMITS_PREFER=osm` when building the packs), which routing uses for its times.
  - **Limits at set hours**, such as school zones on school days, hold by the phone's clock: OpenStreetMap's `30 @ (Mo-Fr 07:00-17:00)` and NVDB's school zones alike. Where two limits apply at once, a school zone in a winter limit, the lower wins.
  - **Not legal defaults.** "50 in built-up areas, 80 outside" sounds like a fallback, but on Finnish roads that have a limit mapped, town streets are 95 % 30 or 40 zones and rural roads only 40–60 % at 80. A default would usually be a confident wrong number. Countries without official data could still get them later, shown as a guess.
  - Not yet: Swedish national data (Trafikverket's NVDB, which needs an API key).
  - Never show a confident wrong number.
- **Speed cameras** arrive as regional **data packs** ([spec §3](../protocol/SPEC.md#3-data-packs-draft)): the app downloads them and matches cameras on the phone.
  - This works offline, and no server learns your route. The app keeps each pack and revalidates it with its ETag.
  - The local stack builds the packs from OpenStreetMap (`infra/datapacks/`). An enforcement relation's `from` and `to` give the direction a camera checks; otherwise its `direction` tag does. For Finland that's about 1,000 cameras, two thirds with a known direction.
  - Norway's come from NVDB: about 465 cameras, nearly all with the direction they check (from the road link each stands on), and about 85 **average speed sections** (_streknings-ATK_), which time cars between two cameras. Mapped cameras within 50 m of an official one are left out, so they don't warn twice.
  - Warnings come about 25 s before a camera (at least 300 m, at most 1.5 km), once per camera, as a pill above the bottom panel and by voice: "Speed camera in 400 metres, limit 80".
  - An average speed section is announced the same way as its start comes up ("Average speed check in 400 metres"). In it, the pill shows the driver's average so far, distance along the section over time since entering it, and the distance to its end, red while the average is over the limit. The average stays on the phone, like the speed.
  - Only cameras on our road count, and only those checking our direction. While navigating that means along the route; in free drive, within a narrow cone around our heading. Cameras without a known direction count in both. No warnings when parked.
  - Sweden's national camera data (Trafikverket) comes next, see [data-sources.md](data-sources.md).
- **Legality differs by country.** Camera warnings are legal in the Nordics, but Germany forbids drivers from using them, Switzerland bans them, and France allows only vague "danger zones". So:
  - the app stays quiet in those countries whatever the setting (`CameraRules`), knowing the country from the road lookup;
  - everywhere else there's a switch in the settings.
- **Reports** (police, speed checks, accidents, hazards, stopped vehicles, roadworks, slippery roads, animals, closed roads) are crowd-sourced Waze-style ([spec §4](../protocol/SPEC.md#4-reports-draft)):
  - one tap on a big button reports it where you are, for the direction you're heading; reports are public and anonymous;
  - each type expires after its own time, from 30 min for an animal to 24 h for roadworks;
  - drivers who pass a report get a "still there?" prompt. A yes keeps it longer (up to three lifetimes); once noes outnumber yeses, it goes;
  - reports ahead on our road show as a pill and are spoken ("Accident in 600 metres"), using the same matching as cameras. Roadworks are only shown, not spoken;
  - police and speed checks follow the camera rules: hidden in Germany, Switzerland and France;
  - the app polls the feed once a minute with its ETag. For the Nordics the whole feed is a few hundred reports, about 15 kB compressed. Pushing new reports over the realtime connection comes later.

  Fintraffic's traffic messages seed them in Finland (accidents, closed roads, animals, slippery roads, and roadworks while they restrict traffic), so the map is useful before there are many users. In Norway, Statens vegvesen's DATEX II feed does the same for a server with an account for it, adding broken-down vehicles and hazards such as rockfalls. Trafikverket's can follow.

Data sources per country are in [data-sources.md](data-sources.md).

## Voice

- **v1:** Ferrostar's spoken instructions go through Android's built-in text-to-speech.
  - They play as navigation-guidance audio, which lowers other audio while they play, so music gets quieter and guidance goes to the car speakers.
  - Drivers choose the guidance language (Finnish, Swedish, Norwegian, Danish, English, or the phone's), one of the phone's offline voices for it, and the speaking speed. Routes are requested with the language and units of that moment, so a change applies from the next route without restarting anything.
  - Camera and report warnings are spoken in full ("Speed camera in 400 metres, limit 80"), briefly ("Speed camera"), as a tone (two rising pips, `design/sounds/alert.py`), or not at all.
- **Voice packs** (`android/.../voice/`): guidance and warnings in a recorded voice. A pack is about 50 phrases, one WAV clip each: distances ("In 300 metres", "In half a mile"), manoeuvres ("turn left", "keep right", "enter the roundabout", "your destination is ahead"), and warnings ("speed camera", "police", "animal on the road"). The phrase rules are in `shared/.../voice/Phrases.kt`.
  - The voice between Ferrostar and the speaker (`GuidanceVoice`) builds each instruction from the step's structured manoeuvre and its trigger distance, rounded to the nearest recorded distance: "In 300 metres" + "turn left". Clips play joined, as one navigation-guidance sound that lowers music like the speech does.
  - Each instruction is matched to the route step it belongs to by its ID, waiting a moment for the core where a new route's first instruction comes before the core says it's on that route; the departure ("Drive east, then bear left onto...") becomes the first manoeuvre. Instructions are said in order, and the same phrases twice within a few seconds (a very short first step, arriving) are said once.
  - Whatever a pack can't say (a phrase not recorded yet, a manoeuvre without a phrase, convoy messages with their names) falls back to the phone's speech, sentence by sentence, so guidance never goes missing. Street names are left out with a pack. The log (`FoxNavVoice`) says why each fallback happened.
  - Drivers record their own in the app, phrase by phrase, or choose an audio file for a phrase, or add many files at once, matched to phrases by name (`turn_left.mp3`). Either way the clip has the silence before and after trimmed, so the joins sound natural, and its volume evened out with the rest. The app asks for the microphone only when recording, and uses it for nothing else.
  - Files in any format the phone plays (MP3, AAC, Ogg Vorbis and Opus, FLAC, WAV) are decoded with Android's own codecs (`AudioDecoder`), mixed down to mono and resampled to 22.05 kHz with a windowed-sinc filter (`Pcm`), so packs stay small and simple to join.
  - Packs travel as zip files, exported from and imported into the app: [voice-packs.md](voice-packs.md) has the format and every phrase's file name.
- **Over-limit sound:** the kin-kon, a single bell strike ("Ding"), three even beeps (`design/sounds/`), the driver's own sound from an audio file (its first 4 seconds, leading silence cut so it plays the moment it's due), or none. It plays on the navigation-guidance stream too.
- **Later:** community packs (with consent: no cloned voices of real people without their permission), a Japanese car-navi style pack, and on-device neural speech (e.g. Piper voices) behind the same voice.

## Android Auto and CarPlay

These are part of the design from the start, not a later port:

- **Android Auto** uses the Car App Library's navigation template. The app draws the map surface itself with MapLibre, so convoy cars and camera icons appear on the car screen too.
- **CarPlay** (iOS phase) needs Apple's navigation entitlement; Ferrostar has a CarPlay UI package.

On the car screen only the map is ours, so the app's look has to live in the map style and markers.

## Look and feel

"Tokyo night drive": a dark, high-tech car-navigation feel with Japanese accents. That means a kitsune (fox) mark, vermilion and electric cyan on near-black, digital instrument typography, and a day mode for sunlight. The design direction, mockups and design tokens live in [`design/`](../design/). Colours come from the tokens: five palettes, each with a night and a day set, which the app bundles and drivers choose from.

The screens follow the mockups:

- **Free drive:** search with voice search (the phone's speech recogniser, where it has one) and settings at the top. Under it, shortcuts to home, work and favourites with their travel time ("Home 14 min"), then what's ahead as solid amber chips ("Speed camera 400 m"). The search, before you type, offers the saved places and recent destinations. Round map buttons sit at the right edge: mute, and a compass that turns with the map and switches between heading-up and north-up. The bottom panel is docked to the screen's edge, with speed and limit, the road, and the Report and Convoy buttons.
- **Navigating:** the maneuver card, lanes and warning chips at the top; mute and the whole route at the right edge; the docked panel with speed, limit and arrival, and Report, Convoy and End.
- **The convoy sheet:** a full timing tower (place, colour, name, car, gap, speed, with lead, tail, owner and stopped badges, lost signal faded), what the leader (or someone) is playing, quick messages as a grid of icon buttons, and the invite, your role, music sharing and the other options behind the header's buttons.
- **Japanese accents:** small Japanese labels beside some English ones, like 到着 beside the arrival time and 左方向 beside a left turn's distance. Always beside the English, never instead of it; skipped by screen readers; off in settings. They await a native speaker's check (design/README.md).

Ferrostar draws the map, the route and the camera, and the maneuver card goes in its slot; FoxNav's own layer on top has the panels and map buttons, with Ferrostar's buttons shrunk away. The camera's padding keeps the car clear of the panels in both modes.

**Screen sizes** (`WindowLayout`): a phone held upright gets the mockups' layout. Anything wider, a phone on its side, a tablet, an unfolded foldable, gets a side column instead: search or the maneuver card at the top, the panel floating at the bottom, the map buttons at the right edge, and the car in the open map right of the column. Short or narrow windows (under 500 dp tall or 380 dp wide) use smaller speedometers, cards and buttons. A panel never runs off the screen or over the maneuver card: it takes the room there is and scrolls the rest. With large text, a Japanese accent or a button's label that doesn't fit is left out whole (the button keeps its icon and its name for screen readers) rather than cut off.

**Places:** home, work and favourites are saved from a route preview (the star by the place's name), recent destinations as you navigate to them; all on the phone only, and managed in settings. The shortcuts' travel times are routes from where you are, asked of the server while the shortcuts are on screen, every few minutes and after moving.

What drivers can change, on one settings page:

- **Driving:** km/h or mph (speeds, limits, distances, Valhalla's guidance and the spoken warnings all follow); how far over the limit before the warning (0, 3, 5 or 10); the over-limit sound (see Voice); a modern or a 7-segment speedometer (DSEG7, with the unlit segments showing, like an 80s digital dash).
- **Map:** day and night by sunrise and sunset where you are (a solar-elevation calculation, so midnight sun and kaamos work in Lapland), like the phone, or fixed; heading-up or north-up; 3D tilt while navigating; zoom buttons.
- **Look:** the colour theme (Tokyo night, True black, High contrast, Shibuya neon or Kyoto moss), the accent (the theme's own, a preset, or any colour) and the route colour (the theme's own or any), the text size and the driving displays' size, and the Japanese accents on or off.
  - Warnings keep amber and red in every theme. Picked colours are adjusted to read on the theme's backgrounds, night and day, and the route can't take amber or red: the picker says why.
  - The map follows the theme: the app keeps the server's styles on the phone (`MapStyles`, revalidated by ETag) and fills each layer's colour template with the palette (`StyleRecolour`, spec §1). Kept on the phone, the styles also let day and night switch without a connection.
  - The text size (90–130 %, on top of the phone's) scales the app's text and the map's labels. The driving display size scales the speedometer, limit sign, maneuver card, arrival time and road name; their numbers stay inside their boxes whatever the text size.
  - At night the route line glows like neon (a soft bloom, a glow and a bright core over the cyan line), as in the mockups; by day it's the plain line. Its corners are rounded off over 12 m either side (`RoutePath`), so bends drawn as a few straight pieces don't show as points, and your car follows the rounded line. The next maneuver's lanes show under the maneuver card where the map has them, the ones to take lit.
  - The map's credits are a small tag on the top edge of the bottom panel, as in the mockups, rather than MapLibre's logo and info button over the map. Its text is the map style's own attribution (any server's style credits its sources), and tapping it lists them with links.
- **Your car:** name, make, model, colour and body style. Your own car on the map is its top-down silhouette (coupé, hatchback, sedan, wagon, convertible, kei car, SUV, van, pickup) in your colour, with an underglow at night; the convoy's cars show their own shapes.
  - The car sits exactly where the camera points, so it never drifts from the screen centre. Navigating, Ferrostar slides the camera along the route line to each fix over a second; `ShownLocation` repeats that calculation for the car, and both update on the same frame. In free drive the app eases the location itself from fix to fix before Ferrostar sees it, so the map glides instead of stepping once a second.
- **Voice and warnings:** see Voice.

## Roadmap

1. **Foundations:** repository, build, local backend stack, docs, design exploration. _(done)_
2. **Navigation MVP:** _(done, except a tested real-world drive)_
   - map, search, routes;
   - free drive: everything except turn-by-turn works without a destination;
   - Ferrostar turn-by-turn with voice;
   - speed limits and speedometer;
   - choosing a server.
3. **Convoy MVP:** _(in progress: everything below works against the local stack, on the emulator and a phone with bots, including an hour-long soak test with ten bots. Still missing: a drive with real phones)_
   - the relay, end-to-end encryption, invites and QR codes;
   - live cars with motion prediction and the timing tower;
   - quick messages, a shared destination and a regroup point;
   - the convoy simulator and conformance tests.
4. **Safety data:** _(in progress: camera packs and warnings, winter and time-of-day speed limits, official limits for Finland and Norway, Norway's cameras and average speed sections, and reports seeded from Fintraffic's and Statens vegvesen's messages work. Still missing: Sweden's national data, and a reader for Fintraffic's new Road Network format once it's published)_
   - camera packs and warnings, from OpenStreetMap and national data;
   - national speed-limit data including winter limits;
   - crowd reports, seeded from national traffic feeds.
5. **Car integration and reach:** _(in progress: the web convoy page and offline map areas work; and so do routes, guidance, speed limits and search without a connection, [ADR 0005](adr/0005-offline-routing.md) and [0006](adr/0006-offline-search.md))_ Android Auto, offline map regions, a web convoy page, Play Store and F-Droid releases, then iOS and CarPlay.
6. **Enthusiast features:**
   - custom voices, push-to-talk convoy radio and the convoy soundtrack;
   - off-grid convoys over LoRa (Meshtastic) and car-to-car links, with gateway cars;
   - twisty-roads routing;
   - a road library, meets and events, drive logs;
   - custom colour palettes.
