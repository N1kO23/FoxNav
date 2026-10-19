// A small Protocol Buffers (proto3) codec for FoxNav's convoy messages, as defined in
// protocol/proto/foxnav/convoy/v0/convoy.proto. Schemas map field numbers to [name, type]; a type is
// a scalar name or another schema. 64-bit integers are BigInts; member IDs are fixed32 Numbers.
// Repeated numbers are packed, as proto3 writes them. Fields the schema doesn't know are skipped,
// as protobuf requires.

const U64 = "u64";
const I64 = "i64";
const U32 = "u32";
const S32 = "s32";
const F32 = "f32";
const SF32 = "sf32";
const BOOL = "bool";
const FLOAT = "float";
const STRING = "string";
const BYTES = "bytes";
const ENUM = "enum";

const repeated = (type) => ({ repeated: type });

const utf8 = new TextEncoder();
const fromUtf8 = new TextDecoder();

class Writer {
  constructor() {
    this.parts = [];
    this.length = 0;
  }

  push(bytes) {
    this.parts.push(bytes);
    this.length += bytes.length;
  }

  varint(value) {
    let v = BigInt.asUintN(64, BigInt(value));
    const out = [];
    while (v >= 0x80n) {
      out.push(Number(v & 0x7fn) | 0x80);
      v >>= 7n;
    }
    out.push(Number(v));
    this.push(Uint8Array.from(out));
  }

  tag(field, wire) {
    this.varint((field << 3) | wire);
  }

  finish() {
    const out = new Uint8Array(this.length);
    let at = 0;
    for (const part of this.parts) {
      out.set(part, at);
      at += part.length;
    }
    return out;
  }
}

function isDefault(type, value) {
  switch (type) {
    case U64: case I64: return BigInt(value) === 0n;
    case U32: case S32: case F32: case SF32: case ENUM: case FLOAT: return value === 0;
    case BOOL: return value === false;
    case STRING: case BYTES: return value.length === 0;
    default: return false; // messages are written whenever they're set, even when empty
  }
}

/** Numbers, which repeated fields pack into one length-delimited field. */
const packable = (type) => typeof type === "string" && type !== STRING && type !== BYTES;

/** [value] without its tag. */
function writeValue(writer, type, value) {
  switch (type) {
    case U64: case I64:
      writer.varint(value);
      return;
    case U32: case ENUM:
      writer.varint(value >>> 0);
      return;
    case BOOL:
      writer.varint(value ? 1 : 0);
      return;
    case S32:
      writer.varint(((value << 1) ^ (value >> 31)) >>> 0);
      return;
    case F32: case SF32: case FLOAT: {
      const bytes = new Uint8Array(4);
      const view = new DataView(bytes.buffer);
      if (type === FLOAT) view.setFloat32(0, value, true);
      else if (type === F32) view.setUint32(0, value >>> 0, true);
      else view.setInt32(0, value | 0, true);
      writer.push(bytes);
      return;
    }
    default: {
      const bytes = type === STRING ? utf8.encode(value) : type === BYTES ? value : encode(type, value);
      writer.varint(bytes.length);
      writer.push(bytes);
    }
  }
}

function writeField(writer, field, type, value) {
  writer.tag(field, WIRE[type] ?? 2);
  writeValue(writer, type, value);
}

/** The message [value] (a plain object) encoded with [schema]. */
export function encode(schema, value) {
  const writer = new Writer();
  for (const [number, [name, type]] of Object.entries(schema)) {
    const field = Number(number);
    const item = value[name];
    if (item === undefined || item === null) continue;
    if (type.repeated !== undefined) {
      if (packable(type.repeated)) {
        if (!item.length) continue;
        const packed = new Writer();
        for (const element of item) writeValue(packed, type.repeated, element);
        writer.tag(field, 2);
        writer.varint(packed.length);
        writer.push(packed.finish());
      } else {
        for (const element of item) writeField(writer, field, type.repeated, element);
      }
    } else if (!isDefault(type, item)) {
      writeField(writer, field, type, item);
    }
  }
  return writer.finish();
}

class Reader {
  constructor(bytes) {
    this.bytes = bytes;
    this.at = 0;
  }

  get done() {
    return this.at >= this.bytes.length;
  }

  varint() {
    let result = 0n;
    let shift = 0n;
    for (;;) {
      if (this.at >= this.bytes.length) throw new Error("truncated varint");
      const byte = this.bytes[this.at++];
      result |= BigInt(byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) return result;
      shift += 7n;
      if (shift > 63n) throw new Error("varint too long");
    }
  }

  take(length) {
    if (this.at + length > this.bytes.length) throw new Error("truncated field");
    const out = this.bytes.subarray(this.at, this.at + length);
    this.at += length;
    return out;
  }

  skip(wire) {
    switch (wire) {
      case 0: this.varint(); return;
      case 1: this.take(8); return;
      case 2: this.take(Number(this.varint())); return;
      case 5: this.take(4); return;
      default: throw new Error(`unknown wire type ${wire}`);
    }
  }
}

const WIRE = { [U64]: 0, [I64]: 0, [U32]: 0, [S32]: 0, [BOOL]: 0, [ENUM]: 0, [F32]: 5, [SF32]: 5, [FLOAT]: 5, [STRING]: 2, [BYTES]: 2 };

function readValue(reader, type) {
  switch (type) {
    case U64: return reader.varint();
    case I64: return BigInt.asIntN(64, reader.varint());
    case U32: case ENUM: return Number(reader.varint() & 0xffffffffn);
    case BOOL: return reader.varint() !== 0n;
    case S32: {
      const n = Number(reader.varint() & 0xffffffffn);
      return (n >>> 1) ^ -(n & 1);
    }
    case FLOAT: case F32: case SF32: {
      const bytes = reader.take(4);
      const view = new DataView(bytes.buffer, bytes.byteOffset, 4);
      return type === FLOAT ? view.getFloat32(0, true) : type === F32 ? view.getUint32(0, true) : view.getInt32(0, true);
    }
    case STRING: return fromUtf8.decode(reader.take(Number(reader.varint())));
    case BYTES: return reader.take(Number(reader.varint())).slice();
    default: return decode(type, reader.take(Number(reader.varint())));
  }
}

/** [bytes] decoded with [schema]: an object with the fields that were set; repeated ones are always arrays. */
export function decode(schema, bytes) {
  const value = {};
  for (const [name, type] of Object.values(schema)) if (type.repeated !== undefined) value[name] = [];
  const reader = new Reader(bytes);
  while (!reader.done) {
    const tag = reader.varint();
    const field = Number(tag >> 3n);
    const wire = Number(tag & 7n);
    const spec = schema[field];
    if (!spec) {
      reader.skip(wire);
      continue;
    }
    const [name, type] = spec;
    const element = type.repeated ?? type;
    if (type.repeated !== undefined && packable(element) && wire === 2) {
      const packed = new Reader(reader.take(Number(reader.varint())));
      while (!packed.done) value[name].push(readValue(packed, element));
      continue;
    }
    if ((WIRE[element] ?? 2) !== wire) {
      reader.skip(wire);
      continue;
    }
    if (type.repeated !== undefined) value[name].push(readValue(reader, element));
    else value[name] = readValue(reader, element);
  }
  return value;
}

// --- The convoy protocol (protocol/SPEC.md §2) ---

export const ErrorCode = Object.freeze({ BAD_REQUEST: 1, NOT_FOUND: 2, FORBIDDEN: 3, CONVOY_FULL: 4, RATE_LIMITED: 5, CONFLICT: 6 });

/** A frame's first byte (spec §2.3). */
export const FrameKind = Object.freeze({ CARD: 1, POSITION: 2, ROUTE: 3, SONG: 4, EVENT: 5, OWNER: 6 });

export const QuickMessageKind = Object.freeze({
  FUEL: 1, BREAK: 2, PULL_OVER: 3, POLICE_AHEAD: 4, HAZARD_AHEAD: 5, SLOW_DOWN: 6, ALL_GOOD: 7,
});

const Empty = {};
export const Relay = { 1: ["frame", BYTES], 2: ["from", F32], 3: ["server_time_ms", I64], 4: ["age_ms", U64] };

export const ClientMessage = {
  1: ["hello", { 1: ["client", STRING] }],
  2: ["create_convoy", { 1: ["member_id", F32], 2: ["owner_key", BYTES] }],
  3: ["join_convoy", { 1: ["convoy_id", BYTES], 2: ["join_token", BYTES], 3: ["member_id", F32] }],
  4: ["resume", { 1: ["resume_token", BYTES] }],
  5: ["publish", { 1: ["frame", BYTES] }],
  6: ["leave_convoy", Empty],
  7: ["remove_member", { 1: ["member_id", F32], 2: ["epoch", U32], 3: ["signature", BYTES] }],
  8: ["ping", Empty],
  9: ["hand_over", { 1: ["number", U32], 2: ["new_owner_key", BYTES], 3: ["signature", BYTES] }],
};

export const ServerMessage = {
  1: ["welcome", { 1: ["server", STRING], 2: ["max_convoy_size", U32] }],
  2: ["convoy_created", { 1: ["convoy_id", BYTES], 2: ["join_token", BYTES] }],
  3: ["joined", {
    1: ["convoy_id", BYTES],
    2: ["member_id", F32],
    3: ["resume_token", BYTES],
    4: ["members", repeated(F32)],
    6: ["join_token", BYTES],
    7: ["frames", repeated(Relay)],
  }],
  4: ["relay", Relay],
  5: ["member_joined", { 1: ["member_id", F32] }],
  6: ["member_left", { 1: ["member_id", F32], 2: ["removed", BOOL] }],
  7: ["pong", Empty],
  8: ["error", { 1: ["code", ENUM], 2: ["message", STRING] }],
  9: ["invite_changed", { 1: ["join_token", BYTES] }],
};

// Frame contents, one per kind.

const Waypoint = { 1: ["lat_e7", SF32], 2: ["lon_e7", SF32], 3: ["name", STRING] };

export const Profile = {
  1: ["display_name", STRING],
  2: ["car", { 1: ["make", STRING], 2: ["model", STRING], 3: ["color_rgb", U32], 4: ["body_style", ENUM] }],
  3: ["watching", BOOL],
  4: ["role", ENUM], // 1 leads the way, 2 sweeps up at the back
};

export const Card = { 1: ["signing_key", BYTES], 2: ["exchange_key", BYTES], 3: ["profile", Profile] };

export const Position = {
  1: ["lat_e7", SF32],
  2: ["lon_e7", SF32],
  3: ["speed_cms", U32],
  4: ["heading_deg", U32],
  5: ["accuracy_m", U32],
  6: ["fix_time_ms", I64],
};

export const RouteShare = { 1: ["waypoints", repeated(Waypoint)] };

export const NowPlaying = { 1: ["title", STRING], 2: ["artist", STRING], 3: ["app", STRING] };

export const Event = {
  1: ["sent_ms", I64],
  2: ["quick_message", { 1: ["kind", ENUM] }],
  3: ["regroup_pin", { 1: ["location", Waypoint] }],
};

export const OwnerStatement = {
  1: ["key_rotation", {
    1: ["epoch", U32],
    2: ["removed", repeated(F32)],
    3: ["part", U32],
    4: ["parts", U32],
    5: ["keys", repeated({ 1: ["member_id", F32], 2: ["key", BYTES] })],
  }],
  2: ["handover", { 1: ["number", U32], 2: ["new_owner_key", BYTES] }],
};
