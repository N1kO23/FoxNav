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

| Field       | Type             | Required | Meaning                                                                      |
| ----------- | ---------------- | -------- | ---------------------------------------------------------------------------- |
| `foxnav`    | integer          | yes      | Format version of this document. This spec describes `1`.                    |
| `name`      | string           | yes      | Server name shown in the app.                                                |
| `privacy`   | URL              | no       | Privacy policy of whoever runs the server.                                   |
| `realtime`  | URL              | no       | WebSocket endpoint (§2). Absent if the server has no realtime features.      |
| `datapacks` | URL              | no       | Where the data packs are (§3). Absent if the server has none.                |
| `reports`   | URL              | no       | The reports API (§4). Absent if the server has none.                         |
| `invites`   | URL              | no       | The convoy page invite links point to (§2.5). Absent if the server has none. |
| `styles`    | object           | no       | MapLibre style URLs keyed by theme. Known themes: `night`, `day`.            |
| `routing`   | endpoint         | no       | Routing service, see below.                                                  |
| `search`    | endpoint         | no       | Search service, see below.                                                   |
| `features`  | array of strings | no       | Optional features the server implements: `convoy`, `reports`, `datapacks`.   |
| `regions`   | array of strings | no       | ISO 3166-1 alpha-2 codes of the countries the server has map data for.       |

An _endpoint_ is `{ "type": "<flavour>", "url": "<URL>" }`. Known flavours:

- routing `valhalla`: the [Valhalla](https://valhalla.github.io/valhalla/api/) HTTP API. Clients call `POST {url}/route` and request OSRM-format responses with banner and voice instructions, which is what the [Ferrostar](https://github.com/stadiamaps/ferrostar) navigation SDK consumes. It may also have `tiles`: where its graph's tiles are, for routing on the phone (§3.3).
- search `photon`: the [Photon](https://github.com/komoot/photon) API, `GET {url}/api?q=...` and `GET {url}/reverse?lat=...&lon=...`.

### URLs

Any URL in the document may be relative. Clients resolve it against the URL the document was fetched from, as in [RFC 3986 §5.2](https://www.rfc-editor.org/rfc/rfc3986#section-5.2). A relative `realtime` URL then switches `http` to `ws` and `https` to `wss`. A server that puts everything behind one origin therefore needs no configuration of its public address.

Styles are [MapLibre style spec](https://maplibre.org/maplibre-style-spec/) documents. They may use any source type MapLibre supports, including `pmtiles://` URLs. Apps download map areas for offline use through MapLibre's offline packs, which need sources with tile URLs (`{z}/{x}/{y}`) or TileJSON; with `pmtiles://` sources the map only works online. Tiles should carry cache headers (`Cache-Control`, `ETag`), since apps keep what they've shown for driving out of coverage. So should styles: apps keep them on the phone and revalidate them, and the reference server answers `If-None-Match` with `304`.

A style may let apps recolour it in the driver's palette. Each layer whose paint takes a palette colour then has a template of those paint properties in its metadata, with each such colour as `"@<token>"`, a colour token of FoxNav's palettes ([`design/tokens.json`](../design/tokens.json), `color.palette`):

```json
{
  "id": "water",
  "type": "fill",
  "paint": { "fill-color": "#11203F" },
  "metadata": { "foxnav:paint": { "fill-color": "@map-water" } }
}
```

Apps fill the templates with the palette's colours and use the result in place of the paint property; they leave styles without templates as they are. The reference server's styles have templates for the map's land, water, parks, buildings, roads, railways, boundaries and labels.

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
  "routing": {
    "type": "valhalla",
    "url": "/valhalla",
    "tiles": "/routing-tiles"
  },
  "search": { "type": "photon", "url": "/photon" },
  "datapacks": "/datapacks",
  "reports": "/v1/reports",
  "invites": "/join",
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

A convoy is a room on one server. Its members know each other by their keys (§2.3), not by anything the server assigns, so a convoy can also carry on over other links, such as LoRa radios, without the server ([ADR 0007](../docs/adr/0007-convoy-member-keys.md)).

1. **Create.** The client generates the convoy key and its own identity (§2.3), then sends `CreateConvoy` with its member ID and its signing public key.
   - The server answers `ConvoyCreated`, with a random 16-byte `convoy_id` and a `join_token`.
   - Then it sends `Joined`: the creator is the convoy's first member and its **owner**. The server keeps the owner's public key, to check the owner's signatures (step 5).
   - The convoy key never goes to the server.
2. **Invite.** The creator shares a link:

   ```
   https://<link domain>/join#v=0&s=<server URL>&c=<convoy_id>&t=<join_token>&k=<key>&o=<owner's key>[&e=<key epoch>][&h=<handovers>]
   ```

   - The parameters are form-encoded, with binary values in unpadded base64url.
   - `o` is the owner's signing public key (§2.3). Members accept removals, key rotations and handovers only from the owner it names, or from whoever that owner handed over to.
   - `e` is the key's epoch (§2.3), left out while it is 0.
   - `h` is how many handovers there have been (§2.3), left out while there have been none.
   - Everything sits in the URL fragment, which browsers never send to any server. So the operator of the link domain learns nothing, and the link works for convoys on any server.
   - The same string can be shown as a QR code.
   - Until FoxNav has a link domain, the app uses `foxnav://join#...` with the same fragment.

3. **Join.** The client generates its identity (§2.3), connects to the server named in the invite, and sends `JoinConvoy` with the convoy ID, the join token and its member ID. The server answers `Joined`, with:
   - a resume token and the current join token;
   - the members connected to the server, by member ID;
   - the frames it keeps (step 4), oldest first, each with how long ago the server received it. A late joiner gets everyone's card and latest position at once, and can tell a quiet car from a live one.

   Other members get `MemberJoined`. A wrong token gets `Error` `FORBIDDEN`, as does a removed member's ID, an unknown convoy `NOT_FOUND`, a full convoy `CONVOY_FULL`, and a member ID already in the convoy `CONFLICT`. After a `CONFLICT`, the client generates a new identity and tries again.

4. **Publish.** Members send `Publish` with a frame (§2.3). The server reads only the frame's header, and forwards the frame to all other members as `Relay`, with its clock (`server_time_ms`) and the member who published it (`from`). That's usually the frame's sender. A gateway, though, publishes frames it heard over a radio, from members who have no connection themselves.

   What the server keeps, for those who join later:
   - the latest `CARD`, `POSITION`, `ROUTE` and `SONG` frame of each sender, from each member who published one, for as long as that member is in the convoy;
   - the latest 64 `OWNER` frames. It takes those only with the owner of the time as their sender, and answers others with `Error` `FORBIDDEN`;
   - no `EVENT` frames, and no frames of kinds it doesn't know: those it relays without keeping.

   It answers frames longer than 233 bytes with `Error` `BAD_REQUEST`, and drops frames from removed senders.

   Rates:
   - Clients publish `POSITION` at most once a second. They publish it again every 5 s while it stays the same, because the others take silence for a lost signal; members without a position send their card that often instead.
   - Cards, routes and songs go out when they change, and all of them again on (re)joining and after a key rotation, so that what the server keeps is under the current key. The card goes out on joining even before the member has a profile: the others need its keys.
   - Servers may rate-limit. The reference server relays at most 5 `POSITION` frames per member and sender in any 3 s, since a burst arrives after a network stall, and drops the rest silently.
   - It answers more than 10 frames of other kinds per member and sender in 10 s with `Error` `RATE_LIMITED`.

5. **Leave or remove.**
   - `LeaveConvoy` removes yourself. An owner hands over first (§2.3): the app hands over to whoever has been in the convoy longest, unless the owner picks someone.
   - The owner removes a member with `RemoveMember`: the member ID, the key epoch the removal starts, and the owner's signature (§2.3). The server checks the signature against the owner's key and answers anything else with `Error` `FORBIDDEN`.
   - The server tells the removed member with `MemberLeft` (`removed` set), stops relaying to them, and from then on drops frames with them as the sender. The others get `MemberLeft` either way.
   - A removal also changes the join token: the server sends the new one to everyone left in `InviteChanged`, and refuses the old one from then on. Otherwise the removed member could come straight back with their invite. The owner then rotates the key (§2.3).
   - Handing over, the owner also sends the server `HandOver`, signed (§2.3), and the server takes the new owner's key from then on.

A member whose connection drops keeps their seat for a few minutes (5 in the reference server) and can `Resume` it, without the others being told. After that, the server frees the seat and the others get `MemberLeft`.

Servers can't read frames, and keep them only in memory. Rooms expire when they have been empty for a while (an hour in the reference server).

### 2.3 End-to-end encryption

Everything members send each other is a **frame**: signed by its sender, then encrypted with the convoy key. The format is the same over every link, so a gateway forwards frames between a radio and the server as they are. A gateway or a server can drop or delay frames, but not read or change them.

#### Identities

- **Keys.** On creating or joining a convoy, a member's client generates an Ed25519 key pair for signing and an X25519 key pair for receiving keys. A member _is_ their signing key.
- **Member ID.** The first 4 bytes of the SHA-256 of the signing public key, as an unsigned big-endian number. It's a hint for finding the key: receivers check frames against keys, not IDs.
- **One convoy only.** An identity is used in one convoy, and lives exactly as long as its frame counter. A client that loses its counter, say in a restart, generates a new identity and joins again.
- **Same ID.** A client that hears another card with its own member ID generates a new identity. With 30 members, that happens in about one convoy in ten million.
- **Cards.** Members announce their public keys and profile in a `CARD` frame (§2.4), on joining and whenever the profile changes. Receivers take a card only if its signing key's member ID is the frame's sender and the frame is signed by that key.

#### Keys

- **Convoy key:** 32 random bytes generated by the convoy's creator, epoch 0. Members get the current key and its epoch through the invite.
- From each convoy key, with HKDF-SHA-256 and the convoy ID as salt:
  - the **frame key**, 32 bytes, with the info `foxnav v0 frame`;
  - the **convoy tag**, 4 bytes, with the info `foxnav v0 tag`. It tells receivers which convoy and which key a frame is for, without trying every key.

#### Frames

A frame is at most 233 bytes, so that it fits one Meshtastic packet. Numbers are big-endian.

| Bytes | Field                                                     |
| ----- | --------------------------------------------------------- |
| 1     | kind                                                      |
| 4     | convoy tag                                                |
| 4     | sender's member ID                                        |
| 4     | counter                                                   |
| rest  | encrypted content and signature, then the 16-byte GCM tag |

- **Header.** The first 13 bytes. Servers and gateways read it; they can't read the rest.
- **Counter.** It starts at 1 with a new identity and goes up by one with every frame the member sends, whatever its kind.
- **Content.** The protobuf message for the frame's kind (§2.4), at most 140 bytes.
- **Signature.** Ed25519, by the sender's signing key, over `"foxnav v0 frame" ‖ convoy_id ‖ header ‖ content`.
- **Encryption.** AES-256-GCM under the frame key, with a 16-byte tag:
  - nonce: sender's member ID (4 bytes) ‖ counter (4 bytes) ‖ 4 zero bytes. A counter never repeats within an identity, so neither does a nonce;
  - associated data: `convoy_id ‖ header`;
  - plaintext: `content ‖ signature`.

| Kind       | Value | Content                     | Server keeps (§2.2) |
| ---------- | ----- | --------------------------- | ------------------- |
| `CARD`     | 1     | `Card`                      | the latest          |
| `POSITION` | 2     | `Position`                  | the latest          |
| `ROUTE`    | 3     | `RouteShare`                | the latest          |
| `SONG`     | 4     | `NowPlaying`                | the latest          |
| `EVENT`    | 5     | `Event`                     | nothing             |
| `OWNER`    | 6     | `KeyRotation` or `Handover` | the latest 64       |

Receiving a frame:

1. Find the key whose convoy tag the frame carries. None: it's for another convoy, or for a key the receiver doesn't have yet (see rotation below).
2. Drop it if the sender has been removed, or if its counter was already accepted from that sender. Receivers keep each sender's highest counter, and which of the 64 below it they accepted; anything older than that is dropped too.
3. Decrypt it. If that fails, drop it.
4. Check the signature against the sender's card. A card's own frame is checked against the key in it. With no card yet, the receiver holds the frame for up to 30 s for the card to arrive. If several cards share the sender's ID, the frame belongs to the one whose key its signature matches.
5. Only then record the counter as accepted, so frames that fail can't use up a sender's counters.

A frame that arrives twice, say over a radio and through the server, counts once.

#### The owner

- **Who.** The invite names the owner by their signing key. The owner's statements go out as `OWNER` frames, signed like any frame: key rotations and handovers. Members accept them only from the current owner, checked against that key, so they need no card for it.
- **Handover.** A `Handover` names the new owner's signing key and the handover's number: 1 for the first. Members accept only the next number. Invites from then on name the new owner.
  - The server learns of it through `HandOver`, with the owner's signature over `"foxnav v0 handover" ‖ convoy_id ‖ number (4 bytes) ‖ the new owner's key`.
  - An owner who disappears without handing over leaves the convoy without one: nobody can remove members or rotate the key.
- **Removal.** `RemoveMember` carries the owner's signature over `"foxnav v0 remove" ‖ convoy_id ‖ member ID (4 bytes) ‖ epoch (4 bytes)`, where the epoch is the one the owner's rotation starts.

#### Rotation

After removing someone, the owner rotates the key:

1. It generates a new convoy key, with the next epoch.
2. It wraps the new key for each remaining member separately, with AES-KW ([RFC 3394](https://www.rfc-editor.org/rfc/rfc3394)), 40 bytes per member. The wrapping key comes from HKDF-SHA-256 over the X25519 shared secret of the owner's and the member's X25519 keys:
   - salt: the convoy ID;
   - info: `foxnav v0 wrap` ‖ epoch (4 bytes) ‖ member ID (4 bytes).

   The new key never travels under the old one, so a removed member who still hears every frame, over a radio, say, can't read on.

3. It sends a `KeyRotation` in `OWNER` frames, under the current key:
   - the new epoch and the removed members;
   - the wrapped keys, as many as fit (two with a member removed);
   - how many frames the rotation takes, and which one this is.

Members accept a rotation only from the owner, and only to a newer epoch. Each unwraps its own key (AES-KW detects a wrong one).

- **Old keys.** Members seal with the newest key from then on. They keep older ones for 2 minutes to open frames still on their way, but never from removed members.
- **Catching up.** Someone away during a rotation catches up from the `OWNER` frames in `Joined` when they resume.
  - When the owner hears a frame under an older key from someone not removed, it wraps the current key for them in an `OWNER` frame of its own, sealed under the older key they still have, once per member and key. That covers members whose card the owner didn't have yet when it rotated, and those out of reach of the rotation.
  - Frames under a key replaced more than 2 minutes ago are checked but not taken in.
- **The invite.** It changes with a rotation: the new key, its epoch and the new join token.

See [`docs/threat-model.md`](../docs/threat-model.md) for what this protects against and what it doesn't.

### 2.4 Payloads

Defined in the `.proto` file. Each is the content of one frame and fits in 140 bytes; clients shorten texts to fit.

- **`CARD`** carries a `Card`: the member's signing key and X25519 key (32 bytes each), and their `Profile`:
  - display name and car (make, model, colour, body style);
  - `watching`, for a member who follows without sharing a position (say, from a browser). Clients show such a member as watching rather than joining;
  - `role`, a part the member takes on themselves: `ROLE_LEAD` leads the way, `ROLE_TAIL` sweeps up at the back. Nobody assigns roles, and more than one member may take the same one.
- **`POSITION`** carries a `Position`: latitude and longitude (degrees × 10^7, as `sfixed32`), speed (cm/s), heading (degrees), accuracy (m) and fix time (Unix epoch milliseconds), about 25 bytes. Members send none until their first GPS fix.
- **`ROUTE`** carries a `RouteShare`: a shared destination and via points, with the destination last. It takes as many as fit, with their names shortened if need be. An empty one ends the share.
- **`SONG`** carries a `NowPlaying`: the song the member's music is playing (title, artist, the music app's name), only if they choose to share it, so the others can play it too. An empty one ends the share.
- **`EVENT`** carries an `Event`: when it was sent (Unix epoch milliseconds), and a `QuickMessage` or a `RegroupPin`. Receivers drop events sent more than 5 minutes before their own clock.
- **`OWNER`** carries a `KeyRotation` or a `Handover` (§2.3).

Receivers ignore a position older than the one they already have from that member, and show an old one with its age.

### 2.5 Convoy page

A server may serve a web page at its discovery document's `invites` URL that takes the same fragment as the app's invite links (§2.2): `https://maps.example.org/join#v=0&s=...&c=...&t=...&k=...`. Apps then share that link instead of `foxnav://join#...`, because every chat app and camera opens it. The page:

- offers to open the convoy in the app (the `foxnav://` link with the same fragment);
- or joins the convoy itself, over the same realtime protocol and with the same end-to-end encryption (WebCrypto's AES-GCM, AES-KW, HKDF, Ed25519 and X25519, which current browsers all have), and shows everyone on a map with a timing tower;
- shares the browser's position if the person chooses to, while the page is open; otherwise its `Profile` says `watching`;
- sends quick messages, and a regroup point where the browser is when it shares its position, as `EVENT` frames like the app's (§2.4). Clearing a regroup point clears it on that page only, as in the app: there's no frame that takes one back.

The fragment never reaches the server. The page holds the convoy's key, so servers should send it with a strict content security policy: scripts only from the server itself, no inline scripts, and nothing from other members inserted as HTML. The reference implementation is [`web/`](../web/): plain JavaScript modules with no build step, served by the reference server at `/join`.

The page fetches the discovery document and styles of the invite's server, which may be a different origin than the page's: servers send `Access-Control-Allow-Origin: *` on both, and on everything the styles load (tiles, fonts, icons). Once: browsers refuse a response that has the header twice. Sharing a position needs a secure context (https, or `localhost`).

### 2.6 Not yet specified

These arrive in a later version of this document:

- convoys over LoRa radios (Meshtastic): the radio link, how often each kind of frame goes out over it, and what gateways forward;
- new reports pushed over this connection, instead of clients polling the feed (§4);
- more data packs (speed-limit fixes, average-speed sections);
- more for the convoy soundtrack in `NowPlaying` (§2.4): service IDs and ISRC to find the exact recording, and the playback position and state to play in step.

## 3. Data packs (draft)

Data the app downloads per region and matches on the phone, so the server never learns where anyone drives. A server that has them lists the `datapacks` feature and a `datapacks` URL in its discovery document. Routing tiles (§3.3) come from the routing endpoint instead; search packs (§3.4) are data packs, too. Packs are plain files: clients revalidate them with `If-None-Match`, and servers should send them compressed. A server needn't have every kind of pack; one it lacks answers `404`, and clients do without.

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
    {
      "id": "osm/node/123",
      "lat": 60.17,
      "lon": 24.94,
      "kind": "speed",
      "bearings": [90],
      "maxspeed": 50
    },
    {
      "id": "nvdb/823/373723082",
      "lat": 59.049104,
      "lon": 10.95147,
      "kind": "average_speed",
      "bearings": [162],
      "line": [
        [10.95147, 59.0491],
        [10.95282, 59.04698],
        [10.95602, 59.04524],
        [10.96808, 59.04212],
        [10.97379, 59.03802]
      ]
    }
  ]
}
```

| Field        | Meaning                                                                                                                                                                     |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `format`     | `1`. A client that doesn't know a pack's format ignores the pack.                                                                                                           |
| `id`         | Stable across rebuilds, so clients don't warn twice about the same camera.                                                                                                  |
| `kind`       | `speed`, `red_light` or `average_speed`. Clients skip kinds they don't know.                                                                                                |
| `lat`, `lon` | Where the camera is; for `average_speed`, where the section begins.                                                                                                         |
| `bearings`   | The travel directions the camera checks, in degrees clockwise from north; for `average_speed`, at the section's start. Absent means unknown, so any direction.              |
| `maxspeed`   | The limit it enforces in km/h, when known.                                                                                                                                  |
| `line`       | Only for `average_speed`: the section whose average speed is measured, from the first camera to the last, as `[lon, lat]` pairs (GeoJSON order) in the direction it checks. |

**Average speed sections** measure the time a car takes between two cameras, so they need a different warning from a point camera: clients warn as the section's start comes up, and while the car is in it, can show its average so far, the distance travelled along `line` over the time since entering, for the driver alone. Clients that don't know the kind skip such entries.

The reference stack builds the pack from OpenStreetMap: `highway=speed_camera` nodes, and `type=enforcement` relations, whose `from` and `to` members give the direction. For Norway it adds the official cameras and average speed sections from NVDB (Statens vegvesen, NLOD 2.0), with the direction each checks, and leaves out mapped cameras within 50 m of an official one. See `infra/datapacks/`. The app warns only where camera warnings are legal; see [`docs/architecture.md`](../docs/architecture.md#speed-limits-cameras-and-reports).

### 3.2 Speed limits

```
GET {datapacks}/{region}/speedlimits.json
```

What the routing engine's map gets wrong or doesn't know about speed limits, keyed by OpenStreetMap way (Valhalla's `trace_attributes` returns it as `edge.way_id`):

- limits that hold only on some dates, above all the Nordic winter limits: routing engines report a road's plain limit all year (Valhalla ignores `maxspeed:conditional`);
- limits for ways the map has none for, from official data, including where they change along a way;
- optionally, official limits where the map's differ.

```json
{
  "format": 1,
  "region": "FI",
  "generated": "2026-10-10T18:00:00Z",
  "attribution": "© OpenStreetMap contributors, ODbL; speed limits from Digiroad (Digitraffic Road Network) © Fintraffic, CC BY 4.0, matched to OpenStreetMap",
  "ways": [
    {
      "way": 2287183,
      "maxspeed": 120,
      "conditional": [
        { "maxspeed": 100, "from": "10-15", "to": "04-30", "season": "winter" }
      ]
    },
    {
      "way": 59591033,
      "limit": 80,
      "sections": [
        {
          "maxspeed": 50,
          "line": [
            [25.12345, 62.12345],
            [25.12401, 62.12298],
            [25.12498, 62.12233]
          ]
        }
      ]
    },
    {
      "way": 95334658,
      "maxspeed": 40,
      "conditional": [
        {
          "maxspeed": 30,
          "from": "01-01",
          "to": "12-31",
          "days": ["Mo", "Tu", "We", "Th", "Fr"],
          "times": [["07:00", "17:00"]]
        }
      ]
    }
  ],
  "fill": { "40": [4234567, 12, 3051], "80": [2287001] }
}
```

| Field         | Meaning                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `format`      | `1`. A client that doesn't know a pack's format ignores the pack.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `way`         | The OpenStreetMap way ID.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `maxspeed`    | What the routing engine reports for the way, in km/h; absent where it reports none. Clients use the way's entry only while the engine reports this (or nothing): a different value means the engine has moved on to another road, say an exit ramp while the last road lookup still says motorway, or the map has changed since the pack was built.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `limit`       | The way's limit in km/h instead of the engine's, or where the engine has none.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `sections`    | Stretches of the way with another limit, such as a village's 50 on a long rural road: `maxspeed` and `line`, the stretch as `[lon, lat]` pairs (GeoJSON order). A client on a stretch (within about 25 m of it, and between its ends) uses its limit.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `conditional` | Limits for parts of the year, the week or the day; the first that applies wins, except on a section, so builders list the lower limits first: a school zone's 30 in a winter limit's 60. `from` and `to` are the first and last day, `MM-DD` every year (a range may wrap round the new year) or `YYYY-MM-DD` once, in local time. `season`, when present, is `winter` or `summer`, for clients to show. `days`, when present, are the weekdays it holds on, `Mo` to `Su`. `times`, when present, are the hours it holds, `[["07:00", "17:00"]]`, local time, from the first minute until the second (`24:00` is midnight); hours that end before they start run past midnight, and that part belongs to the day they started on. A rule with days or times a client can't read doesn't apply. Where `limit` replaces the engine's limit, a conditional limit counts only below it. |
| `fill`        | Limits for whole ways the routing engine has none for: km/h → way IDs. Each list is sorted, and every ID after the first is given as the difference from the one before (`[4234567, 12, 3051]` is ways 4234567, 4234579 and 4237630), which keeps a few hundred thousand ways small. Clients use these only where the engine reports no limit.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `attribution` | Credits for the pack's sources, which clients show.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |

OpenStreetMap often says only "winter", whose dates vary by year and region. The reference builder (`infra/datapacks/`) turns it into dates per region, erring towards a long winter: a winter limit shown a little early means a slower drive, a summer limit shown too long means a fine. Server operators can set the dates with `--season`. It also reads weekdays and hours, as in `30 @ (Mo-Fr 07:00-17:00)`, and holds such limits on public and school holidays too, which errs towards the lower limit. Other conditions (wet roads, vehicles) are left out for now, as are limits for one direction of a way.

For Finland, the reference builder takes the official limits from [Digiroad](https://www.fintraffic.fi/fi/digitrafficroadnetwork) (Fintraffic's Digitraffic Road Network since 2026, CC BY 4.0), which has a limit for nearly every public road and street. It matches Digiroad's segments to OpenStreetMap ways by position and direction: a way gets its most common limit, and stretches of 60 m or more with another one become sections. Where both sources have a limit, they agree on about 90 % of road length. By default Digiroad wins where they differ, being kept up to date by the road authorities, with `limit` and `sections` on those ways, and its winter limits replace OpenStreetMap's; `--prefer osm` keeps OpenStreetMap's and uses Digiroad only where OpenStreetMap has nothing. Legal default limits ("50 in built-up areas, 80 outside") are deliberately not used: in Finland most streets are in 30 or 40 zones, so the default would usually be wrong.

For Norway, it takes them from [NVDB](https://nvdbapiles.atlas.vegvesen.no), Statens vegvesen's road database (NLOD 2.0), matched the same way: the limits on European, national, county and municipal roads, and limits for part of the year or the day, which become conditional ones: winter and summer limits with their own dates, and school zones on weekdays at set hours. Environmental limits without dates are switched on as the air needs, so they're left out.

The matching is source-neutral (`infra/datapacks/official_limits.py`), with a reader per national database: Fintraffic's own Road Network format, which replaces Digiroad's from 2027, gets one when Fintraffic publishes it.

### 3.3 Routing tiles

```
GET {tiles}/manifest.json
GET {tiles}/{build}/{tile path}.gz
```

`{tiles}` is the routing endpoint's `tiles` URL (§1). These are the server's own routing graph, cut into tiles, for clients that route and look up roads on the phone when there's no connection. With the same engine as the server, routes, guidance and way IDs (which §3.2 is keyed by) then match the server's. Only Valhalla's graph is described so far.

```json
{
  "format": 1,
  "engine": "valhalla",
  "version": "3.9.1",
  "build": "758e170c8b3cf751",
  "generated": "2026-10-09T23:57:26Z",
  "attribution": "© OpenStreetMap contributors, ODbL",
  "levels": [
    { "level": 0, "size": 4 },
    { "level": 1, "size": 1 },
    { "level": 2, "size": 0.25 }
  ],
  "tiles": {
    "0": [[3381, 4886520, 1869545]],
    "1": [[54204, 9032168, 3312542]],
    "2": [
      [864819, 35743800, 12125246],
      [864820, 16303200, 5617922]
    ]
  }
}
```

| Field         | Meaning                                                                                                                                                                                    |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `format`      | `1`. A client that doesn't know a manifest's format doesn't use the tiles.                                                                                                                 |
| `engine`      | `valhalla`.                                                                                                                                                                                |
| `version`     | The engine version that built the tiles. Clients use them only with an engine that reads that version's tiles.                                                                             |
| `build`       | Names this set of tiles: letters, digits, `.`, `_` and `-`. Tiles from different builds don't fit together, so clients keep one build's tiles and replace them all when the build changes. |
| `levels`      | The graph's hierarchy: each level and the size of its tiles in degrees. Valhalla's are 0 for motorways and trunk roads, 1 for arterials and 2 for local roads.                             |
| `tiles`       | The tiles of each level that exist (a tile without roads doesn't): `[tile ID, bytes, download bytes]`, by ID. The sizes let clients say what an area costs before downloading it.          |
| `attribution` | Credits for the data, which clients show.                                                                                                                                                  |

**Tiles.** A tile's ID is `row × columns + column`, counting rows of `size` degrees from 90° south and columns from 180° west (`columns` = 360 / `size`). Its path is Valhalla's: the level, then the ID padded with zeros to as many digits as the level's largest ID has, rounded up to a multiple of three, in groups of three, plus `.gph`. Level 2's tile 864819 (60°–60.25° N, 24.75°–25° E) is `2/000/864/819.gph`, and `{tiles}/{build}/2/000/864/819.gph.gz` is that tile, gzipped. That's also what Valhalla fetches itself with `mjolnir.tile_url` set to `{tiles}/{build}/{tilePath}` and `tile_url_gz` on.

**Which tiles.** An area needs its tiles on every level. Looking up the road under the car needs level 2 there, even on a motorway, because Valhalla finds the roads near a position through level 2's spatial index. A route needs level 2 at both ends and the levels above it in between. Clients that also want road lookups along the way, for §3.2's limits, take level 2 along the whole route.

**Updates.** Tiles never change within a build, so servers can send them with `Cache-Control: immutable`; clients revalidate the manifest with its `ETag`. A tile of a build the server no longer has answers `404`: clients then fetch the manifest again and start over with the new build. Servers should keep the previous build for at least a day after publishing a new one, so clients can finish downloading it. A server that lists `tiles` but hasn't published any answers `404` for the manifest, and clients do without.

The reference stack publishes the graph its Valhalla built with `infra/datapacks/routing_tiles.py`. The build is a hash of the tiles, so publishing an unchanged graph changes nothing. Finland's graph is 1,421 tiles, 841 MB, or 332 MB to download.

### 3.4 Search packs

```
GET {datapacks}/{region}/search/manifest.json
GET {datapacks}/{region}/search/{build}/{cell}.json.gz
```

A region's places, streets, addresses and points of interest, for searching on the phone when there's no connection. They come in cells of 0.25°, numbered like Valhalla's local-road tiles (§3.3): `row × 1440 + column`, counting from 90° S and 180° W. So a client that keeps a local-road tile can take the same cell's search data, and search works wherever routes do.

```json
{
  "format": 1,
  "region": "FI",
  "build": "9e48d299fc92c27e",
  "generated": "2026-10-03T23:01:00Z",
  "attribution": "© OpenStreetMap contributors, ODbL",
  "size": 0.25,
  "cells": [
    [864819, 48408, 923414],
    [902262, 1783, 23661]
  ]
}
```

| Field       | Meaning                                                                          |
| ----------- | -------------------------------------------------------------------------------- |
| `format`    | `1`. A client that doesn't know a manifest's format doesn't use the pack.        |
| `build`     | Names this set of cells, like a routing build. Cells change only with the build. |
| `generated` | When the data was taken from OpenStreetMap.                                      |
| `size`      | The cells' size in degrees: `0.25`.                                              |
| `cells`     | The cells that have anything: `[cell, entries, download bytes]`, by cell.        |

A cell is a gzipped JSON file:

```json
{
  "format": 1,
  "entries": [
    ["street", "Mannerheimintie", "Helsinki", 60.18008, 24.92847, 0.1],
    [
      "address",
      "Mannerheimintie 5",
      "Helsinki",
      60.1696,
      24.9405,
      0,
      ["Mannerheimvägen 5"]
    ],
    ["place", "Kamppi", "Helsinki", 60.1683, 24.9306, 0.25, ["Kampen"]],
    [
      "poi",
      "Kampin keskus",
      "Urho Kekkosen katu 1, Helsinki",
      60.169,
      24.933,
      0.2
    ]
  ]
}
```

Each entry is `[kind, title, detail, lat, lon, weight, names]`:

| Item              | Meaning                                                                                                                               |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `kind`            | `place` (a town, a district, a square), `street`, `address` or `poi` (a named point of interest). Clients skip kinds they don't know. |
| `title`, `detail` | A search result's two lines, as shown.                                                                                                |
| `lat`, `lon`      | Where it is, in degrees: in the cell.                                                                                                 |
| `weight`          | How notable it is, 0–1, for ranking: towns and landmarks above street addresses.                                                      |
| `names`           | Optional: other names it's found by, such as the Swedish ones in Finland's bilingual towns.                                           |

Cells never change within a build, so servers can send them with `Cache-Control: immutable`; clients revalidate the manifest with its `ETag`. A cell of a build the server no longer has answers `404`: clients then fetch the manifest again. A region without a search pack answers `404` for the manifest, and clients search only online.

The reference builder (`infra/datapacks/search_pack.py`) takes the pack from the Photon dump the local stack imports. It keeps places, streets once per name and town in a cell, addresses and named points of interest, and leaves out water, landforms and boundaries. Finland's pack is 1.29 million entries in 1,166 cells, 18 MB to download; central Helsinki's cell is the largest, at 0.9 MB.

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
    {
      "id": "mTHYi9CkVdeQ",
      "type": "police",
      "lat": 60.17,
      "lon": 24.94,
      "bearing": 180,
      "reported": "2026-10-10T18:00:00Z",
      "expires": "2026-10-10T19:00:00Z",
      "confirmations": 2
    },
    {
      "id": "fintraffic:GUID50472141",
      "type": "accident",
      "lat": 60.21,
      "lon": 24.8,
      "reported": "2026-10-10T19:30:31Z",
      "expires": "2026-10-10T20:00:31Z",
      "source": {
        "name": "Fintraffic",
        "licence": "CC BY 4.0",
        "url": "https://www.digitraffic.fi"
      },
      "text": "Tie 51, Espoo. Liikennetiedote."
    }
  ]
}
```

| Field                 | Meaning                                                                                                                                                                                                                              |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `format`              | `1`. A client that doesn't know a feed's format ignores the feed.                                                                                                                                                                    |
| `id`                  | Unique, stable for the report's life.                                                                                                                                                                                                |
| `type`                | `police`, `speed_check`, `accident`, `hazard`, `stopped_vehicle`, `roadworks`, `slippery_road`, `animal` or `road_closed`. Clients skip types they don't know.                                                                       |
| `lat`, `lon`          | Where it is. For a stretch of road, where the stretch begins.                                                                                                                                                                        |
| `bearing`             | The direction of travel it concerns, in degrees clockwise from north. Absent means both directions. It only matters for `police`, `speed_check`, `accident`, `hazard` and `stopped_vehicle`; the other types concern the whole road. |
| `reported`, `expires` | ISO 8601 times, UTC.                                                                                                                                                                                                                 |
| `confirmations`       | How many drivers said it's still there.                                                                                                                                                                                              |
| `source`              | Only for official reports: `name`, `licence` and `url` of where it comes from. Clients show this attribution with the report.                                                                                                        |
| `text`                | Only for official reports: the source's description, in the source's language.                                                                                                                                                       |

One feed for the whole server works for the Nordics: hundreds of reports, tens of kilobytes compressed. A later version may add area queries or push (§2.6).

### 4.2 Reporting

```
POST {reports}
Content-Type: application/json

{ "type": "police", "lat": 60.17, "lon": 24.94, "bearing": 180 }
```

`bearing` is the reporter's heading, and may be left out. The server answers `201 Created` with `{ "id": "..." }`, `400 Bad Request` for an unknown type or a position or bearing out of range, or `429 Too Many Requests`.

### 4.3 Still there?

```
POST {reports}/{id}/votes
Content-Type: application/json

{ "still_there": true }
```

Clients ask drivers passing a report whether it's still there, at most once per report. The server answers `204 No Content`, `404 Not Found` for a report that's gone, `400` or `429`.

### 4.4 Lifetimes

Each type lasts a set time unless someone confirms it. In the reference server:

| Type                              | Lifetime |
| --------------------------------- | -------- |
| `police`, `speed_check`, `hazard` | 60 min   |
| `accident`                        | 90 min   |
| `stopped_vehicle`                 | 45 min   |
| `animal`                          | 30 min   |
| `slippery_road`                   | 4 h      |
| `road_closed`                     | 6 h      |
| `roadworks`                       | 24 h     |

A "yes" keeps a report for at least half its lifetime from the vote, but no report lasts more than three lifetimes in all. Once "no" votes outnumber "yes" votes, and there are at least two, a driver's report goes. Official reports last as long as their source says. Other servers may tune all of this.

The reference server limits each client address to 5 reports and 30 votes per 10 minutes, and keeps the addresses in memory only. With `FOXNAV_FINTRAFFIC=true` it adds Fintraffic's traffic messages for Finland ([Digitraffic](https://www.digitraffic.fi/en/road-traffic/), CC BY 4.0): accidents, closed roads, animals, slippery roads, and roadworks during phases that restrict traffic. With an account for Statens vegvesen's DATEX II feed (`FOXNAV_VEGVESEN_USERNAME` and `FOXNAV_VEGVESEN_PASSWORD`) it adds Norway's (NLOD 2.0): accidents, closed roads, animals, broken-down vehicles, slippery roads, hazards such as rockfalls and landslips, and roadworks while they hold up traffic, with lane closures, traffic lights or a lower limit.

## 5. Conformance

[`conformance/`](conformance/) checks any server against this document: the discovery document, the map services and data packs it lists, the realtime protocol message by message (with opaque payloads), and the reports API. Each convoy check uses a new convoy.

```sh
./gradlew :protocol:conformance:installDist
protocol/conformance/build/install/foxnav-conformance/bin/foxnav-conformance maps.example.org
```

It prints each check as passed, failed (with what the server should have done) or skipped, and exits non-zero on failures. Checks that leave something on the server for others to see run only with `--write`: a test report on Null Island (0, 0), voted away straight after. The reference server runs it in its own tests (`ConformanceTest`), next to `ConvoyRelayTest` for §2 in detail and `ConvoySessionTest` for the client in `shared/` against the server.
