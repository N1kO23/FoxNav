import assert from "node:assert/strict";
import { test } from "node:test";
import { ConvoySession } from "../js/convoy.js";
import { Event, FrameKind, QuickMessageKind, decode } from "../js/protobuf.js";

const invite = { server: "http://localhost:8080", convoyId: new Uint8Array(16), key: new Uint8Array(32), ownerKey: new Uint8Array(32) };

/** A session whose sealed frames are recorded instead of sent, live once [live] is called. */
function session(now = () => 1_000_000) {
  const convoy = new ConvoySession(invite, "ws://localhost:8080/v1/ws", { now });
  const sent = [];
  convoy.sendFrame = async (socket, kind, content) => sent.push({ kind, event: decode(Event, content) });
  const live = () => {
    convoy.status = "live";
    convoy.socket = { readyState: 1 };
    return convoy.sendEvents();
  };
  return { convoy, sent, live };
}

test("quick messages and regroup points wait until we're in the convoy, then go out in order", async () => {
  const { convoy, sent, live } = session();
  convoy.sendQuickMessage(QuickMessageKind.SLOW_DOWN);
  convoy.dropRegroupPin({ lat: 60.1699, lon: 24.9384 }, "Regroup point");
  assert.equal(sent.length, 0, "not in yet");
  await live();
  assert.deepEqual(sent.map(({ kind }) => kind), [FrameKind.EVENT, FrameKind.EVENT]);
  assert.equal(sent[0].event.quick_message.kind, QuickMessageKind.SLOW_DOWN);
  assert.equal(sent[0].event.sent_ms, 1_000_000n);
  assert.deepEqual(sent[1].event.regroup_pin.location, { lat_e7: 601_699_000, lon_e7: 249_384_000, name: "Regroup point" });
});

test("our own regroup point shows here, and clearing it takes it off this map only", async () => {
  const { convoy, sent, live } = session();
  await live();
  convoy.dropRegroupPin({ lat: 60.17, lon: 24.94 }, "Regroup point");
  assert.equal(convoy.regroup.mine, true);
  assert.equal(convoy.regroup.name, "Regroup point");
  convoy.clearRegroup();
  assert.equal(convoy.regroup, null);
  assert.equal(sent.length, 1, "nothing goes out to take it back");
});

test("one that waited too long for the connection isn't sent", async () => {
  let clock = 0;
  const { convoy, sent, live } = session(() => clock);
  convoy.sendQuickMessage(QuickMessageKind.FUEL);
  clock = 5 * 60_000 + 1;
  convoy.sendQuickMessage(QuickMessageKind.ALL_GOOD);
  await live();
  assert.deepEqual(sent.map(({ event }) => event.quick_message.kind), [QuickMessageKind.ALL_GOOD]);
});
