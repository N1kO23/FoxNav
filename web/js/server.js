// The convoy's server, from its discovery document (protocol/SPEC.md §1).

import { serverBase } from "./invite.js";

/** The server's discovery document, with every URL in it made absolute. */
export async function discover(server) {
  const url = `${serverBase(server)}/.well-known/foxnav`;
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  if (!response.ok) throw new Error(`${url} answered HTTP ${response.status}`);
  const document = await response.json();
  const absolute = (value) => (value ? new URL(value, url).href : null);
  const realtime = absolute(document.realtime);
  return {
    name: document.name ?? server,
    privacy: absolute(document.privacy),
    // A relative realtime URL takes ws or wss to match the document's http or https.
    realtime: realtime && realtime.replace(/^http(s?):/, "ws$1:"),
    styles: Object.fromEntries(Object.entries(document.styles ?? {}).map(([theme, style]) => [theme, absolute(style)])),
    features: document.features ?? [],
  };
}
