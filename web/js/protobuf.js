// A small Protocol Buffers (proto3) codec for FoxNav's convoy messages, as defined in
// protocol/proto/foxnav/convoy/v0/convoy.proto. Schemas map field numbers to [name, type]; a type is
// a scalar name or another schema. 64-bit integers are BigInts (member IDs use all 63 bits).
// Fields the schema doesn't know are skipped, as protobuf requires.

const U64 = "u64";
const I64 = "i64";
const U32 = "u32";
const S32 = "s32";
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
    case U32: case S32: case ENUM: case FLOAT: return value === 0;
    case BOOL: return value === false;
    case STRING: case BYTES: return value.length === 0;
    default: return false; // messages are written whenever they're set, even when empty
  }
}

function writeField(writer, field, type, value) {
  switch (type) {
    case U64: case I64:
      writer.tag(field, 0);
      writer.varint(value);
      return;
    case U32: case ENUM:
      writer.tag(field, 0);
      writer.varint(value >>> 0);
      return;
    case BOOL:
      writer.tag(field, 0);
      writer.varint(value ? 1 : 0);
      return;
    case S32:
      writer.tag(field, 0);
      writer.varint(((value << 1) ^ (value >> 31)) >>> 0);
      return;
    case FLOAT: {
      writer.tag(field, 5);
      const bytes = new Uint8Array(4);
      new DataView(bytes.buffer).setFloat32(0, value, true);
      writer.push(bytes);
      return;
    }
    case STRING: case BYTES: {
      const bytes = type === STRING ? utf8.encode(value) : value;
      writer.tag(field, 2);
      writer.varint(bytes.length);
      writer.push(bytes);
      return;
    }
    default: {
      const bytes = encode(type, value);
      writer.tag(field, 2);
      writer.varint(bytes.length);
      writer.push(bytes);
    }
  }
}

/** The message [value] (a plain object) encoded with [schema]. */
export function encode(schema, value) {
  const writer = new Writer();
  for (const [number, [name, type]] of Object.entries(schema)) {
    const field = Number(number);
    const item = value[name];
    if (item === undefined || item === null) continue;
    if (type.repeated !== undefined) {
      for (const element of item) writeField(writer, field, type.repeated, element);
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

const WIRE = { [U64]: 0, [I64]: 0, [U32]: 0, [S32]: 0, [BOOL]: 0, [ENUM]: 0, [FLOAT]: 5, [STRING]: 2, [BYTES]: 2 };

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
    case FLOAT: {
      const bytes = reader.take(4);
      return new DataView(bytes.buffer, bytes.byteOffset, 4).getFloat32(0, true);
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

export const Channel = Object.freeze({ STATE: 1, EVENT: 2, KEY: 3 });

export const ErrorCode = Object.freeze({ BAD_REQUEST: 1, NOT_FOUND: 2, FORBIDDEN: 3, CONVOY_FULL: 4, RATE_LIMITED: 5 });

export const QuickMessageKind = Object.freeze({
  FUEL: 1, BREAK: 2, PULL_OVER: 3, POLICE_AHEAD: 4, HAZARD_AHEAD: 5, SLOW_DOWN: 6, ALL_GOOD: 7,
});

const Empty = {};
export const Sealed = { 1: ["key_epoch", U32], 2: ["nonce", BYTES], 3: ["ciphertext", BYTES] };
const Member = { 1: ["member_id", U64], 2: ["last_state", Sealed], 3: ["last_state_age_ms", U64] };
export const Relay = { 1: ["sender", U64], 2: ["channel", ENUM], 3: ["payload", Sealed], 4: ["server_time_ms", I64] };

export const ClientMessage = {
  1: ["hello", { 1: ["client", STRING] }],
  2: ["create_convoy", Empty],
  3: ["join_convoy", { 1: ["convoy_id", BYTES], 2: ["join_token", BYTES] }],
  4: ["resume", { 1: ["resume_token", BYTES] }],
  5: ["publish", { 1: ["channel", ENUM], 2: ["payload", Sealed] }],
  6: ["leave_convoy", Empty],
  7: ["remove_member", { 1: ["member_id", U64] }],
  8: ["ping", Empty],
};

export const ServerMessage = {
  1: ["welcome", { 1: ["server", STRING], 2: ["max_convoy_size", U32] }],
  2: ["convoy_created", { 1: ["convoy_id", BYTES], 2: ["join_token", BYTES] }],
  3: ["joined", {
    1: ["convoy_id", BYTES],
    2: ["member_id", U64],
    3: ["resume_token", BYTES],
    4: ["members", repeated(Member)],
    5: ["owner", U64],
    6: ["join_token", BYTES],
    7: ["key_history", repeated(Relay)],
  }],
  4: ["relay", Relay],
  5: ["member_joined", { 1: ["member_id", U64] }],
  6: ["member_left", { 1: ["member_id", U64], 2: ["removed", BOOL], 3: ["new_owner", U64] }],
  7: ["pong", Empty],
  8: ["error", { 1: ["code", ENUM], 2: ["message", STRING] }],
  9: ["invite_changed", { 1: ["join_token", BYTES] }],
};

const Waypoint = { 1: ["lat_e7", S32], 2: ["lon_e7", S32], 3: ["name", STRING] };

export const MemberState = {
  1: ["profile", {
    1: ["display_name", STRING],
    2: ["car", { 1: ["make", STRING], 2: ["model", STRING], 3: ["color_rgb", U32], 4: ["body_style", ENUM] }],
    3: ["watching", BOOL],
    4: ["role", ENUM], // 1 leads the way, 2 sweeps up at the back
  }],
  2: ["position", {
    1: ["lat_e7", S32],
    2: ["lon_e7", S32],
    3: ["speed_mps", FLOAT],
    4: ["heading_deg", FLOAT],
    5: ["accuracy_m", FLOAT],
    6: ["fix_time_ms", I64],
  }],
  3: ["route", { 1: ["waypoints", repeated(Waypoint)] }],
  4: ["now_playing", { 1: ["title", STRING], 2: ["artist", STRING], 3: ["app", STRING] }],
};

export const ConvoyPayload = {
  1: ["state", MemberState],
  2: ["quick_message", { 1: ["kind", ENUM] }],
  3: ["regroup_pin", { 1: ["location", Waypoint] }],
  4: ["key_rotation", { 1: ["epoch", U32], 2: ["key", BYTES] }],
};
