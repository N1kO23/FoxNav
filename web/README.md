# Convoy page

The page invite links open: it offers the FoxNav app, or follows the convoy right in the browser, with a map, the timing tower and, if you choose, your own position while the page is open. See [`protocol/SPEC.md`](../protocol/SPEC.md) §2.5.

Plain JavaScript modules, no build step, no dependencies besides [MapLibre GL JS](https://maplibre.org) and [PMTiles](https://github.com/protomaps/PMTiles). The reference server serves it at `/join` and `/web/`; its build fetches those two libraries pinned and checked against npm's integrity hashes (`server/build.gradle.kts`).

| File | What it does |
|---|---|
| `join.html`, `join.css` | The page, in the app's night look |
| `js/protobuf.js` | Encodes and decodes the convoy messages (`protocol/proto/…/convoy.proto`) |
| `js/convoy.js` | The convoy protocol and its end-to-end encryption, like the app's `ConvoySession` |
| `js/invite.js`, `js/server.js` | Invite links and the server's discovery document |
| `js/motion.js` | Cars gliding between position reports, like the app's `MotionTrack` |
| `js/join.js` | The page itself |

## Tests

```sh
cd web && npm test     # Node 22's built-in test runner; no install needed
```

`test/vectors.test.js` checks the encoding and encryption against bytes from the app's own code (`shared/src/jvmTest/…/convoy/WebVectorsTest.kt`), so the two can't drift apart unnoticed.

To check a live convoy without a browser, `tools/watch.mjs` joins it as a viewer and prints who's in it:

```sh
node web/tools/watch.mjs --invite-file invite.txt --server http://localhost:8080
```

## Safety

The page holds the convoy's key. Everything other members send goes into the page as text, never as HTML, and the server sends it with a strict content security policy. Keep it that way: no `innerHTML`, no inline scripts, no third-party scripts at runtime.
