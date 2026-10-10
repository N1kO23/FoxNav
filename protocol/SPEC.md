# FoxNav protocol, v0 (draft)

This document is for anyone building a FoxNav client or server. A FoxNav backend is any server that implements some or all of:

- the **discovery document**, which tells the app where everything is;
- standard map services: MapLibre styles and tiles, Valhalla-compatible routing and Photon-compatible search;
- the **realtime endpoint** for convoys (and later live reports).

**Status:** discovery (§1) is implemented by the reference server in `server/`. The realtime protocol (§2) is a draft for the convoy work and will change before v1. Its message definitions are in [`proto/foxnav/convoy/v0/convoy.proto`](proto/foxnav/convoy/v0/convoy.proto).

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
  "styles": { "day": "/styles/day.json", "night": "/styles/night.json" },
  "routing": { "type": "valhalla", "url": "/valhalla" },
  "search": { "type": "photon", "url": "/photon" },
  "regions": ["FI"]
}
```

## 2. Realtime (draft)

### 2.1 Transport

- A WebSocket connection to the `realtime` URL, using the subprotocol `foxnav.v0`.
- Every frame is binary and holds exactly one protobuf message: `ClientMessage` from the client, `ServerMessage` from the server.
- The client sends `Ping` when it has sent nothing for 15 s. The server closes connections that stay silent for 45 s.
- After a dropped connection, clients reconnect with exponential backoff (1 s up to 30 s, with jitter) and send `Resume` with the token from `Joined`. After reconnecting, they publish only their latest state, because old positions are worthless.

### 2.2 Convoys

A convoy is a room on one server.

1. **Create.** The client sends `CreateConvoy`. The server answers `ConvoyCreated` with a random `convoy_id` and a `join_token`. The client generates the convoy's encryption key locally (§2.3). The key never goes to the server.
2. **Invite.** The creator shares a link:

   ```
   https://<link domain>/join#v=0&s=<server URL>&c=<convoy_id>&t=<join_token>&k=<key>
   ```

   The parameters are form-encoded, with binary values in unpadded base64url. Everything sits in the URL fragment, which browsers never send to any server. So the operator of the link domain learns nothing, and the link works for convoys on any server. The same string can be shown as a QR code.
3. **Join.** The client connects to the server named in the invite and sends `JoinConvoy` with the convoy ID and join token. The server answers `Joined` with the member ID it assigned, a resume token, its limits, and the last state of every member (still encrypted). Other members get `MemberJoined`.
4. **Publish.** Members send `Publish` with an encrypted payload on one of two channels:
   - `STATE` for things where only the latest value matters (position, profile, shared route). The server keeps the latest `STATE` payload per member for people who join later.
   - `EVENT` for one-off messages (quick messages, regroup pins, key rotations). The server relays them without keeping them.

   The server forwards each payload to all other members as `Relay`. It may drop `STATE` messages that arrive faster than once per second.
5. **Leave or remove.** `LeaveConvoy` removes yourself. The convoy's creator can send `RemoveMember`, after which the server stops relaying to and from that member. Others get `MemberLeft`.

Servers never store positions. Rooms live in memory and expire when they have been empty for a while.

### 2.3 End-to-end encryption

- **Key:** 32 random bytes generated by the convoy creator. Members receive it through the invite.
- **Cipher:** AES-256-GCM with a fresh random 12-byte nonce per message and a 16-byte tag.
- **Associated data:** `convoy_id ‖ sender member_id (8 bytes, big-endian) ‖ channel (1 byte)`. A server can't move a payload to another convoy, member or channel without detection.
- **Plaintext:** a serialized `ConvoyPayload` message.
- **Rotation:** after a removal, the creator sends a `KeyRotation` event with a new key and epoch, encrypted under the current key. Each `Sealed` payload names the epoch of its key.

See [`docs/threat-model.md`](../docs/threat-model.md) for what this protects against and what it doesn't.

### 2.4 Payloads

Defined in the `.proto` file: `Position` (location, speed, heading, accuracy, fix time), `Profile` (display name and car), `RouteShare` (destination and via points), `QuickMessage`, `RegroupPin` and `KeyRotation`.

### 2.5 Not yet specified

These arrive in a later version of this document:

- live reports (police, hazards, roadworks…);
- data packs (speed cameras, speed-limit fixes);
- a `NowPlaying` payload for the convoy soundtrack: track metadata, service IDs and ISRC, playback position and state.

## 3. Conformance

A conformance test suite will run against any server URL and report which parts of this spec the server implements correctly. Until it exists, the reference server's tests in `server/src/test/` are the executable description of §1.
