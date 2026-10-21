// Convoy invites, as the app's Invite (shared/.../convoy/Invite.kt): everything is in the link's
// fragment, which browsers never send to a server.
//   ...#v=0&s=<server>&c=<convoy ID>&t=<join token>&k=<key>&o=<owner's key>[&e=<key epoch>][&h=<handovers>]

const VERSION = "0";
const KEY_SIZE = 32;

export function base64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

export function fromBase64Url(text) {
  if (!/^[A-Za-z0-9_-]*={0,2}$/.test(text)) return null;
  try {
    const binary = atob(
      text.replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, ""),
    );
    return Uint8Array.from(binary, (char) => char.charCodeAt(0));
  } catch {
    return null;
  }
}

/** The invite in [link] (a whole URL or just its fragment), or null if there isn't a valid one. */
export function parseInvite(link) {
  const hash = link.includes("#") ? link.slice(link.indexOf("#") + 1) : link;
  const fields = new Map();
  for (const part of hash.split("&")) {
    const at = part.indexOf("=");
    if (at > 0) fields.set(part.slice(0, at), part.slice(at + 1));
  }
  if (fields.get("v") !== VERSION) return null;
  let server;
  try {
    server = decodeURIComponent(fields.get("s") ?? "").trim();
  } catch {
    return null;
  }
  const convoyId = fromBase64Url(fields.get("c") ?? "");
  const joinToken = fromBase64Url(fields.get("t") ?? "");
  const key = fromBase64Url(fields.get("k") ?? "");
  const ownerKey = fromBase64Url(fields.get("o") ?? "");
  const keyEpoch = count(fields.get("e"));
  const handovers = count(fields.get("h"));
  if (
    !server ||
    convoyId?.length !== 16 ||
    !joinToken?.length ||
    key?.length !== KEY_SIZE ||
    ownerKey?.length !== KEY_SIZE
  )
    return null;
  if (keyEpoch === null || handovers === null) return null;
  return { server, convoyId, joinToken, key, ownerKey, keyEpoch, handovers };
}

/** A count from the fragment: 0 when it's left out, null when it isn't one. */
function count(text) {
  if (text === undefined) return 0;
  const value = Number(text);
  return Number.isInteger(value) && value >= 0 && /^\d+$/.test(text)
    ? value
    : null;
}

/** The fragment for [invite], without the "#". */
export function inviteFragment(invite) {
  return (
    `v=${VERSION}&s=${encodeURIComponent(invite.server)}&c=${base64Url(invite.convoyId)}` +
    `&t=${base64Url(invite.joinToken)}&k=${base64Url(invite.key)}&o=${base64Url(invite.ownerKey)}` +
    (invite.keyEpoch ? `&e=${invite.keyEpoch}` : "") +
    (invite.handovers ? `&h=${invite.handovers}` : "")
  );
}

/** The server's base URL: a bare address means https, as in the app. */
export function serverBase(server) {
  const trimmed = server.trim().replace(/\/+$/, "");
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)
    ? trimmed
    : `https://${trimmed}`;
}
