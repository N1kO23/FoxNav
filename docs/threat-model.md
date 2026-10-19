# Threat model: convoys

What convoy encryption ([ADR 0003](adr/0003-convoy-encryption.md), [ADR 0007](adr/0007-convoy-member-keys.md), [spec §2.3](../protocol/SPEC.md#23-end-to-end-encryption)) protects, and what it deliberately doesn't. This covers protocol v0, as ADR 0007 changed it. Reports and data packs are at the end.

## What's at stake

- Live position and speed of each member.
- Who they are: display name and car, and the role they took on (leading, at the back).
- What their music is playing, for members who choose to share it.
- Where the convoy is going: the shared route and regroup pins.
- Who is driving with whom.

## Who might want it, and what they get

| Who | Can they read positions, speeds, names, routes? | What they can still learn or do |
|---|---|---|
| Server operator | No. Frames are encrypted with a key the server never sees. | Metadata (see below). Dropping or delaying frames, but not changing, forging or moving them: every frame is signed by its sender. |
| Someone on the network (Wi-Fi, ISP) | No. Everything goes over TLS. | That you talk to a FoxNav server, and how much. |
| Someone in radio range, once convoys run over LoRa | No. Frames are encrypted the same way over the radio; Meshtastic's own encryption isn't relied on. | Radio metadata (see below). Recording frames, to read them if that key's invite ever reaches them. |
| Operator of the invite-link domain | No for the app. Links open in the app, and the fragment holding the key is never sent to any server. | Invite links point at the convoy page of the convoy's own server (spec §2.5). That page *can* read the fragment: whoever follows in a browser trusts the server that serves the page, like any end-to-end encrypted web app. App users don't. |
| Anyone the invite link is forwarded to | Yes. The link is the key. | Everything a member can see, until the owner removes them. |
| Current members | Yes, by design. | Not sending as another member: every frame is signed by its sender. A member forwarding frames between a radio and the server (a gateway) can drop or delay them, like a server. |
| Removed members | No, not even with a malicious server's help or over a radio. The owner sends the new key to each remaining member separately, never under the old key. | With a malicious server's help, a way back in despite the new join token, but without the key. Over a radio, the radio metadata, like anyone in range. |

## Integrity

- **Moving frames.** A frame is bound to its convoy, kind and sender by the encryption's associated data and by the sender's signature. A server or gateway that moves it elsewhere makes the check fail.
- **Impersonation.** Every frame is signed by its sender's own key, which never leaves their phone. Nobody else can send as them: not another member, not the server, not a gateway.
  - Member IDs are derived from keys, and frames are checked against keys, so a made-up card with someone else's ID gets nowhere.
  - Display names are still whatever members call themselves: someone can join as "Alice". Watch who joins.
- **The owner.** The invite names the owner by their signing key. Removals, key rotations and handovers count only when the owner signs them, and the server checks the same signatures before it removes anyone.
- **Replay.** Each sender numbers their frames. Receivers accept each number once, so a frame replayed, or arriving over both a radio and the server, counts once.
  - Positions carry their fix time: an old one shows as old.
  - Events carry the time they were sent, and receivers drop those more than 5 minutes old, so a newcomer can't be fed old ones either.
  - A server or gateway can still delay frames within those limits, or withhold them.

## Metadata the server sees

- IP addresses and connection times of members.
- How many members a convoy has, and when they join and leave.
- Each frame's kind, sender and number, and its timing and size. Members send about once per second while their position changes and every 5 s otherwise, so the server can tell moving from parked.
- Which member forwards whose frames, for gateways.

Possible later mitigations: sending at a constant rate, padding frames, and connecting through a proxy or Tor.

## Over a radio

Once convoys run over LoRa radios (Meshtastic, [ADR 0007](adr/0007-convoy-member-keys.md)), anyone with a receiver in range hears every frame. They can't read or forge them, but:

- **Metadata is broadcast.** Meshtastic's packet headers aren't encrypted: they carry each radio's node number. With the frames' own headers (kind, convoy tag, member ID, number) and their timing and size, a listener can tell how many cars there are, which radio sends what and when, and roughly where the radios are. The convoy tag changes with every key rotation.
- **Recordings last.** Anyone in range can record frames. Whoever later gets an invite with that key can read them: there's no forward secrecy. The key is discarded when the convoy ends, and a rotation starts a new one.
- **Meshtastic's encryption adds nothing to rely on.** A Meshtastic channel's key is shared by every radio on it, and the default channel's key is public.

## Out of scope

- A compromised phone. Malware on a member's phone sees what that member sees.
- Members screenshotting or sharing what they see.

## How the app handles the key

- The convoy key exists only in memory while you're in the convoy, and inside the invite link. So do your identity's keys, without the link; a new convoy gets a new identity.
- The app never logs invite links. `Invite.toString()` leaves the key out.
- A copied invite is marked sensitive, so Android leaves it out of the clipboard preview.

## The convoy page

Following a convoy in a browser ([`web/`](../web/), spec §2.5) uses the same protocol and encryption as the app; the keys never leave the browser. What changes is whom you trust:

- **The page comes from the convoy's server.** An honest server's page can't read anything the app couldn't, but a malicious operator could serve a page that sends the key elsewhere. People who follow in a browser therefore trust the server operator with their convoy; people in the app don't. Running your own server, or using one you trust, settles it.
- **Cross-site scripting would leak the key**, so the reference server sends a strict content security policy (scripts only from itself, nothing inline), and the page puts other people's names and messages in only as text.
- **The page shares a position only when the person ticks the box**, only while it's open, and only with a secure connection.
- **Nothing is stored on the server**, as with the app. The browser remembers the name, colour and the sharing choice; the invite stays in the address bar, as it would in the app's link.

## Advice for users

- Share invite links only in private chats. Anyone with the link can join.
- Watch who joins. Remove anyone you don't recognise.
- End the convoy after the drive. The key is discarded with it.

## Possible upgrades

- **Forward secrecy**, with MLS ([RFC 9420](https://www.rfc-editor.org/rfc/rfc9420)) or by ratcheting the convoy key, so recorded frames can't be read with an invite that leaks later. ADR 0007 says why not yet.
- **Co-owners**, so a convoy whose owner disappeared without handing over can still remove members.
- **Join approval and expiring invite tokens.**

## Reports and data packs

Neither is encrypted, because neither is private: they're meant for everyone.

- **Data packs** are downloaded whole, per region, and matched on the phone. The server learns that someone wanted Finland's cameras, not where they drive.
- **Reports** are public. A report says what, where, which way and when, and nothing about who: no device ID, no account. The server keeps the reporter's address only in memory, for rate limits (5 reports and 30 votes per 10 minutes), and rounds positions to about a metre.
- **Still, a report is a position at a time.** Someone who knows you reported from a road at a moment learns little new. But a server operator who logs connections (or a network observer) could tie a report to an address. The reference server doesn't log addresses with reports; a proxy in front of it might.
- **The feed is the same for everyone**, so polling it reveals nothing about where you are.
- **Abuse:** fake reports are possible. Rate limits, expiry and "not there" votes limit the damage. Trust scores per device would need device identity, which FoxNav avoids for now.
