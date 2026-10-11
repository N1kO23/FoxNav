// Convoy cars between their position reports, as the app draws them (shared/…/convoy/MotionTrack.kt
// and RoadOrder.kt): extrapolated along their heading, gliding to each new report.

const EARTH_RADIUS_M = 6_371_008.8;
const MAX_PLAUSIBLE_MPS = 100;
const STILL_MPS = 1;

export const STALE_AFTER_MS = 10_000;
export const LOST_AFTER_MS = 60_000;

const radians = (degrees) => (degrees * Math.PI) / 180;
const degrees = (radians) => (radians * 180) / Math.PI;

/** The point [meters] from [point] ({lat, lon}) along [bearing] degrees, on a great circle. */
export function moved(point, meters, bearing) {
  if (meters === 0) return point;
  const angular = meters / EARTH_RADIUS_M;
  const theta = radians(bearing);
  const lat1 = radians(point.lat);
  const lon1 = radians(point.lon);
  const lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular) + Math.cos(lat1) * Math.sin(angular) * Math.cos(theta));
  const lon2 = lon1 + Math.atan2(Math.sin(theta) * Math.sin(angular) * Math.cos(lat1), Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
  return { lat: degrees(lat2), lon: degrees(lon2) };
}

export function distance(a, b) {
  const dLat = radians(b.lat - a.lat);
  const dLon = radians(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(radians(a.lat)) * Math.cos(radians(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

export function bearing(from, to) {
  const lat1 = radians(from.lat);
  const lat2 = radians(to.lat);
  const dLon = radians(to.lon - from.lon);
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  return (degrees(Math.atan2(y, x)) + 360) % 360;
}

/** A fix {point, speed (m/s), heading, time (Unix ms)} from a location that may lack speed or heading. */
export function fixOf(point, time, speed, heading, previous) {
  const seconds = previous ? (time - previous.time) / 1000 : null;
  const travelled = previous ? distance(previous.point, point) : null;
  let derived = seconds > 0 && seconds <= 10 ? travelled / seconds : null;
  if (derived !== null && derived > MAX_PLAUSIBLE_MPS) derived = null; // a jump, not a drive
  const fixSpeed = speed ?? derived ?? 0;
  let fixHeading;
  if (fixSpeed < STILL_MPS && previous) fixHeading = previous.heading; // standing still: GPS bearings are noise
  else if (heading !== null && heading !== undefined && !Number.isNaN(heading)) fixHeading = heading;
  else if (previous && travelled >= 1) fixHeading = bearing(previous.point, point);
  else fixHeading = previous?.heading ?? 0;
  return { point, speed: fixSpeed, heading: fixHeading, time };
}

/** Smooth motion for one car, fed with its fixes. */
export class MotionTrack {
  constructor(blendMs = 1_000, maxExtrapolationMs = 5_000) {
    this.blendMs = blendMs;
    this.maxExtrapolationMs = maxExtrapolationMs;
    this.fix = null;
    this.blendFrom = null;
    this.blendStart = 0;
  }

  /** Feeds a fix received at [now]; older ones than the current are ignored. */
  update(fix, now) {
    if (this.fix && fix.time <= this.fix.time) return;
    this.blendFrom = this.fix ? this.positionAt(now) : null;
    this.blendStart = now;
    this.fix = fix;
  }

  /** Where to draw the car at [now], or null before its first fix. */
  positionAt(now) {
    if (!this.fix) return null;
    const elapsed = Math.min(Math.max(now - this.fix.time, 0), this.maxExtrapolationMs);
    const estimate = moved(this.fix.point, (this.fix.speed * elapsed) / 1000, this.fix.heading);
    if (!this.blendFrom) return estimate;
    const progress = Math.min(Math.max((now - this.blendStart) / this.blendMs, 0), 1);
    if (progress >= 1) return estimate;
    return {
      lat: this.blendFrom.lat + (estimate.lat - this.blendFrom.lat) * progress,
      lon: this.blendFrom.lon + (estimate.lon - this.blendFrom.lon) * progress,
    };
  }
}

/** "waiting", "live", "stale" or "lost", as the app's tower. */
export function signal(member, now) {
  if (!member.fix || member.heardAt === undefined) return "waiting";
  const age = now - member.heardAt;
  if (age >= LOST_AFTER_MS) return "lost";
  if (age >= STALE_AFTER_MS) return "stale";
  return "live";
}
