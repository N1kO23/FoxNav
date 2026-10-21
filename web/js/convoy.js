// A convoy member in the browser, the same protocol as the app's ConvoySession
// (shared/.../convoy/ConvoySession.kt, protocol/SPEC.md §2): one WebSocket to the convoy's server.
// Everything it sends is a frame (§2.3), signed with its own Ed25519 key and sealed with the
// convoy key (AES-256-GCM); everything it takes in is checked against its sender's key. WebCrypto
// does all of it: AES-GCM, AES-KW, HKDF, Ed25519 and X25519.

import {
  Card,
  ClientMessage,
  ErrorCode,
  Event as EventContent,
  FrameKind,
  NowPlaying,
  OwnerStatement,
  Position,
  RouteShare,
  ServerMessage,
  decode,
  encode,
} from "./protobuf.js";

export const SUBPROTOCOL = "foxnav.v0";

const TIMING = {
  positionIntervalMs: 1_000, // at most this often while our position changes
  refreshMs: 5_000, // and this often when it doesn't (or the card, without a position)
  pingIntervalMs: 15_000,
  silenceTimeoutMs: 45_000,
  minBackoffMs: 1_000,
  maxBackoffMs: 30_000,
  holdMs: 30_000, // a frame waits this long for its sender's card, or for its key
  oldKeyGraceMs: 120_000, // frames under a replaced key still count for this long
};
const MAX_EVENT_AGE_MS = 5 * 60_000;
const MAX_QUEUED_EVENTS = 16;
const MAX_HELD = 256;

// --- Frames (spec §2.3) ---

export const FRAME = Object.freeze({
  HEADER: 13,
  SIGNATURE: 64,
  TAG: 16,
  MAX: 233,
  MAX_CONTENT: 140,
});

const subtle = crypto.subtle;
const ascii = (text) => new TextEncoder().encode(text);
const LABEL = {
  frame: ascii("foxnav v0 frame"),
  tag: ascii("foxnav v0 tag"),
  wrap: ascii("foxnav v0 wrap"),
  remove: ascii("foxnav v0 remove"),
  handover: ascii("foxnav v0 handover"),
};
// PKCS #8 headers for raw 32-byte private keys (RFC 8410), to import fixed keys for test vectors.
const PKCS8 = {
  Ed25519: fromHex("302e020100300506032b657004220420"),
  X25519: fromHex("302e020100300506032b656e04220420"),
};

export function concat(...parts) {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

function fromHex(text) {
  return Uint8Array.from(text.match(/../g), (pair) => parseInt(pair, 16));
}

const u32 = (value) => {
  const bytes = new Uint8Array(4);
  new DataView(bytes.buffer).setUint32(0, value >>> 0);
  return bytes;
};
const readU32 = (bytes, at) =>
  new DataView(bytes.buffer, bytes.byteOffset + at, 4).getUint32(0);
const same = (a, b) =>
  a?.length === b?.length && a.every((byte, i) => byte === b[i]);

/** A member ID: the first 4 bytes of the SHA-256 of their signing key. */
export async function memberIdOf(signingKey) {
  return readU32(new Uint8Array(await subtle.digest("SHA-256", signingKey)), 0);
}

async function hkdf(ikm, salt, info, length) {
  const key = await subtle.importKey("raw", ikm, "HKDF", false, ["deriveBits"]);
  return new Uint8Array(
    await subtle.deriveBits(
      { name: "HKDF", hash: "SHA-256", salt, info },
      key,
      length * 8,
    ),
  );
}

/** One convoy key and what derives from it: the frame key and the convoy tag. */
export async function convoyKey(epoch, raw, convoyId) {
  const frameKey = await subtle.importKey(
    "raw",
    await hkdf(raw, convoyId, LABEL.frame, 32),
    "AES-GCM",
    false,
    ["encrypt", "decrypt"],
  );
  return {
    epoch,
    raw,
    frameKey,
    tag: readU32(await hkdf(raw, convoyId, LABEL.tag, 4), 0),
  };
}

/** Our own keys in one convoy: Ed25519 to sign our frames, X25519 to receive new convoy keys. */
export async function newIdentity() {
  const signing = await subtle.generateKey({ name: "Ed25519" }, false, [
    "sign",
    "verify",
  ]);
  const exchange = await subtle.generateKey({ name: "X25519" }, false, [
    "deriveBits",
  ]);
  return identity(
    signing.privateKey,
    signing.publicKey,
    exchange.privateKey,
    exchange.publicKey,
  );
}

/** An identity from 32-byte private keys, as the app's tests make them. */
export async function identityFromSeeds(signingSeed, exchangeSeed) {
  const signingPrivate = await subtle.importKey(
    "pkcs8",
    concat(PKCS8.Ed25519, signingSeed),
    { name: "Ed25519" },
    true,
    ["sign"],
  );
  const exchangePrivate = await subtle.importKey(
    "pkcs8",
    concat(PKCS8.X25519, exchangeSeed),
    { name: "X25519" },
    true,
    ["deriveBits"],
  );
  return identity(
    signingPrivate,
    await publicOf(signingPrivate, ["verify"]),
    exchangePrivate,
    await publicOf(exchangePrivate, []),
  );
}

async function publicOf(privateKey, usages) {
  const { kty, crv, x } = await subtle.exportKey("jwk", privateKey);
  return subtle.importKey(
    "jwk",
    { kty, crv, x },
    { name: privateKey.algorithm.name },
    true,
    usages,
  );
}

async function identity(
  signingPrivate,
  signingPublic,
  exchangePrivate,
  exchangePublic,
) {
  const signingKey = new Uint8Array(
    await subtle.exportKey("raw", signingPublic),
  );
  const exchangeKey = new Uint8Array(
    await subtle.exportKey("raw", exchangePublic),
  );
  return {
    signingPrivate,
    exchangePrivate,
    signingKey,
    exchangeKey,
    memberId: await memberIdOf(signingKey),
  };
}

export async function sign(identity, message) {
  return new Uint8Array(
    await subtle.sign({ name: "Ed25519" }, identity.signingPrivate, message),
  );
}

/** Whether [signature] is [signingKey]'s over [message]; false for malformed keys too. */
export async function verify(signingKey, message, signature) {
  if (signingKey?.length !== 32 || signature?.length !== FRAME.SIGNATURE)
    return false;
  try {
    const key = await subtle.importKey(
      "raw",
      signingKey,
      { name: "Ed25519" },
      false,
      ["verify"],
    );
    return await subtle.verify({ name: "Ed25519" }, key, signature, message);
  } catch {
    return false;
  }
}

/** A frame's 13-byte header, which the server can read too. */
export function parseHeader(frame) {
  if (frame.length < FRAME.HEADER) return null;
  return {
    kind: frame[0],
    tag: readU32(frame, 1),
    sender: readU32(frame, 5),
    counter: readU32(frame, 9),
  };
}

/** Sender ‖ counter ‖ 4 zero bytes: unique as long as no identity reuses a counter. */
const nonce = (sender, counter) =>
  concat(u32(sender), u32(counter), new Uint8Array(4));

/** [identity]'s frame number [counter], which must never repeat for that identity. */
export async function sealFrame(
  convoyId,
  key,
  identity,
  counter,
  kind,
  content,
) {
  if (content.length > FRAME.MAX_CONTENT)
    throw new Error(`Frame content of ${content.length} bytes`);
  const header = concat(
    Uint8Array.of(kind),
    u32(key.tag),
    u32(identity.memberId),
    u32(counter),
  );
  const signature = await sign(
    identity,
    concat(LABEL.frame, convoyId, header, content),
  );
  const sealed = await subtle.encrypt(
    {
      name: "AES-GCM",
      iv: nonce(identity.memberId, counter),
      additionalData: concat(convoyId, header),
      tagLength: 128,
    },
    key.frameKey,
    concat(content, signature),
  );
  return concat(header, new Uint8Array(sealed));
}

/** The frame decrypted with [key], or null if it isn't one of [key]'s; it's its sender's only if [signedBy] their key. */
export async function openFrame(convoyId, key, frame) {
  if (
    frame.length < FRAME.HEADER + FRAME.SIGNATURE + FRAME.TAG ||
    frame.length > FRAME.MAX
  )
    return null;
  const header = parseHeader(frame);
  if (header.tag !== key.tag) return null;
  const headerBytes = frame.slice(0, FRAME.HEADER);
  let plain;
  try {
    plain = new Uint8Array(
      await subtle.decrypt(
        {
          name: "AES-GCM",
          iv: nonce(header.sender, header.counter),
          additionalData: concat(convoyId, headerBytes),
          tagLength: 128,
        },
        key.frameKey,
        frame.slice(FRAME.HEADER),
      ),
    );
  } catch {
    return null;
  }
  const content = plain.slice(0, plain.length - FRAME.SIGNATURE);
  const signature = plain.slice(plain.length - FRAME.SIGNATURE);
  return {
    header,
    content,
    signedBy: (signingKey) =>
      verify(
        signingKey,
        concat(LABEL.frame, convoyId, headerBytes, content),
        signature,
      ),
  };
}

/** The key that wraps a new convoy key for one member: from X25519 between the owner's and theirs. */
async function wrappingKey(convoyId, epoch, me, otherExchangeKey, memberId) {
  try {
    const other = await subtle.importKey(
      "raw",
      otherExchangeKey,
      { name: "X25519" },
      false,
      [],
    );
    const secret = new Uint8Array(
      await subtle.deriveBits(
        { name: "X25519", public: other },
        me.exchangePrivate,
        256,
      ),
    );
    const bytes = await hkdf(
      secret,
      convoyId,
      concat(LABEL.wrap, u32(epoch), u32(memberId)),
      32,
    );
    return await subtle.importKey("raw", bytes, "AES-KW", false, [
      "wrapKey",
      "unwrapKey",
    ]);
  } catch {
    return null; // a public key that gives no secret
  }
}

/** [newKey] wrapped (AES-KW) for member [memberId] alone, by the owner [owner]. */
export async function wrapKey(
  convoyId,
  epoch,
  owner,
  memberId,
  memberExchangeKey,
  newKey,
) {
  const wrapping = await wrappingKey(
    convoyId,
    epoch,
    owner,
    memberExchangeKey,
    memberId,
  );
  if (!wrapping) return null;
  const key = await subtle.importKey("raw", newKey, "AES-GCM", true, [
    "encrypt",
  ]);
  return new Uint8Array(await subtle.wrapKey("raw", key, wrapping, "AES-KW"));
}

/** The new convoy key the owner wrapped for us, or null if it isn't ours. */
export async function unwrapKey(
  convoyId,
  epoch,
  me,
  ownerExchangeKey,
  wrapped,
) {
  const wrapping = await wrappingKey(
    convoyId,
    epoch,
    me,
    ownerExchangeKey,
    me.memberId,
  );
  if (!wrapping) return null;
  try {
    const key = await subtle.unwrapKey(
      "raw",
      wrapped,
      wrapping,
      "AES-KW",
      "AES-GCM",
      true,
      ["encrypt"],
    );
    return new Uint8Array(await subtle.exportKey("raw", key));
  } catch {
    return null;
  }
}

/** What the owner signs for the server, so it can check removals and handovers too. */
export const ownerSignatures = {
  removal: (convoyId, memberId, epoch) =>
    concat(LABEL.remove, convoyId, u32(memberId), u32(epoch)),
  handover: (convoyId, number, newOwnerKey) =>
    concat(LABEL.handover, convoyId, u32(number), newOwnerKey),
};

/** Which of a sender's frame counters were accepted: the highest, and which of the 64 below it. */
export class ReplayWindow {
  constructor() {
    this.highest = 0;
    this.below = new Set();
  }

  isNew(counter) {
    if (counter < 1) return false;
    if (counter > this.highest) return true;
    const distance = this.highest - counter;
    return distance >= 1 && distance <= 64 && !this.below.has(counter);
  }

  accept(counter) {
    if (counter > this.highest) {
      if (this.highest > 0) this.below.add(this.highest);
      this.highest = counter;
      for (const old of this.below)
        if (this.highest - old > 64) this.below.delete(old);
    } else if (this.highest - counter <= 64) {
      this.below.add(counter);
    }
  }
}

/** Our card: keys and profile, with texts shortened to fit a frame. */
export function cardContent(identity, profile) {
  let shown = structuredClone(profile ?? {});
  for (;;) {
    const bytes = encode(Card, {
      signing_key: identity.signingKey,
      exchange_key: identity.exchangeKey,
      profile: shown,
    });
    const excess = bytes.length - FRAME.MAX_CONTENT;
    if (excess <= 0) return bytes;
    const texts = [
      [shown, "display_name"],
      [shown.car, "make"],
      [shown.car, "model"],
    ];
    const sizes = texts.map(
      ([owner, name]) => ascii(owner?.[name] ?? "").length,
    );
    const longest = sizes.indexOf(Math.max(...sizes));
    const next = Math.max(0, ...sizes.filter((_, i) => i !== longest));
    const [owner, name] = texts[longest];
    owner[name] = shortenedBy(
      owner[name],
      Math.max(1, Math.min(excess, sizes[longest] - next)),
    );
  }
}

/** At least [bytes] bytes shorter in UTF-8 (or empty), cut between characters. */
function shortenedBy(text, bytes) {
  const chars = Array.from(text);
  const target = ascii(text).length - bytes;
  while (chars.length && ascii(chars.join("")).length > target) chars.pop();
  return chars.join("").trimEnd();
}

// --- The session ---

/**
 * Joins the convoy in [invite] through [realtimeUrl] and stays in it, reconnecting as needed.
 * Dispatches "change" whenever members, status or the invite change, and "notice" events
 * ({kind: "quick" | "regroup" | "joined" | "left", member, ...}) for things to show briefly.
 */
export class ConvoySession extends EventTarget {
  constructor(
    invite,
    realtimeUrl,
    { clientName = "FoxNav Web", now = () => Date.now() } = {},
  ) {
    super();
    this.invite = { keyEpoch: 0, handovers: 0, ...invite };
    this.realtimeUrl = realtimeUrl;
    this.clientName = clientName;
    this.now = now;
    this.status = "connecting"; // connecting, live, reconnecting, ended
    this.endReason = null; // left, removed, gone, invalid, full
    this.memberId = null;
    this.ownerId = null;
    this.members = new Map(); // member ID -> {id, profile, fix, route, nowPlaying, heardAt}
    this.regroup = null;
    this.myState = null;
    this.stateChanged = false;
    this.resumeToken = null;
    this.socket = null;
    this.wake = null;
    // Who we are, and what we know of everyone else's keys.
    this.identity = null;
    this.counter = 0;
    this.keys = new Map(); // epoch -> convoy key
    this.retiredAt = new Map(); // epoch -> when a newer key replaced it
    this.epoch = this.invite.keyEpoch;
    this.cards = new Map(); // member ID -> [card]: one each, more only if two keys share an ID
    this.windows = new Map();
    this.removed = new Set();
    this.held = [];
    this.connected = []; // members connected to the server, longest in first
    this.sent = {};
    this.republish = true;
    this.outgoing = []; // events waiting to go out: {event, queuedAt}
    this.sendingEvents = false;
  }

  /** Connects, and keeps reconnecting with backoff until [leave] or the convoy ends. */
  async start() {
    this.identity = await newIdentity();
    this.keys.set(
      this.epoch,
      await convoyKey(this.epoch, this.invite.key, this.invite.convoyId),
    );
    this.ownerId = await memberIdOf(this.invite.ownerKey);
    let attempt = 0;
    while (this.status !== "ended") {
      const seated = await this.connectOnce();
      if (this.status === "ended") break;
      attempt = seated ? 1 : attempt + 1;
      this.setStatus("reconnecting");
      await this.pause(this.backoffMs(attempt));
    }
  }

  /** Our state for the others: {profile, position?} (a Position message). */
  setState(state) {
    this.myState = state;
    this.stateChanged = true;
  }

  /** Sends the others a quick message (QuickMessageKind). */
  sendQuickMessage(kind) {
    this.queueEvent({ quick_message: { kind } });
  }

  /** Asks everyone to meet at [point] ({lat, lon}), which shows here too, as ours. */
  dropRegroupPin(point, name) {
    const location = { lat_e7: Math.round(point.lat * 1e7), lon_e7: Math.round(point.lon * 1e7), name };
    this.queueEvent({ regroup_pin: { location } });
    this.regroup = { ...waypoint(location), by: this.memberId, mine: true };
    this.changed();
  }

  /** Takes the regroup point off this map. Only here: as in the app, the others keep theirs. */
  clearRegroup() {
    this.regroup = null;
    this.changed();
  }

  /** Skips the wait before the next reconnect, e.g. when the network comes back. */
  reconnectNow() {
    this.wake?.();
  }

  /** Leaves the convoy for good; as the owner (if it came to that), hands it over first. */
  async leave() {
    if (this.status === "ended") return;
    const socket = this.socket;
    this.end("left");
    if (socket?.readyState === 1) {
      await this.handOver(socket).catch(() => {});
      socket.send(encode(ClientMessage, { leave_convoy: {} }));
    }
    socket?.close();
  }

  // --- Internals ---

  backoffMs(attempt) {
    const exponential = Math.min(
      TIMING.maxBackoffMs,
      TIMING.minBackoffMs * 2 ** Math.min(attempt - 1, 16),
    );
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

  send(message, socket = this.socket) {
    if (socket?.readyState === 1) socket.send(encode(ClientMessage, message));
  }

  openingMessage() {
    if (this.resumeToken) return { resume: { resume_token: this.resumeToken } };
    return {
      join_convoy: {
        convoy_id: this.invite.convoyId,
        join_token: this.invite.joinToken,
        member_id: this.identity.memberId,
      },
    };
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
        timers.push(
          setInterval(() => this.send({ ping: {} }), TIMING.pingIntervalMs),
        );
        timers.push(
          setInterval(() => {
            if (this.now() - lastHeard > TIMING.silenceTimeoutMs)
              socket.close();
          }, 5_000),
        );
      };
      socket.onmessage = (event) => {
        lastHeard = this.now();
        let message;
        try {
          message = decode(ServerMessage, new Uint8Array(event.data));
        } catch {
          return;
        }
        // Frames open and check asynchronously, but must apply in order.
        queue = queue
          .then(() => this.handle(message, socket))
          .then((result) => {
            if (result === "seated" && !seated) {
              seated = true;
              this.republish = true; // the server may have lost what it kept of ours
              timers.push(
                setInterval(
                  () => this.publishState(socket),
                  TIMING.positionIntervalMs,
                ),
              );
              this.publishState(socket);
              this.sendEvents(socket);
            } else if (result === "close") {
              socket.close();
            }
          })
          .catch(() => {});
      };
      socket.onclose = () => {
        timers.forEach(clearInterval);
        timers = [];
        if (this.socket === socket) this.socket = null;
        resolve(seated);
      };
    });
  }

  /** Seals [content] as our next frame, under the current key unless [epoch] says another, and sends it. */
  async sendFrame(socket, kind, content, epoch = this.epoch) {
    const counter = ++this.counter;
    const frame = await sealFrame(
      this.invite.convoyId,
      this.keys.get(epoch),
      this.identity,
      counter,
      kind,
      content,
    );
    this.send({ publish: { frame } }, socket);
  }

  /**
   * Events go out once we're in the convoy, in order, unless they've waited too long by then (as
   * the app's): a quick message sent while reconnecting still arrives, a stale one doesn't.
   */
  queueEvent(event) {
    const now = this.now();
    this.outgoing.push({ event: { ...event, sent_ms: BigInt(now) }, queuedAt: now });
    if (this.outgoing.length > MAX_QUEUED_EVENTS) this.outgoing.shift();
    this.sendEvents();
  }

  async sendEvents(socket = this.socket) {
    if (this.sendingEvents || this.status !== "live" || socket?.readyState !== 1) return;
    this.sendingEvents = true;
    try {
      while (this.outgoing.length && this.socket === socket) {
        const { event, queuedAt } = this.outgoing.shift();
        if (this.now() - queuedAt <= MAX_EVENT_AGE_MS)
          await this.sendFrame(socket, FrameKind.EVENT, encode(EventContent, event));
      }
    } finally {
      this.sendingEvents = false;
    }
  }

  /** Our card when it changed (or, without a position, as a sign of life), and our position when due. */
  async publishState(socket) {
    if (this.memberId === null || this.publishing) return;
    this.publishing = true;
    try {
      const state = this.myState ?? { profile: {} };
      const now = this.now();
      const all = this.republish;
      this.republish = false;
      const changed = this.stateChanged;
      this.stateChanged = false;
      // Due again a little early, so the others never go [refreshMs] without hearing from us.
      const refresh = TIMING.refreshMs - TIMING.positionIntervalMs;
      const profile = JSON.stringify(state.profile);
      if (
        all ||
        profile !== this.sent.profile ||
        (!state.position && now - (this.sent.cardAt ?? 0) >= refresh)
      ) {
        await this.sendFrame(
          socket,
          FrameKind.CARD,
          cardContent(this.identity, state.profile),
        );
        this.sent.profile = profile;
        this.sent.cardAt = now;
      }
      if (
        state.position &&
        (all || changed || now - (this.sent.positionAt ?? 0) >= refresh)
      ) {
        await this.sendFrame(
          socket,
          FrameKind.POSITION,
          encode(Position, state.position),
        );
        this.sent.positionAt = now;
      }
    } finally {
      this.publishing = false;
    }
  }

  /** As the owner, before leaving: to whoever has been in the convoy longest (and isn't only watching, if anyone). */
  async handOver(socket) {
    if (!same(this.invite.ownerKey, this.identity?.signingKey)) return;
    const candidates = this.connected
      .map((id) => this.cards.get(id)?.at(-1))
      .filter(Boolean);
    const next =
      candidates.find((card) => !card.profile?.watching) ?? candidates[0];
    if (!next) return;
    const number = this.invite.handovers + 1;
    await this.sendFrame(
      socket,
      FrameKind.OWNER,
      encode(OwnerStatement, {
        handover: { number, new_owner_key: next.signing_key },
      }),
    );
    const signature = await sign(
      this.identity,
      ownerSignatures.handover(this.invite.convoyId, number, next.signing_key),
    );
    this.send(
      { hand_over: { number, new_owner_key: next.signing_key, signature } },
      socket,
    );
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
    if (message.relay) await this.receive(message.relay.frame, this.now());
    if (message.member_joined) {
      const id = message.member_joined.member_id;
      this.connected = [...this.connected.filter((other) => other !== id), id];
      if (!this.members.has(id)) this.members.set(id, { id });
      this.notice({ kind: "joined", member: this.members.get(id) });
      this.changed();
    }
    if (message.member_left) {
      const { member_id: id, removed } = message.member_left;
      if (id === this.identity.memberId) {
        this.end(removed ? "removed" : "left");
        return "close";
      }
      const member = this.members.get(id);
      this.connected = this.connected.filter((other) => other !== id);
      this.forget(id);
      this.notice({ kind: "left", member, removed });
      this.changed();
    }
    if (message.error) return this.onError(message.error, socket);
    return null;
  }

  async onJoined(joined) {
    this.resumeToken = joined.resume_token;
    if (joined.join_token?.length) this.invite.joinToken = joined.join_token;
    this.connected = [...joined.members];
    const before = this.members;
    this.members = new Map(
      joined.members.map((id) => [id, before.get(id) ?? { id }]),
    );
    // What the server kept, oldest first: cards, the latest positions, and the owner's statements.
    const now = this.now();
    for (const relay of joined.frames)
      await this.receive(relay.frame, now - Number(relay.age_ms ?? 0n));
    this.memberId = this.identity.memberId;
    this.status = "live";
    this.changed();
  }

  /** Takes in one frame, if it opens with a key we have and is signed by its sender's key, once. */
  async receive(frame, heardAt) {
    const header = frame && parseHeader(frame);
    if (
      !header ||
      header.sender === this.identity.memberId ||
      this.removed.has(header.sender)
    )
      return;
    const now = this.now();
    const key = [...this.keys.values()].find(
      (candidate) => candidate.tag === header.tag,
    );
    if (!key) return this.hold(frame, heardAt, now); // under a key we may get yet
    const retired = this.retiredAt.get(key.epoch);
    const current =
      key.epoch === this.epoch ||
      (retired !== undefined && now - retired <= TIMING.oldKeyGraceMs);
    if (this.windows.get(header.sender)?.isNew(header.counter) === false)
      return;
    const opened = await openFrame(this.invite.convoyId, key, frame);
    if (!opened) return;
    let card = null;
    let statement = null;
    try {
      if (header.kind === FrameKind.OWNER) {
        if (
          header.sender !== this.ownerId ||
          !(await opened.signedBy(this.invite.ownerKey))
        )
          return;
        statement = decode(OwnerStatement, opened.content);
      } else if (header.kind === FrameKind.CARD) {
        card = decode(Card, opened.content);
        if (
          card.signing_key?.length !== 32 ||
          (await memberIdOf(card.signing_key)) !== header.sender ||
          !(await opened.signedBy(card.signing_key))
        )
          return;
      } else {
        const theirs = this.cards.get(header.sender);
        if (!theirs) return this.hold(frame, heardAt, now); // their card is surely on its way
        let signed = false;
        for (const known of theirs)
          if ((signed = await opened.signedBy(known.signing_key))) break;
        if (!signed) return;
      }
    } catch {
      return; // content that doesn't decode
    }
    const rotation = statement?.key_rotation;
    if (
      rotation &&
      rotation.epoch > this.epoch &&
      rotation.keys.some((k) => k.member_id === this.identity.memberId) &&
      !this.ownerCard()
    ) {
      return this.hold(frame, heardAt, now); // the owner's card has the key to unwrap ours
    }
    if (!current) return;
    if (!this.windows.has(header.sender))
      this.windows.set(header.sender, new ReplayWindow());
    this.windows.get(header.sender).accept(header.counter);
    try {
      await this.apply(
        header.kind,
        header.sender,
        opened.content,
        card,
        statement,
        heardAt,
      );
    } catch {
      // content that doesn't decode
    }
  }

  async apply(kind, sender, content, card, statement, heardAt) {
    switch (kind) {
      case FrameKind.CARD: {
        const others = (this.cards.get(sender) ?? []).filter(
          (known) => !same(known.signing_key, card.signing_key),
        );
        this.cards.set(sender, [...others, card]);
        this.update(sender, heardAt, { profile: card.profile ?? {} });
        await this.releaseHeld();
        break;
      }
      case FrameKind.POSITION: {
        const fix = fixOf(decode(Position, content));
        const known = this.members.get(sender);
        if (known?.fix && fix.time < known.fix.time) return; // a late, older one changes nothing
        this.update(sender, heardAt, { fix });
        break;
      }
      case FrameKind.ROUTE: {
        const route = decode(RouteShare, content);
        this.update(sender, heardAt, {
          route: route.waypoints.length ? route : undefined,
        });
        break;
      }
      case FrameKind.SONG: {
        const song = decode(NowPlaying, content);
        this.update(sender, heardAt, {
          nowPlaying: song.title || song.artist || song.app ? song : undefined,
        });
        break;
      }
      case FrameKind.EVENT: {
        const event = decode(EventContent, content);
        if (this.now() - Number(event.sent_ms ?? 0n) > MAX_EVENT_AGE_MS) return; // old news, or a replay
        this.update(sender, heardAt, {});
        const member = this.members.get(sender);
        if (event.quick_message)
          this.notice({
            kind: "quick",
            member,
            quick: event.quick_message.kind ?? 0,
          });
        if (event.regroup_pin?.location) {
          this.regroup = {
            ...waypoint(event.regroup_pin.location),
            by: sender,
          };
          this.notice({ kind: "regroup", member, regroup: this.regroup });
        }
        break;
      }
      case FrameKind.OWNER:
        await this.applyOwner(statement);
        break;
      default:
        return;
    }
    this.changed();
  }

  async applyOwner(statement) {
    const handover = statement.handover;
    // Only the next one: an old handover sent again changes nothing.
    if (
      handover &&
      handover.number === this.invite.handovers + 1 &&
      handover.new_owner_key?.length === 32
    ) {
      this.invite.handovers = handover.number;
      this.invite.ownerKey = handover.new_owner_key;
      this.ownerId = await memberIdOf(handover.new_owner_key);
    }
    const rotation = statement.key_rotation;
    if (!rotation || (rotation.epoch ?? 0) <= this.epoch) return;
    for (const gone of rotation.removed) {
      this.removed.add(gone);
      this.forget(gone);
    }
    const mine = rotation.keys.find(
      (wrapped) => wrapped.member_id === this.identity.memberId,
    );
    const owner = this.ownerCard();
    if (!mine || !owner) return; // ours is in another part
    const raw = await unwrapKey(
      this.invite.convoyId,
      rotation.epoch,
      this.identity,
      owner.exchange_key,
      mine.key,
    );
    if (raw?.length !== 32) return;
    this.keys.set(
      rotation.epoch,
      await convoyKey(rotation.epoch, raw, this.invite.convoyId),
    );
    for (const epoch of this.keys.keys())
      if (epoch !== rotation.epoch && !this.retiredAt.has(epoch))
        this.retiredAt.set(epoch, this.now());
    this.epoch = rotation.epoch;
    this.invite.key = raw;
    this.invite.keyEpoch = rotation.epoch;
    // What the server keeps of ours is under the old key, which newcomers won't have.
    this.republish = true;
    await this.releaseHeld();
  }

  ownerCard() {
    return this.cards
      .get(this.ownerId)
      ?.find((card) => same(card.signing_key, this.invite.ownerKey));
  }

  update(id, heardAt, changes) {
    const known = this.members.get(id) ?? { id };
    this.members.set(id, {
      ...known,
      ...changes,
      heardAt: Math.max(known.heardAt ?? heardAt, heardAt),
    });
  }

  forget(id) {
    this.cards.delete(id);
    this.windows.delete(id);
    this.members.delete(id);
  }

  hold(frame, heardAt, now) {
    this.held.push({ frame, heardAt, heldAt: now });
    if (this.held.length > MAX_HELD) this.held.shift();
  }

  /** Tries the held frames again, now that a card or a key arrived; those held too long go. */
  async releaseHeld() {
    if (!this.held.length) return;
    const now = this.now();
    const again = this.held;
    this.held = [];
    for (const held of again)
      if (now - held.heldAt <= TIMING.holdMs)
        await this.receive(held.frame, held.heardAt);
  }

  async onError(error, socket) {
    if (this.memberId === null || this.status !== "live") {
      // The answer to our opening message.
      switch (error.code) {
        case ErrorCode.NOT_FOUND:
          if (this.resumeToken) {
            // Our seat expired while we were away: join again with the invite.
            this.resumeToken = null;
            this.send(this.openingMessage(), socket);
            return null;
          }
          this.end("gone");
          return "close";
        case ErrorCode.CONFLICT:
          // Someone in the convoy has our member ID: be someone else (a new key, a new counter).
          this.identity = await newIdentity();
          this.counter = 0;
          this.send(this.openingMessage(), socket);
          return null;
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

/** A fix as the page draws it, from a Position. */
export function fixOf(position) {
  return {
    point: {
      lat: (position.lat_e7 ?? 0) / 1e7,
      lon: (position.lon_e7 ?? 0) / 1e7,
    },
    speed: (position.speed_cms ?? 0) / 100,
    heading: position.heading_deg ?? 0,
    time: Number(position.fix_time_ms ?? 0n),
  };
}

export function waypoint(w) {
  return {
    lat: (w.lat_e7 ?? 0) / 1e7,
    lon: (w.lon_e7 ?? 0) / 1e7,
    name: w.name ?? "",
  };
}

/** Our position, for a POSITION frame. */
export function position(fix, accuracy) {
  return {
    lat_e7: Math.round(fix.point.lat * 1e7),
    lon_e7: Math.round(fix.point.lon * 1e7),
    speed_cms: Math.max(0, Math.round((fix.speed ?? 0) * 100)),
    heading_deg: Math.round((((fix.heading ?? 0) % 360) + 360) % 360) % 360,
    accuracy_m: Math.max(0, Math.round(accuracy ?? 0)),
    fix_time_ms: BigInt(Math.round(fix.time)),
  };
}
