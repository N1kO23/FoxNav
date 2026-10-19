// The web client reads and writes the same bytes as the app: these vectors come from the app's own
// code (shared/src/jvmTest/…/convoy/WebVectorsTest.kt). Run with: node --test web/test
import assert from "node:assert/strict";
import { test } from "node:test";
import { FRAME, ReplayWindow, cardContent, concat, convoyKey, identityFromSeeds, memberIdOf, openFrame, ownerSignatures, parseHeader, sealFrame, sign, unwrapKey, verify, wrapKey } from "../js/convoy.js";
import { ClientMessage, Event, FrameKind, Position, ServerMessage, decode, encode } from "../js/protobuf.js";

const ALICE_SIGNING = "7776e870b93354f2a0b24c23f2a36cc4e80e223218c1b97926fdd018396a2b9b";
const ALICE_EXCHANGE = "34e42d4af5ef94a07a3a84201b889d4cd1a743cb27b11b6a10438a8feb8e5847";
const ALICE_ID = 0x0a5b6754;
const TAG = 0xf987e3ba;
const FRAME_HEX = "02f987e3ba0a5b67540000000705d618bf9b10dd3d54a75ff44cde8eaeacef92fe7fe80f65689b4e96d180a34626d365eb187f38588ed706531b9cc922dbf1b4222e33bee74205d9879a76a627c824e3f9fffed347f44e585373173a321ca9268ef36158faa6b375cacc6a2fcf2f1eb0d34a6a4d7079";
const CARD = "0a207776e870b93354f2a0b24c23f2a36cc4e80e223218c1b97926fdd018396a2b9b122034e42d4af5ef94a07a3a84201b889d4cd1a743cb27b11b6a10438a8feb8e58471a170a05c3846bc3a4120c0a054d617a646118ff9bb2021801";
const WRAPPED = "9b46fb9a8d09ca79c39b7883194280625af1d0e98bb47d3f4d69d0fb627f9abb257f73baa446cc5f";
const REMOVAL = "f4de908dfa2cdfaf14ca9103a3ae2c7a86e5a7bb53afe5f043597082ab9c3ee17c9f9391c2af2f9a64f8aa3edd992769f83615f96e5d3b068e3e8cd4e34ecf0d";
const JOINED = "1a410a10a0a1a2a3a4a5a6a7a8a9aaabacadaeaf1554675b0a1a04010101012208546b94552a000000320209093a140a0307070715546b94551880ecacff8f3420dc0b";
const REMOVE = "3a0b0d546b945510011a020505";

const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
const bytes = (text) => Uint8Array.from(text.match(/../g), (h) => parseInt(h, 16));
const range = (length, start) => Uint8Array.from({ length }, (_, i) => (start + i) & 0xff);

const convoyId = range(16, 0xa0);
const alice = await identityFromSeeds(range(32, 0x10), range(32, 0x30));
const bob = await identityFromSeeds(range(32, 0x50), range(32, 0x70));
const key = await convoyKey(0, range(32, 0), convoyId);
const newKey = range(32, 0x90);
const position = { lat_e7: 601_699_000, lon_e7: -249_384_000, speed_cms: 1350, heading_deg: 271, accuracy_m: 4, fix_time_ms: 1_791_000_000_123n };

test("identities and keys derive as the app's", async () => {
  assert.equal(hex(alice.signingKey), ALICE_SIGNING);
  assert.equal(hex(alice.exchangeKey), ALICE_EXCHANGE);
  assert.equal(alice.memberId, ALICE_ID);
  assert.equal(await memberIdOf(alice.signingKey), ALICE_ID);
  assert.equal(key.tag, TAG);
});

test("frames seal, open and check out as the app's", async () => {
  const frame = await sealFrame(convoyId, key, alice, 7, FrameKind.POSITION, encode(Position, position));
  assert.equal(hex(frame), FRAME_HEX);
  assert.deepEqual(parseHeader(frame), { kind: FrameKind.POSITION, tag: TAG, sender: ALICE_ID, counter: 7 });
  const opened = await openFrame(convoyId, key, bytes(FRAME_HEX));
  assert.deepEqual(decode(Position, opened.content), position);
  assert.equal(await opened.signedBy(alice.signingKey), true);
  assert.equal(await opened.signedBy(bob.signingKey), false, "only the sender's key checks out");
  // Bound to its convoy and its header.
  assert.equal(await openFrame(range(16, 0), key, frame), null);
  for (const at of [0, 9, 12, frame.length - 1]) {
    const changed = frame.slice();
    changed[at] ^= 1;
    assert.equal(await openFrame(convoyId, key, changed), null, `byte ${at} changed`);
  }
  assert.equal(await openFrame(convoyId, await convoyKey(1, range(32, 1), convoyId), frame), null, "another key");
  await assert.rejects(sealFrame(convoyId, key, alice, 8, FrameKind.POSITION, new Uint8Array(FRAME.MAX_CONTENT + 1)));
});

test("cards encode as the app's, and fit", async () => {
  const profile = { display_name: "Äkä", car: { make: "Mazda", color_rgb: 0x4c8dff }, watching: true };
  assert.equal(hex(cardContent(alice, profile)), CARD);
  const long = "Ääkkösiä ".repeat(30);
  const fitted = cardContent(alice, { display_name: long, car: { make: long, color_rgb: 1 } });
  assert.ok(fitted.length <= FRAME.MAX_CONTENT, `${fitted.length}`);
  const card = decode((await import("../js/protobuf.js")).Card, fitted);
  assert.ok(card.profile.display_name.length > 0 && long.startsWith(card.profile.display_name));
});

test("new keys wrap for one member only, as the app's", async () => {
  const wrapped = await wrapKey(convoyId, 1, alice, bob.memberId, bob.exchangeKey, newKey);
  assert.equal(hex(wrapped), WRAPPED);
  assert.deepEqual(await unwrapKey(convoyId, 1, bob, alice.exchangeKey, bytes(WRAPPED)), newKey);
  assert.equal(await unwrapKey(convoyId, 2, bob, alice.exchangeKey, wrapped), null, "another epoch");
  const carol = await identityFromSeeds(range(32, 0xb0), range(32, 0xd0));
  assert.equal(await unwrapKey(convoyId, 1, carol, alice.exchangeKey, wrapped), null, "someone else");
});

test("the owner's signatures match the app's", async () => {
  const removal = ownerSignatures.removal(convoyId, bob.memberId, 1);
  assert.equal(hex(await sign(alice, removal)), REMOVAL);
  assert.equal(await verify(alice.signingKey, removal, bytes(REMOVAL)), true);
  assert.equal(await verify(bob.signingKey, removal, bytes(REMOVAL)), false);
  assert.equal(await verify(alice.signingKey, concat(removal, Uint8Array.of(0)), bytes(REMOVAL)), false);
});

test("server messages decode and encode as the app's", () => {
  const message = decode(ServerMessage, bytes(JOINED));
  const joined = message.joined;
  assert.equal(joined.member_id, ALICE_ID);
  assert.deepEqual(joined.members, [bob.memberId, 42]);
  assert.equal(joined.frames[0].from, bob.memberId);
  assert.equal(joined.frames[0].age_ms, 1500n);
  assert.equal(joined.frames[0].server_time_ms, 1_791_000_000_000n);
  assert.equal(hex(encode(ServerMessage, message)), JOINED);
  const remove = { remove_member: { member_id: bob.memberId, epoch: 1, signature: Uint8Array.of(5, 5) } };
  assert.equal(hex(encode(ClientMessage, remove)), REMOVE);
  assert.equal(hex(encode(ClientMessage, { ping: {} })), "4200");
});

test("counters count once", () => {
  const window = new ReplayWindow();
  assert.equal(window.isNew(0), false);
  for (const counter of [1, 3, 2, 70]) {
    assert.equal(window.isNew(counter), true, `${counter}`);
    window.accept(counter);
    assert.equal(window.isNew(counter), false, `${counter} again`);
  }
  assert.equal(window.isNew(69), true, "late, but within the window");
  assert.equal(window.isNew(5), false, "older than the window");
});

test("unknown fields are skipped", () => {
  // Field 15 (varint) and field 16 (bytes) before a known field.
  const decoded = decode(Event, Uint8Array.from([0x78, 0x05, 0x82, 0x01, 0x01, 0x00, 0x12, 0x02, 0x08, 0x04]));
  assert.equal(decoded.quick_message.kind, 4);
});
