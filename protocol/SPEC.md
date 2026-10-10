# FoxNav protocol, v0 (draft)

This document is for anyone building a FoxNav client or server. A FoxNav backend is any server that implements some or all of:

- the **discovery document**, which tells the app where everything is;
- standard map services: MapLibre styles and tiles, Valhalla-compatible routing and Photon-compatible search;
- the **realtime endpoint** for convoys;
- **data packs** the app matches on the phone, like speed cameras;
- **reports**: police, accidents, roadworks and the like, from drivers and official sources.

**Status:** the reference server in `server/` implements all of it: discovery (§1), the convoy relay (§2), data packs (§3) and reports (§4). `shared/` has the matching clients. The realtime protocol is still a draft and will change before v1. Its message definitions are in [`proto/foxnav/convoy/v0/convoy.proto`](proto/foxnav/convoy/v0/convoy.proto).

## 1. Discovery

Users only ever type a server address. The app fetches the discovery document from it:

```
GET /.well-known/foxnav
Accept: application/json
```

If the user typed a bare domain (`maps.example.org`), clients use `https://`.

### Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `foxnav` | integer | yes | Format version of this document. This spec describes `1`. |
| `name` | string | yes | Server name shown in the app. |
| `privacy` | URL | no | Privacy policy of whoever runs the server. |
| `realtime` | URL | no | WebSocket endpoint (§2). Absent if the server has no realtime features. |
| `datapacks` | URL | no | Where the data packs are (§3). Absent if the server has none. |
| `reports` | URL | no | The reports API (§4). Absent if the server has none. |
| `styles` | object | no | MapLibre style URLs keyed by theme. Known themes: `night`, `day`. |
| `routing` | endpoint | no | Routing service, see below. |
| `search` | endpoint | no | Search service, see below. |
| `features` | array of strings | no | Optional features the server implements: `convoy`, `reports`, `datapacks`. |
| `regions` | array of strings | no | ISO 3166-1 alpha-2 codes of the countries the server has map data for. |

An *endpoint* is `{ "type": "<flavour>", "url": "<URL>" }`. Known flavours:

- routing `valhalla`: the [Valhalla](https://valhalla.github.io/valhalla/api/) HTTP API. Clients call `POST {url}/route` and request OSRM-format responses with banner and voice instructions, which is what the [Ferrostar](https://github.com/stadiamaps/ferrostar) navigation SDK consumes.
- search `photon`: the [Photon](https://github.com/komoot/photon) API, `GET {url}/api?q=…` and `GET {url}/reverse?lat=…&lon=…`.

### URLs

Any URL in the document may be relative. Clients resolve it against the URL the document was fetched from, as in [RFC 3986 §5.2](https://www.rfc-editor.org/rfc/rfc3986#section-5.2). A relative `realtime` URL then switches `http` to `ws` and `https` to `wss`. A server that puts everything behind one origin therefore needs no configuration of its public address.

Styles are [MapLibre style spec](https://maplibre.org/maplibre-style-spec/) documents. They may use any source type MapLibre supports, including `pmtiles://` URLs.

### Compatibility

- Clients ignore fields and features they don't know.
- Servers may add fields without changing `foxnav`. Changes that would break existing clients increase it.

### Example

What the local stack in `infra/` returns:

```json
{
  "foxnav": 1,
  "name": "FoxNav (local)",
  "realtime": "/v1/ws",
  "styles": { "day": "/styles/day.json", "night": "/styles/night.json" },
  "routing": { "type": "valhalla", "url": "/valhalla" },
  "search": { "type": "photon", "url": "/photon" },
  "datapacks": "/datapacks",
  "reports": "/v1/reports",
  "features": ["convoy", "reports", "datapacks"],
  "regions": ["FI"]
}
```

## 2. Realtime (draft)

### 2.1 Transport

- A WebSocket connection to the `realtime` URL, using the subprotocol `foxnav.v0`.
- Every frame is binary and holds exactly one protobuf message: `ClientMessage` from the client, `ServerMessage` from the server.
- The client opens with `Hello`. The server answers `Welcome` with its name and the most members a convoy may have.
- The client sends `Ping` every 15 s and the server answers `Pong`, so a working connection is never silent for long. Clients treat 45 s without any message as a dead connection; servers close connections that stay silent that long.
- After a dropped connection, clients reconnect with exponential backoff (1 s doubling up to 30 s, with ±20 % jitter) and send `Resume` with the token from `Joined`.
  - If the seat has expired meanwhile, the server answers `Error` `NOT_FOUND`, and the client joins again with the invite on the same connection.
  - After reconnecting, clients publish only their latest state, because old positions are worthless.

### 2.2 Convoys

A convoy is a room on one server.

1. **Create.** The client sends `CreateConvoy`.
   - The server answers `ConvoyCreated`, with a random 16-byte `convoy_id` and a `join_token`.
   - Then it sends `Joined`: the creator is the convoy's first member and its **owner**.
   - The client generates the convoy's encryption key locally (§2.3). The key never goes to the server.
2. **Invite.** The creator shares a link:

   ```
   https://<link domain>/join#v=0&s=<server URL>&c=<convoy_id>&t=<join_token>&k=<key>[&e=<key epoch>]
   ```

   - The parameters are form-encoded, with binary values in unpadded base64url.
   - `e` is the key's epoch (§2.3), left out while it is 0.
   - Everything sits in the URL fragment, which browsers never send to any server. So the operator of the link domain learns nothing, and the link works for convoys on any server.
   - The same string can be shown as a QR code.
   - Until FoxNav has a link domain, the app uses `foxnav://join#…` with the same fragment.
3. **Join.** The client connects to the server named in the invite and sends `JoinConvoy` with the convoy ID and join token. The server answers `Joined`, with:
   - the member ID it assigned, and a resume token;
   - the owner's member ID, and the current join token;
   - every other member, with their latest `STATE` payload (still encrypted) and how long ago the server received it. That lets a late joiner tell a quiet car from a live one;
   - the latest `KEY` payloads (§2.3), oldest first, for members who missed a key rotation.

   Other members get `MemberJoined`. A wrong token gets `Error` `FORBIDDEN`, an unknown convoy `NOT_FOUND`, and a full convoy `CONVOY_FULL`.
4. **Publish.** Members send `Publish` with an encrypted payload on one of three channels:
   - `STATE` for things where only the latest value matters: everything about the member, bundled (§2.4). The server keeps the latest `STATE` payload per member for people who join later.
   - `EVENT` for one-off messages (quick messages, regroup pins). The server relays them without keeping them.
   - `KEY` for key rotations (§2.3). Only the owner may publish on it; the server answers anyone else with `Error` `FORBIDDEN`. It keeps the latest few (8 in the reference server) for `Joined`.

   The server forwards each payload to all other members as `Relay`, stamped with the sender and the server's clock (`server_time_ms`).

   Rates:
   - Clients publish `STATE` at most once a second. They publish it again every 5 s while it stays the same, because the others take silence for a lost signal.
   - Servers may rate-limit. The reference server relays at most 5 `STATE` payloads per member in any 3 s, since a burst arrives after a network stall, and drops the rest silently.
   - It answers more than 10 `EVENT` and `KEY` payloads per member in 10 s with `Error` `RATE_LIMITED`.
5. **Leave or remove.**
   - `LeaveConvoy` removes yourself.
   - The owner can send `RemoveMember`. The server tells the removed member with `MemberLeft` (`removed` set) and stops relaying to and from them.
   - The others get `MemberLeft` either way.
   - A removal also changes the join token: the server sends the new one to everyone left in `InviteChanged`, and refuses the old one from then on. Otherwise the removed member could come straight back with their invite. The owner then rotates the key (§2.3).
   - When the owner leaves, ownership passes to whoever has been in the convoy longest, named in `MemberLeft.new_owner`.

A member whose connection drops keeps their seat for a few minutes (5 in the reference server) and can `Resume` it, without the others being told. After that, the server frees the seat and the others get `MemberLeft`.

Servers never store positions. Rooms live in memory and expire when they have been empty for a while (an hour in the reference server).

### 2.3 End-to-end encryption

- **Key:** 32 random bytes generated by the convoy creator, epoch 0. Members receive the current key and its epoch through the invite.
- **Cipher:** AES-256-GCM with a fresh random 12-byte nonce per message and a 16-byte tag.
- **Associated data:** `convoy_id ‖ sender member_id (8 bytes, big-endian) ‖ channel (1 byte)`. A server can't move a payload to another convoy, member or channel without detection.
- **Plaintext:** a serialized `ConvoyPayload` message.
- **Rotation:** once the server confirms a removal (`MemberLeft` with `removed` set), the owner generates a new key and sends it as a `KeyRotation` with the next epoch on the `KEY` channel, encrypted under the current key. The removed member no longer gets anything relayed, so they never see it.
  - Each `Sealed` payload names the epoch of its key. Members seal with the newest key they have and keep older ones, to open messages still on their way.
  - Members accept a rotation only from the current owner, and only to a newer epoch.
  - Someone away during a rotation catches up from `Joined.key_history` when they resume.
  - The invite changes with it: the new key, its epoch and the new join token.

See [`docs/threat-model.md`](../docs/threat-model.md) for what this protects against and what it doesn't.

### 2.4 Payloads

Defined in the `.proto` file.

- **`STATE`** carries a `MemberState`, everything the others need about a member in one message, because the server keeps only the latest one:
  - `Profile`: display name and car (make, model, colour, body style);
  - `Position`: location, speed, heading, accuracy and fix time. It is absent until the first GPS fix;
  - `RouteShare`: a shared destination and via points, with the destination last.
- **`EVENT`** carries a `QuickMessage` or a `RegroupPin`.
- **`KEY`** carries a `KeyRotation`.

Receivers ignore a `STATE` whose position is older than the one they already have (a replay).

### 2.5 Not yet specified

These arrive in a later version of this document:

- new reports pushed over this connection, instead of clients polling the feed (§4);
- more data packs (speed-limit fixes, average-speed sections);
- a `NowPlaying` payload for the convoy soundtrack: track metadata, service IDs and ISRC, playback position and state.

## 3. Data packs (draft)

Data the app downloads per region and matches on the phone, so the server never learns where anyone drives. A server that has them lists the `datapacks` feature and a `datapacks` URL in its discovery document. Packs are plain files: clients revalidate them with `If-None-Match`, and servers should send them compressed. A server needn't have every kind of pack; one it lacks answers `404`, and clients do without.

### 3.1 Speed cameras

```
GET {datapacks}/{region}/cameras.json
```

`region` is one of the document's `regions`. The pack:

```json
{
  "format": 1,
  "region": "FI",
  "generated": "2026-10-10T18:00:00Z",
  "attribution": "© OpenStreetMap contributors, ODbL",
  "cameras": [
    { "id": "osm/node/123", "lat": 60.17, "lon": 24.94, "kind": "speed", "bearings": [90], "maxspeed": 50 }
  ]
}
```

| Field | Meaning |
|---|---|
| `format` | `1`. A client that doesn't know a pack's format ignores the pack. |
| `id` | Stable across rebuilds, so clients don't warn twice about the same camera. |
| `kind` | `speed` or `red_light`. Clients skip kinds they don't know. |
| `bearings` | The travel directions the camera checks, in degrees clockwise from north. Absent means unknown, so any direction. |
| `maxspeed` | The limit it enforces in km/h, when known. |

The reference stack builds the pack from OpenStreetMap: `highway=speed_camera` nodes, and `type=enforcement` relations, whose `from` and `to` members give the direction. See `infra/datapacks/`. The app warns only where camera warnings are legal; see [`docs/architecture.md`](../docs/architecture.md#speed-limits-cameras-and-reports).

### 3.2 Speed limits for part of the year

```
GET {datapacks}/{region}/speedlimits.json
```

Limits that hold only on some dates, above all the Nordic winter limits. Routing engines report a road's plain limit all year (Valhalla ignores `maxspeed:conditional`), so clients correct it with this pack. Ways are OpenStreetMap ways, which Valhalla's `trace_attributes` returns as `edge.way_id`.

```json
{
  "format": 1,
  "region": "FI",
  "generated": "2026-10-10T18:00:00Z",
  "attribution": "© OpenStreetMap contributors, ODbL",
  "ways": [
    { "way": 2287183, "maxspeed": 120,
      "conditional": [{ "maxspeed": 100, "from": "10-15", "to": "04-30", "season": "winter" }] }
  ]
}
```

| Field | Meaning |
|---|---|
| `format` | `1`. A client that doesn't know a pack's format ignores the pack. |
| `way` | The OpenStreetMap way ID. |
| `maxspeed` | The way's plain limit in km/h, as the routing engine reports it. Clients apply the way's conditional limits only while the engine reports this value there: a different value means the engine has moved on to another road, or the map has changed since the pack was built. |
| `conditional` | Limits for parts of the year; the first that applies wins. `from` and `to` are the first and last day, `MM-DD` every year (a range may wrap round the new year) or `YYYY-MM-DD` once, in local time. `season`, when present, is `winter` or `summer`, for clients to show. |

OpenStreetMap often says only "winter", whose dates vary by year and region. The reference builder (`infra/datapacks/`) turns it into dates per region, erring towards a long winter: a winter limit shown a little early means a slower drive, a summer limit shown too long means a fine. Server operators can set the dates with `--season`. Conditions other than dates (times of day, weekdays, wet roads) are left out for now, as are limits for one direction of a way.

## 4. Reports (draft)

What drivers report on the road, and official traffic messages: police, speed checks, accidents, hazards, stopped vehicles, roadworks, slippery roads, animals and closed roads. A server that has them lists the `reports` feature and a `reports` URL in its discovery document.

Reports are public: everyone using the server sees them. They say nothing about who made them, and servers keep no record of who sent a report or a vote beyond what rate limits need.

### 4.1 The feed

```
GET {reports}
```

Every current report. Clients poll it about once a minute while driving, with `If-None-Match`. Servers send an `ETag` and answer `304 Not Modified` while nothing changed, and should compress the feed.

```json
{
  "format": 1,
  "reports": [
    { "id": "mTHYi9CkVdeQ", "type": "police", "lat": 60.17, "lon": 24.94, "bearing": 180,
      "reported": "2026-10-10T18:00:00Z", "expires": "2026-10-10T19:00:00Z", "confirmations": 2 },
    { "id": "fintraffic:GUID50472141", "type": "accident", "lat": 60.21, "lon": 24.80,
      "reported": "2026-10-10T19:30:31Z", "expires": "2026-10-10T20:00:31Z",
      "source": { "name": "Fintraffic", "licence": "CC BY 4.0", "url": "https://www.digitraffic.fi" },
      "text": "Tie 51, Espoo. Liikennetiedote." }
  ]
}
```

| Field | Meaning |
|---|---|
| `format` | `1`. A client that doesn't know a feed's format ignores the feed. |
| `id` | Unique, stable for the report's life. |
| `type` | `police`, `speed_check`, `accident`, `hazard`, `stopped_vehicle`, `roadworks`, `slippery_road`, `animal` or `road_closed`. Clients skip types they don't know. |
| `lat`, `lon` | Where it is. For a stretch of road, where the stretch begins. |
| `bearing` | The direction of travel it concerns, in degrees clockwise from north. Absent means both directions. It only matters for `police`, `speed_check`, `accident`, `hazard` and `stopped_vehicle`; the other types concern the whole road. |
| `reported`, `expires` | ISO 8601 times, UTC. |
| `confirmations` | How many drivers said it's still there. |
| `source` | Only for official reports: `name`, `licence` and `url` of where it comes from. Clients show this attribution with the report. |
| `text` | Only for official reports: the source's description, in the source's language. |

One feed for the whole server works for the Nordics: hundreds of reports, tens of kilobytes compressed. A later version may add area queries or push (§2.5).

### 4.2 Reporting

```
POST {reports}
Content-Type: application/json

{ "type": "police", "lat": 60.17, "lon": 24.94, "bearing": 180 }
```

`bearing` is the reporter's heading, and may be left out. The server answers `201 Created` with `{ "id": "…" }`, `400 Bad Request` for an unknown type or a position or bearing out of range, or `429 Too Many Requests`.

### 4.3 Still there?

```
POST {reports}/{id}/votes
Content-Type: application/json

{ "still_there": true }
```

Clients ask drivers passing a report whether it's still there, at most once per report. The server answers `204 No Content`, `404 Not Found` for a report that's gone, `400` or `429`.

### 4.4 Lifetimes

Each type lasts a set time unless someone confirms it. In the reference server:

| Type | Lifetime |
|---|---|
| `police`, `speed_check`, `hazard` | 60 min |
| `accident` | 90 min |
| `stopped_vehicle` | 45 min |
| `animal` | 30 min |
| `slippery_road` | 4 h |
| `road_closed` | 6 h |
| `roadworks` | 24 h |

A "yes" keeps a report for at least half its lifetime from the vote, but no report lasts more than three lifetimes in all. Once "no" votes outnumber "yes" votes, and there are at least two, a driver's report goes. Official reports last as long as their source says. Other servers may tune all of this.

The reference server limits each client address to 5 reports and 30 votes per 10 minutes, and keeps the addresses in memory only. With `FOXNAV_FINTRAFFIC=true` it adds Fintraffic's traffic messages for Finland ([Digitraffic](https://www.digitraffic.fi/en/road-traffic/), CC BY 4.0): accidents, closed roads, animals, slippery roads, and roadworks during phases that restrict traffic.

## 5. Conformance

[`conformance/`](conformance/) checks any server against this document: the discovery document, the map services and data packs it lists, the realtime protocol message by message (with opaque payloads), and the reports API. Each convoy check uses a new convoy.

```sh
./gradlew :protocol:conformance:installDist
protocol/conformance/build/install/foxnav-conformance/bin/foxnav-conformance maps.example.org
```

It prints each check as passed, failed (with what the server should have done) or skipped, and exits non-zero on failures. Checks that leave something on the server for others to see run only with `--write`: a test report on Null Island (0, 0), voted away straight after. The reference server runs it in its own tests (`ConformanceTest`), next to `ConvoyRelayTest` for §2 in detail and `ConvoySessionTest` for the client in `shared/` against the server.
