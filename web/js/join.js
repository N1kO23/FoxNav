// FoxNav's convoy page (protocol/SPEC.md §2.5): open an invite link in any browser to follow the
// convoy on a map, and optionally share your own position while the page is open.
// Everything shown comes from other people: it goes into the page as text, never as HTML.

import * as maplibregl from "../vendor/maplibre-gl.mjs";
import { ConvoySession, position, waypoint } from "./convoy.js";
import { base64Url, inviteFragment, parseInvite } from "./invite.js";
import { MotionTrack, fixOf, signal } from "./motion.js";
import { QuickMessageKind } from "./protobuf.js";
import { discover } from "./server.js";

const REFRAME_MS = 8_000;
const PALETTE = [0x4c8dff, 0x3ddc97, 0xff5fa2, 0xb79bff, 0xa8e05f];
const LABEL_FONT = "Noto Sans Medium"; // the FoxNav styles' label font (infra/styles)
const QUICK = {
  [QuickMessageKind.FUEL]: "needs fuel",
  [QuickMessageKind.BREAK]: "wants a break",
  [QuickMessageKind.PULL_OVER]: "is pulling over",
  [QuickMessageKind.POLICE_AHEAD]: "police ahead",
  [QuickMessageKind.HAZARD_AHEAD]: "hazard ahead",
  [QuickMessageKind.SLOW_DOWN]: "slow down",
  [QuickMessageKind.ALL_GOOD]: "all good",
};
const WARNINGS = new Set([QuickMessageKind.POLICE_AHEAD, QuickMessageKind.HAZARD_AHEAD, QuickMessageKind.SLOW_DOWN]);
const ENDINGS = {
  left: ["You left the convoy", "Open the link again to rejoin."],
  removed: ["You were removed", "The convoy's owner removed you from the convoy."],
  gone: ["This convoy has ended", "Everyone has left, or the convoy is too old."],
  invalid: ["This invite no longer works", "Someone was removed from the convoy since it was shared, which changes the invite. Ask for a new one."],
  full: ["The convoy is full", "Try again when someone has left."],
};

const $ = (id) => document.getElementById(id);

/** An element with text content only; [children] are elements or strings. */
function el(tag, className, ...children) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  for (const child of children) element.append(child);
  return element;
}

const store = {
  get(key) {
    try { return localStorage.getItem(`foxnav.${key}`); } catch { return null; }
  },
  set(key, value) {
    try { localStorage.setItem(`foxnav.${key}`, value); } catch { /* private mode: not remembered */ }
  },
};

function showMessage(title, text, action) {
  $("start").hidden = true;
  $("tower").hidden = true;
  $("controls").hidden = true;
  $("message-title").textContent = title;
  $("message-text").textContent = text;
  const button = $("message-action");
  button.hidden = !action;
  if (action) {
    button.textContent = action.label;
    button.onclick = action.run;
  }
  $("message").hidden = false;
}

function toast(text, warn = false) {
  const item = el("div", warn ? "toast warn" : "toast", text);
  $("toasts").append(item);
  setTimeout(() => item.remove(), 4_000);
}

const hexColor = (rgb) => `#${(rgb & 0xffffff).toString(16).padStart(6, "0")}`;

// --- Start ---

const invite = parseInvite(location.hash);

// Opening another invite in this tab only changes the fragment: start over with the new convoy.
// (Our own updates to the address use replaceState, which doesn't fire this.)
addEventListener("hashchange", () => {
  const next = parseInvite(location.hash);
  if (next && (!invite || base64Url(next.convoyId) !== base64Url(invite.convoyId))) location.reload();
});

if (!invite) {
  showMessage("This isn't a convoy invite", "The link is incomplete. Ask whoever shared it to send it again, and open it whole.");
} else {
  start(invite).catch((error) => showMessage("Couldn't reach the convoy's server", `${error.message}. Check your connection and reload the page.`));
}

async function start(invite) {
  const server = await discover(invite.server);
  if (!server.realtime || !server.features.includes("convoy")) {
    showMessage("No convoys here", `${server.name} doesn't host convoys.`);
    return;
  }
  const map = createMap(server);

  $("start-server").textContent = `On ${server.name}. Your position stays end-to-end encrypted.`;
  $("open-app").href = `foxnav://join#${inviteFragment(invite)}`;
  $("name").value = store.get("name") ?? "";
  const canShare = "geolocation" in navigator && window.isSecureContext;
  if (!canShare) {
    $("share").disabled = true;
    $("share-note").textContent = "Sharing your location needs a secure (https) connection to this page.";
  }
  $("share").checked = canShare && store.get("share") === "true";
  $("start").hidden = false;

  $("join").onclick = () => {
    const name = $("name").value.trim().slice(0, 32) || "Guest";
    store.set("name", name);
    store.set("share", String($("share").checked));
    $("start").hidden = true;
    follow(invite, server, map, name, $("share").checked && canShare);
  };
}

function createMap(server) {
  const protocol = new globalThis.pmtiles.Protocol();
  maplibregl.addProtocol("pmtiles", protocol.tile);
  const theme = matchMedia("(prefers-color-scheme: light)").matches ? "day" : "night";
  const style = server.styles[theme] ?? Object.values(server.styles)[0];
  const map = new maplibregl.Map({
    container: "map",
    style,
    center: [24.94, 60.17],
    zoom: 5,
    attributionControl: { compact: true },
  });
  map.on("load", () => {
    map.addImage("car", carImage(), { sdf: true, pixelRatio: 2 });
    map.addSource("cars", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    map.addLayer({
      id: "cars",
      type: "symbol",
      source: "cars",
      layout: {
        "icon-image": "car",
        "icon-size": 1,
        "icon-rotate": ["get", "heading"],
        "icon-rotation-alignment": "map",
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
        "text-field": ["get", "name"],
        "text-font": [LABEL_FONT],
        "text-size": 13,
        "text-offset": [0, 1.4],
        "text-anchor": "top",
        "text-optional": true,
      },
      paint: {
        "icon-color": ["get", "color"],
        "icon-halo-color": "#0b0d12",
        "icon-halo-width": 2,
        "icon-opacity": ["get", "opacity"],
        "text-color": "#e8eaf0",
        "text-halo-color": "#0b0d12",
        "text-halo-width": 1.5,
        "text-opacity": ["get", "opacity"],
      },
    });
  });
  return map;
}

/** The car marker, an arrow pointing north, drawn for SDF tinting. */
function carImage() {
  const size = 56;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const g = canvas.getContext("2d");
  g.fillStyle = "#000";
  g.beginPath();
  g.moveTo(size * 0.5, size * 0.1);
  g.lineTo(size * 0.82, size * 0.86);
  g.lineTo(size * 0.5, size * 0.69);
  g.lineTo(size * 0.18, size * 0.86);
  g.closePath();
  g.fill();
  return g.getImageData(0, 0, size, size);
}

// --- In the convoy ---

function follow(invite, server, map, name, sharing) {
  let color = Number(store.get("color"));
  if (!PALETTE.includes(color)) {
    color = PALETTE[Math.floor(Math.random() * PALETTE.length)];
    store.set("color", String(color));
  }
  const session = new ConvoySession(invite, server.realtime, { clientName: "FoxNav Web" });
  const tracks = new Map();
  const markers = new Map();
  let me = null; // our own fix, while sharing
  let watchId = null;
  let wakeLock = null;
  // Everyone stays in view as the convoy drives, until the reader moves the map themselves.
  let following = true;
  let framedCars = 0;
  let framedAt = 0;

  const profile = () => ({ display_name: name, car: { color_rgb: color }, watching: watchId === null });
  const publish = () => session.setState({ profile: profile(), position: me && watchId !== null ? position(me, me.accuracy) : undefined });

  function startSharing() {
    if (watchId !== null) return;
    watchId = navigator.geolocation.watchPosition(
      ({ coords, timestamp }) => {
        const speed = Number.isFinite(coords.speed) ? coords.speed : null;
        const heading = Number.isFinite(coords.heading) ? coords.heading : null;
        me = { ...fixOf({ lat: coords.latitude, lon: coords.longitude }, timestamp, speed, heading, me), accuracy: coords.accuracy };
        publish();
      },
      (error) => {
        toast(error.code === error.PERMISSION_DENIED ? "Location access was denied" : "Can't get your location", true);
        stopSharing();
      },
      { enableHighAccuracy: true, maximumAge: 0 },
    );
    navigator.wakeLock?.request("screen").then((lock) => { wakeLock = lock; }).catch(() => {});
    $("share-live").checked = true;
    publish();
  }

  function stopSharing() {
    if (watchId !== null) navigator.geolocation.clearWatch(watchId);
    watchId = null;
    me = null;
    wakeLock?.release().catch(() => {});
    wakeLock = null;
    $("share-live").checked = false;
    publish();
  }

  $("share-live").disabled = $("share").disabled;
  $("share-live").onchange = () => {
    store.set("share", String($("share-live").checked));
    if ($("share-live").checked) startSharing();
    else stopSharing();
  };
  $("leave").onclick = () => session.leave();
  $("recenter").onclick = () => {
    following = true;
    frame(Date.now(), features(Date.now()));
  };
  for (const event of ["dragstart", "zoomstart", "rotatestart", "pitchstart"]) {
    map.on(event, (e) => { if (e.originalEvent) following = false; });
  }
  $("tower").hidden = false;
  $("controls").hidden = false;

  session.addEventListener("change", () => {
    // Rotated keys and new join tokens: keep the address bar's link working for a reload.
    history.replaceState(null, "", `#${inviteFragment(session.invite)}`);
    if (session.status === "ended") {
      stopSharing();
      const [title, text] = ENDINGS[session.endReason] ?? ["The convoy ended", ""];
      showMessage(title, text, session.endReason === "left" ? { label: "Rejoin", run: () => location.reload() } : null);
      return;
    }
    renderTower();
    renderPins();
  });
  session.addEventListener("notice", ({ detail }) => {
    const who = detail.member?.profile?.display_name || "Someone";
    if (detail.kind === "quick") toast(`${who}: ${QUICK[detail.quick] ?? "sent a message"}`, WARNINGS.has(detail.quick));
    else if (detail.kind === "regroup") toast(`${who} set a regroup point`);
    else if (detail.kind === "left") toast(`${who} left the convoy`);
  });
  addEventListener("online", () => session.reconnectNow());
  addEventListener("pagehide", (event) => { if (!event.persisted) session.leave(); });

  if (sharing) startSharing();
  else publish();
  session.start();
  requestAnimationFrame(draw);

  function renderTower() {
    const now = Date.now();
    const status = $("status");
    status.textContent = { live: "Live", connecting: "Connecting", reconnecting: "Reconnecting" }[session.status] ?? session.status;
    status.className = `pill ${session.status}`;
    const rows = [el("li", "me",
      el("span", "swatch"), el("span", "name", `${name} (you)`),
      el("span", "state", watchId === null ? "watching" : ""), el("span", "speed", me ? String(Math.round(me.speed * 3.6)) : "–"))];
    rows[0].firstChild.style.background = hexColor(color);
    const others = [...session.members.values()].sort((a, b) => (b.fix?.speed ?? -1) - (a.fix?.speed ?? -1));
    for (const member of others) {
      const state = signal(member, now);
      const label = state === "waiting" ? (member.profile?.watching ? "watching" : "joining") : state === "lost" ? "LOST" : state === "stale" ? "stale" : "";
      // The part they took on in the convoy, as the app's timing tower shows it.
      const role = { 1: "LEAD", 2: "TAIL" }[member.profile?.role];
      const row = el("li", state,
        el("span", "swatch"), el("span", "name", member.profile?.display_name || "Driver", ...(role ? [" ", el("span", `role ${role.toLowerCase()}`, role)] : [])),
        el("span", "state", label), el("span", "speed", member.fix && state !== "lost" ? String(Math.round(member.fix.speed * 3.6)) : "–"));
      row.firstChild.style.background = hexColor(member.profile?.car?.color_rgb ?? PALETTE[0]);
      rows.push(row);
    }
    $("members").replaceChildren(...rows);
  }
  setInterval(renderTower, 2_000);

  function pin(key, point, text, className) {
    let marker = markers.get(key);
    if (!point) {
      marker?.remove();
      markers.delete(key);
      return;
    }
    if (!marker) {
      marker = new maplibregl.Marker({ element: el("div", `pin ${className}`), anchor: "bottom" }).setLngLat([point.lon, point.lat]).addTo(map);
      markers.set(key, marker);
    }
    marker.setLngLat([point.lon, point.lat]);
    marker.getElement().textContent = text;
  }

  function renderPins() {
    pin("regroup", session.regroup, session.regroup?.name || "Regroup", "");
    const shared = [...session.members.values()].find((member) => member.route?.waypoints?.length);
    const destination = shared ? waypoint(shared.route.waypoints.at(-1)) : null;
    pin("destination", destination, destination?.name || "Destination", "destination");
  }

  function features(now) {
    const out = [];
    for (const member of session.members.values()) {
      if (!member.fix) continue;
      let track = tracks.get(member.id);
      if (!track) tracks.set(member.id, (track = new MotionTrack()));
      track.update(member.fix, now);
      const at = track.positionAt(now);
      const state = signal(member, now);
      out.push(car(at, member.fix.heading, member.profile?.display_name || "Driver", member.profile?.car?.color_rgb ?? PALETTE[0], state));
    }
    for (const id of tracks.keys()) if (!session.members.has(id)) tracks.delete(id);
    if (me) out.push(car(me.point, me.heading, "You", color, "live"));
    return out;
  }

  function car(at, heading, label, rgb, state) {
    return {
      type: "Feature",
      geometry: { type: "Point", coordinates: [at.lon, at.lat] },
      properties: { name: label, heading, color: hexColor(rgb), opacity: state === "lost" ? 0.3 : state === "stale" ? 0.5 : 1 },
    };
  }

  /** Fits everyone into view: when someone new shows up, every few seconds while following, and on request. */
  function frame(now, cars) {
    const points = cars.map((feature) => feature.geometry.coordinates);
    if (!points.length) return;
    const first = framedCars === 0;
    framedCars = points.length;
    framedAt = now;
    const bounds = points.reduce((b, p) => b.extend(p), new maplibregl.LngLatBounds(points[0], points[0]));
    const tower = $("tower").getBoundingClientRect();
    map.fitBounds(bounds, { padding: { top: tower.bottom + 40, bottom: 140, left: 60, right: 60 }, maxZoom: 16, duration: first ? 0 : 1000 });
  }

  let last = 0;
  function draw(time) {
    // About 30 frames a second is plenty for cars; the source update is the expensive part.
    if (time - last >= 33) {
      last = time;
      const now = Date.now();
      const cars = features(now);
      map.getSource("cars")?.setData({ type: "FeatureCollection", features: cars });
      if (following && map.getSource("cars") && (cars.length !== framedCars || now - framedAt > REFRAME_MS)) frame(now, cars);
    }
    requestAnimationFrame(draw);
  }
}
