import assert from "node:assert/strict";
import { test } from "node:test";
import { inviteFragment, parseInvite, serverBase } from "../js/invite.js";

const range = (length, start) => Uint8Array.from({ length }, (_, i) => (start + i) & 0xff);
const invite = {
  server: "http://192.168.3.202:8080",
  convoyId: range(16, 1),
  joinToken: range(16, 100),
  key: range(32, 200),
  ownerKey: range(32, 7),
  keyEpoch: 0,
  handovers: 0,
};

test("invites survive a round trip through a link", () => {
  const link = `https://maps.example.org/join#${inviteFragment(invite)}`;
  assert.deepEqual(parseInvite(link), invite);
  const later = { ...invite, keyEpoch: 3, handovers: 2 };
  assert.match(inviteFragment(later), /&e=3&h=2$/);
  assert.deepEqual(parseInvite(`foxnav://join#${inviteFragment(later)}`), later);
});

test("the app's links parse", () => {
  // As the app's Invite.toLink() writes them (InviteTest parses the same one): the server
  // percent-encoded, base64url without padding.
  const link = "foxnav://join#v=0&s=http%3A%2F%2F192.168.3.202%3A8080&c=AQIDBAUGBwgJCgsMDQ4PEA&t=ZGVmZ2hpamtsbW5vcHFycw" +
    "&k=yMnKy8zNzs_Q0dLT1NXW19jZ2tvc3d7f4OHi4-Tl5uc&o=BwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSY&e=2&h=1";
  assert.deepEqual(parseInvite(link), { ...invite, keyEpoch: 2, handovers: 1 });
});

test("broken invites are refused", () => {
  const good = inviteFragment(invite);
  assert.equal(parseInvite("https://example.org/join"), null);
  assert.equal(parseInvite(good.replace("v=0", "v=1")), null);
  assert.equal(parseInvite(good.replace(/k=[^&]+/, "k=AAAA")), null);
  assert.equal(parseInvite(good.replace(/o=[^&]+/, "o=AAAA")), null);
  assert.equal(parseInvite(good.replace(/&o=[^&]+/, "")), null, "no owner");
  assert.equal(parseInvite(good.replace(/c=[^&]+/, "c=!!!")), null);
  assert.equal(parseInvite(`${good}&e=-1`), null);
  assert.equal(parseInvite(`${good}&h=x`), null);
});

test("bare server addresses mean https", () => {
  assert.equal(serverBase("maps.example.org"), "https://maps.example.org");
  assert.equal(serverBase("http://10.0.2.2:8080/"), "http://10.0.2.2:8080");
});
