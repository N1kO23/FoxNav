import assert from "node:assert/strict";
import { test } from "node:test";
import { MotionTrack, distance, fixOf, moved, signal } from "../js/motion.js";

const here = { lat: 60.1699, lon: 24.9384 };

test("cars are extrapolated along their heading, at most 5 s", () => {
  const track = new MotionTrack();
  track.update({ point: here, speed: 20, heading: 90, time: 0 }, 0);
  assert.ok(Math.abs(distance(here, track.positionAt(1_000)) - 20) < 0.01);
  assert.ok(Math.abs(distance(here, track.positionAt(60_000)) - 100) < 0.01);
});

test("a new fix glides in instead of jumping", () => {
  const track = new MotionTrack();
  track.update({ point: here, speed: 0, heading: 0, time: 0 }, 0);
  const next = moved(here, 100, 0);
  track.update({ point: next, speed: 0, heading: 0, time: 1_000 }, 1_000);
  assert.ok(Math.abs(distance(here, track.positionAt(1_500)) - 50) < 0.5);
  assert.ok(distance(next, track.positionAt(2_000)) < 0.01);
  // Older fixes change nothing.
  track.update({ point: here, speed: 0, heading: 0, time: 500 }, 2_000);
  assert.ok(distance(next, track.positionAt(2_500)) < 0.01);
});

test("missing speed and heading come from the previous fix", () => {
  const first = fixOf(here, 0, null, null, null);
  const second = fixOf(moved(here, 15, 45), 1_000, null, null, first);
  assert.ok(Math.abs(second.speed - 15) < 0.01);
  assert.ok(Math.abs(second.heading - 45) < 0.1);
  // Standing still keeps the heading.
  assert.equal(fixOf(second.point, 2_000, 0, 200, second).heading, second.heading);
  // A jump isn't a speed.
  assert.equal(fixOf(moved(here, 5_000, 0), 3_000, null, null, second).speed, 0);
});

test("signal follows how long ago we heard from a member", () => {
  const fix = { point: here, speed: 0, heading: 0, time: 0 };
  assert.equal(signal({}, 0), "waiting");
  assert.equal(signal({ fix, heardAt: 0 }, 9_000), "live");
  assert.equal(signal({ fix, heardAt: 0 }, 10_000), "stale");
  assert.equal(signal({ fix, heardAt: 0 }, 60_000), "lost");
});
