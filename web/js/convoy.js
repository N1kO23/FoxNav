// A convoy member in the browser, the same protocol as the app's ConvoySession
// (shared/…/convoy/ConvoySession.kt, protocol/SPEC.md §2): one WebSocket to the convoy's server,
// every payload end-to-end encrypted with the invite's key (AES-256-GCM, WebCrypto).

import { Channel, ClientMessage, ConvoyPayload, ErrorCode, ServerMessage, decode, encode } from "./protobuf.js";

export const SUBPROTOCOL = "foxnav.v0";

const TIMING = {
  stateIntervalMs: 1_000, // at most this often while our state changes
  stateRefreshMs: 5_000, // and this often when it doesn't
  pingIntervalMs: 15_000,
  silenceTimeoutMs: 45_000,
  minBackoffMs: 1_000,
  maxBackoffMs: 30_000,
};

// --- Encryption (spec §2.3) ---

export async function importKey(raw) {
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** convoy ID ‖ sender member ID (8 bytes, big-endian) ‖ channel (1 byte). */
export function associatedData(convoyId, sender, channel) {
  const out = new Uint8Array(convoyId.length + 9);
  out.set(convoyId, 0);
  new DataView(out.buffer).setBigUint64(convoyId.length, BigInt.asUintN(64, sender));
  out[convoyId.length + 8] = channel;
  return out;
}

export async function seal(key, epoch, convoyId, sender, channel, payload, nonce = crypto.getRandomValues(new Uint8Array(12))) {
  const plaintext = encode(ConvoyPayload, payload);
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: associatedData(convoyId, sender, channel), tagLength: 128 },
    key,
    plaintext,
  );
  return { key_epoch: epoch, nonce, ciphertext: new Uint8Array(ciphertext) };
}

/** The payload, or null when it doesn't open with the keys we have (wrong key, tampered or moved). */
export async function open(keys, convoyId, sender, channel, sealed) {
  const key = keys.get(sealed?.key_epoch ?? 0);
  if (!key || !sealed.nonce || !sealed.ciphertext) return null;
  try {
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: sealed.nonce, additionalData: associatedData(convoyId, sender, channel), tagLength: 128 },
      key,
      sealed.ciphertext,
    );
    return decode(ConvoyPayload, new Uint8Array(plaintext));
  } catch {
    return null;
  }
}

// --- The session ---

/**
 * Joins the convoy in [invite] through [realtimeUrl] and stays in it, reconnecting as needed.
 * Dispatches "change" whenever members, status or the invite change, and "notice" events
 * ({kind: "quick" | "regroup" | "joined" | "left", member, …}) for things to show briefly.
 */
export class ConvoySession extends EventTarget {
  constructor(invite, realtimeUrl, { clientName = "FoxNav Web", now = () => Date.now() } = {}) {
    super();
    this.invite = { ...invite };
    this.realtimeUrl = realtimeUrl;
    this.clientName = clientName;
    this.now = now;
    this.status = "connecting"; // connecting, live, reconnecting, ended
    this.endReason = null; // left, removed, gone, invalid, full
    this.memberId = null;
    this.ownerId = null;
    this.members = new Map(); // member ID (BigInt) -> {id, profile, fix, route, heardAt}
    this.regroup = null;
    this.myState = null;
    this.resumeToken = null;
    this.rawKeys = new Map([[invite.keyEpoch, invite.key]]);
    this.keys = new Map();
    this.socket = null;
    this.wake = null;
  }

  /** Connects, and keeps reconnecting with backoff until [leave] or the convoy ends. */
  async start() {
    this.keys.set(this.invite.keyEpoch, await importKey(this.invite.key));
    let attempt = 0;
    while (this.status !== "ended") {
      const seated = await this.connectOnce();
      if (this.status === "ended") break;
      attempt = seated ? 1 : attempt + 1;
      this.setStatus("reconnecting");
      await this.pause(this.backoffMs(attempt));
    }
  }

  /** Our state for the others: {profile, position?, route?} as in MemberState. */
  setState(state) {
    this.myState = state;
    this.stateChanged = true;
  }

  /** Skips the wait before the next reconnect, e.g. when the network comes back. */
  reconnectNow() {
    this.wake?.();
  }

  /** Leaves the convoy for good. */
  leave() {
    if (this.status === "ended") return;
    this.send({ leave_convoy: {} });
    this.end("left");
    this.socket?.close();
  }

  // --- Internals ---

  backoffMs(attempt) {
    const exponential = Math.min(TIMING.maxBackoffMs, TIMING.minBackoffMs * 2 ** Math.min(attempt - 1, 16));
    return exponential * (0.8 + Math.random() * 0.4);
  }

  pause(ms) {
    return new Promise((resolve) => {
      const timer = setTimeout(done, ms);
      function done() {
        clearTimeout(timer);
        resolve();
      }
      this.wake = done;
    });
  }

  setStatus(status) {
    if (this.status === status || this.status === "ended") return;
    this.status = status;
    this.changed();
  }

  end(reason) {
    if (this.status === "ended") return;
    this.endReason = reason;
    this.status = "ended";
    this.changed();
  }

  changed() {
    this.dispatchEvent(new Event("change"));
  }

  notice(detail) {
    this.dispatchEvent(new CustomEvent("notice", { detail }));
  }

  send(message) {
    if (this.socket?.readyState === 1) this.socket.send(encode(ClientMessage, message));
  }

  openingMessage() {
    if (this.resumeToken) return { resume: { resume_token: this.resumeToken } };
    return { join_convoy: { convoy_id: this.invite.convoyId, join_token: this.invite.joinToken } };
  }

  /** One connection until it drops; resolves with whether we got into the convoy on it. */
  connectOnce() {
    return new Promise((resolve) => {
      let seated = false;
      let lastHeard = this.now();
      let queue = Promise.resolve();
      let timers = [];
      const socket = new WebSocket(this.realtimeUrl, SUBPROTOCOL);
      socket.binaryType = "arraybuffer";
      this.socket = socket;

      socket.onopen = () => {
        this.send({ hello: { client: this.clientName } });
        this.send(this.openingMessage());
        timers.push(setInterval(() => this.send({ ping: {} }), TIMING.pingIntervalMs));
        timers.push(setInterval(() => {
          if (this.now() - lastHeard > TIMING.silenceTimeoutMs) socket.close();
        }, 5_000));
      };
      socket.onmessage = (event) => {
        lastHeard = this.now();
        let message;
        try {
          message = decode(ServerMessage, new Uint8Array(event.data));
        } catch {
          return;
        }
        // Messages decrypt asynchronously but must apply in order.
        queue = queue.then(() => this.handle(message, socket)).then((result) => {
          if (result === "seated" && !seated) {
            seated = true;
            timers.push(setInterval(() => this.publishState(), TIMING.stateIntervalMs));
            this.publishState(true);
          } else if (result === "close") {
            socket.close();
          }
        }).catch(() => {});
      };
      socket.onclose = () => {
        timers.forEach(clearInterval);
        timers = [];
        if (this.socket === socket) this.socket = null;
        resolve(seated);
      };
    });
  }

  async publishState(force = false) {
    const state = this.myState;
    if (!state || this.memberId === null) return;
    const due = force || this.stateChanged || this.now() - (this.lastPublished ?? 0) >= TIMING.stateRefreshMs;
    if (!due) return;
    this.stateChanged = false;
    this.lastPublished = this.now();
    const epoch = Math.max(...this.keys.keys());
    const sealed = await seal(this.keys.get(epoch), epoch, this.invite.convoyId, this.memberId, Channel.STATE, { state });
    this.send({ publish: { channel: Channel.STATE, payload: sealed } });
  }

  /** Applies one server message; returns "seated" once in, "close" when this connection is done for. */
  async handle(message, socket) {
    if (message.invite_changed) {
      this.invite.joinToken = message.invite_changed.join_token;
      this.changed();
    }
    if (message.joined) {
      await this.onJoined(message.joined);
      return "seated";
    }
    if (message.relay) await this.onRelay(message.relay);
    if (message.member_joined) {
      const id = message.member_joined.member_id;
      if (!this.members.has(id)) this.members.set(id, { id });
      this.notice({ kind: "joined", member: this.members.get(id) });
      this.changed();
    }
    if (message.member_left) {
      const { member_id: id, removed, new_owner: newOwner } = message.member_left;
      if (id === this.memberId) {
        this.end(removed ? "removed" : "left");
        return "close";
      }
      const member = this.members.get(id);
      this.members.delete(id);
      if (newOwner) this.ownerId = newOwner;
      this.notice({ kind: "left", member, removed });
      this.changed();
    }
    if (message.error) return this.onError(message.error, socket);
    return null;
  }

  async onJoined(joined) {
    const convoyId = joined.convoy_id;
    this.resumeToken = joined.resume_token;
    if (joined.join_token?.length) this.invite.joinToken = joined.join_token;
    this.ownerId = joined.owner;
    // Rotations we missed, before opening anything sealed with their keys.
    for (const rotation of joined.key_history) await this.applyKeyRotation(convoyId, rotation);
    const now = this.now();
    const before = this.members;
    this.members = new Map();
    for (const member of joined.members) {
      const known = before.get(member.member_id) ?? { id: member.member_id };
      const payload = member.last_state ? await open(this.keys, convoyId, member.member_id, Channel.STATE, member.last_state) : null;
      this.members.set(member.member_id, withState(known, payload?.state, now - Number(member.last_state_age_ms ?? 0n)));
    }
    this.memberId = joined.member_id;
    this.status = "live";
    this.changed();
  }

  async applyKeyRotation(convoyId, relay) {
    if (!relay.payload) return;
    const payload = await open(this.keys, convoyId, relay.sender, Channel.KEY, relay.payload);
    const rotation = payload?.key_rotation;
    const current = Math.max(...this.keys.keys());
    if (!rotation || (rotation.epoch ?? 0) <= current || rotation.key?.length !== 32) return;
    this.keys.set(rotation.epoch, await importKey(rotation.key));
    this.rawKeys.set(rotation.epoch, rotation.key);
    this.invite.key = rotation.key;
    this.invite.keyEpoch = rotation.epoch;
    this.changed();
  }

  async onRelay(relay) {
    const convoyId = this.invite.convoyId;
    if (relay.channel === Channel.KEY) {
      // Only the owner rotates keys; the server checks too.
      if (relay.sender === this.ownerId) await this.applyKeyRotation(convoyId, relay);
      return;
    }
    const payload = await open(this.keys, convoyId, relay.sender, relay.channel, relay.payload);
    if (!payload) return;
    if (relay.channel === Channel.STATE && payload.state) {
      const known = this.members.get(relay.sender) ?? { id: relay.sender };
      this.members.set(relay.sender, withState(known, payload.state, this.now()));
      this.changed();
    } else if (relay.channel === Channel.EVENT) {
      const member = this.members.get(relay.sender);
      if (payload.quick_message) this.notice({ kind: "quick", member, quick: payload.quick_message.kind ?? 0 });
      if (payload.regroup_pin?.location) {
        this.regroup = { ...waypoint(payload.regroup_pin.location), by: relay.sender };
        this.notice({ kind: "regroup", member, regroup: this.regroup });
        this.changed();
      }
    }
  }

  onError(error, socket) {
    if (this.memberId === null || this.status !== "live") {
      // The answer to our opening message.
      switch (error.code) {
        case ErrorCode.NOT_FOUND:
          if (this.resumeToken) {
            // Our seat expired while we were away: join again with the invite.
            this.resumeToken = null;
            if (socket.readyState === 1) socket.send(encode(ClientMessage, this.openingMessage()));
            return null;
          }
          this.end("gone");
          return "close";
        case ErrorCode.FORBIDDEN:
          this.end("invalid");
          return "close";
        case ErrorCode.CONVOY_FULL:
          this.end("full");
          return "close";
        default:
          return null;
      }
    }
    return null;
  }
}

/** A member with a new state; an older position than the one we have (a replay) changes nothing. */
export function withState(member, state, heardAt) {
  if (!state) return member;
  const position = state.position;
  const time = position ? Number(position.fix_time_ms ?? 0n) : null;
  if (position && member.fix && time < member.fix.time) return member;
  return {
    ...member,
    profile: state.profile ?? member.profile,
    fix: position
      ? { point: { lat: (position.lat_e7 ?? 0) / 1e7, lon: (position.lon_e7 ?? 0) / 1e7 }, speed: position.speed_mps ?? 0, heading: position.heading_deg ?? 0, time }
      : member.fix,
    route: state.route,
    heardAt,
  };
}

export function waypoint(w) {
  return { lat: (w.lat_e7 ?? 0) / 1e7, lon: (w.lon_e7 ?? 0) / 1e7, name: w.name ?? "" };
}

/** Our position for MemberState. */
export function position(fix, accuracy) {
  return {
    lat_e7: Math.round(fix.point.lat * 1e7),
    lon_e7: Math.round(fix.point.lon * 1e7),
    speed_mps: fix.speed,
    heading_deg: fix.heading,
    accuracy_m: accuracy ?? 0,
    fix_time_ms: BigInt(Math.round(fix.time)),
  };
}
