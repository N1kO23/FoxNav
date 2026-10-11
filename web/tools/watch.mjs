#!/usr/bin/env node
// Joins a convoy as a viewer, like the web page, and prints who's in it: a check that the web
// client and the app understand each other. Needs Node 22 (built-in WebSocket and WebCrypto).
//
//   node web/tools/watch.mjs --invite-file invite.txt [--server http://localhost:8080] [--seconds 60] [--name "Node"]
//
// The invite carries the convoy's key: keep it in a file, never on the command line or in logs.

import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { ConvoySession } from "../js/convoy.js";
import { parseInvite } from "../js/invite.js";
import { signal } from "../js/motion.js";
import { discover } from "../js/server.js";

const { values: options } = parseArgs({
  options: {
    "invite-file": { type: "string" },
    server: { type: "string" },
    seconds: { type: "string", default: "60" },
    name: { type: "string", default: "Node viewer" },
  },
});
const invite = parseInvite(readFileSync(options["invite-file"], "utf8").trim());
if (!invite) {
  console.error("That file has no valid invite link.");
  process.exit(2);
}
const server = await discover(options.server ?? invite.server);
const session = new ConvoySession(invite, server.realtime, { clientName: "FoxNav web tools" });
session.setState({ profile: { display_name: options.name, car: { color_rgb: 0xa8e05f }, watching: true } });

const log = (text) => console.log(`${new Date().toISOString().slice(11, 19)}  ${text}`);
const nameOf = (member) => member?.profile?.display_name || "a member";
let status = null;
session.addEventListener("change", () => {
  if (session.status !== status) {
    status = session.status;
    log(`status: ${status}${session.endReason ? ` (${session.endReason})` : ""}, key epoch ${session.invite.keyEpoch}`);
  }
});
session.addEventListener("notice", ({ detail }) => log(`${detail.kind}: ${nameOf(detail.member)}${detail.quick ? ` sent quick message ${detail.quick}` : ""}`));

const report = setInterval(() => {
  const now = Date.now();
  const rows = [...session.members.values()].map((member) => {
    const speed = member.fix ? `${Math.round(member.fix.speed * 3.6)} km/h, heading ${Math.round(member.fix.heading)}°` : "no position";
    const watching = member.profile?.watching && !member.fix ? " (watching)" : "";
    return `    ${nameOf(member)}: ${signal(member, now)}, ${speed}${watching}`;
  });
  log(`${rows.length} others in the convoy\n${rows.join("\n")}`);
}, 5_000);

const running = session.start();
setTimeout(() => {
  session.leave();
  clearInterval(report);
}, Number(options.seconds) * 1000);
await running;
clearInterval(report);
