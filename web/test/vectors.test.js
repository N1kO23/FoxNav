// The web client reads and writes the same bytes as the app: these vectors come from the app's own
// code (shared/src/jvmTest/…/convoy/WebVectorsTest.kt). Run with: node --test web/test
import assert from "node:assert/strict";
import { test } from "node:test";
import { associatedData, importKey, open, seal } from "../js/convoy.js";
import { Channel, ClientMessage, ConvoyPayload, ServerMessage, decode, encode } from "../js/protobuf.js";

const PAYLOAD = "0a520a100a05c3846bc3a4120518ff9bb2021801122208f0cae9bd0410ffb0eaed011d000058412500a087432d0000804030fbecacff8f341a1a0a180880a194bd041080c1bbed011a0a4b6175707061746f7269";
const CIPHERTEXT = "63544910763f11f0f43453888b529d2da2e341c32a2547689cbf8ea449cc62edf1110b8440dbf0bc98f849303a39f30f8b6cfc98da8b9fb450cc64c655eb4d33b23f6244fad2e9762b7a660d1c91a93029ee442b40350a1d370e364384a8604ecd05d0a1";
const JOINED = "1a6a0a10a0a1a2a3a4a5a6a7a8a9aaabacadaeaf10ef9bafcdf8acd191711a0401010101221c082a12150802120cf0f1f2f3f4f5f6f7f8f9fafb1a0307070718dc0b282a320209093a22082a10031a150802120cf0f1f2f3f4f5f6f7f8f9fafb1a030707072080ecacff8f34";

const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
const bytes = (text) => Uint8Array.from(text.match(/../g), (h) => parseInt(h, 16));
const range = (length, start) => Uint8Array.from({ length }, (_, i) => (start + i) & 0xff);

const key = range(32, 0);
const nonce = range(12, 0xf0);
const convoyId = range(16, 0xa0);
const sender = 0x7123456789abcdefn;

const state = {
  state: {
    profile: { display_name: "Äkä", car: { color_rgb: 0x4c8dff }, watching: true },
    position: { lat_e7: 601_699_000, lon_e7: -249_384_000, speed_mps: 13.5, heading_deg: 271.25, accuracy_m: 4, fix_time_ms: 1_791_000_000_123n },
    route: { waypoints: [{ lat_e7: 601_000_000, lon_e7: 249_000_000, name: "Kauppatori" }] },
  },
};

test("payloads encode and decode as the app's", () => {
  assert.equal(hex(encode(ConvoyPayload, state)), PAYLOAD);
  const decoded = decode(ConvoyPayload, bytes(PAYLOAD));
  assert.equal(decoded.state.profile.display_name, "Äkä");
  assert.equal(decoded.state.profile.watching, true);
  assert.equal(decoded.state.position.lon_e7, -249_384_000);
  assert.equal(decoded.state.position.heading_deg, 271.25);
  assert.equal(decoded.state.position.fix_time_ms, 1_791_000_000_123n);
  assert.equal(decoded.state.route.waypoints[0].name, "Kauppatori");
});

test("sealing and opening match the app's AES-GCM", async () => {
  const cryptoKey = await importKey(key);
  const sealed = await seal(cryptoKey, 0, convoyId, sender, Channel.STATE, state, nonce);
  assert.equal(hex(sealed.ciphertext), CIPHERTEXT);
  const opened = await open(new Map([[0, cryptoKey]]), convoyId, sender, Channel.STATE, { key_epoch: 0, nonce, ciphertext: bytes(CIPHERTEXT) });
  assert.equal(opened.state.profile.display_name, "Äkä");
  // Bound to sender and channel: moved elsewhere, it doesn't open.
  assert.equal(await open(new Map([[0, cryptoKey]]), convoyId, sender + 1n, Channel.STATE, { key_epoch: 0, nonce, ciphertext: bytes(CIPHERTEXT) }), null);
  assert.equal(await open(new Map([[0, cryptoKey]]), convoyId, sender, Channel.EVENT, { key_epoch: 0, nonce, ciphertext: bytes(CIPHERTEXT) }), null);
  assert.equal(associatedData(convoyId, sender, Channel.STATE).length, 25);
});

test("server messages decode, 64-bit member IDs intact", () => {
  const message = decode(ServerMessage, bytes(JOINED));
  const joined = message.joined;
  assert.equal(joined.member_id, sender);
  assert.equal(joined.owner, 42n);
  assert.equal(joined.members[0].last_state.key_epoch, 2);
  assert.equal(joined.members[0].last_state_age_ms, 1500n);
  assert.equal(joined.key_history[0].channel, Channel.KEY);
  assert.equal(joined.key_history[0].server_time_ms, 1_791_000_000_000n);
  assert.equal(hex(encode(ServerMessage, message)), JOINED);
  assert.equal(hex(encode(ClientMessage, { ping: {} })), "4200");
});

test("unknown fields are skipped", () => {
  // Field 15 (varint) and field 16 (bytes) before a known field.
  const decoded = decode(ConvoyPayload, Uint8Array.from([0x78, 0x05, 0x82, 0x01, 0x01, 0x00, 0x12, 0x02, 0x08, 0x04]));
  assert.equal(decoded.quick_message.kind, 4);
});
