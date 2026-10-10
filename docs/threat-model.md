# Threat model: convoys

What convoy encryption ([ADR 0003](adr/0003-convoy-encryption.md), [spec §2.3](../protocol/SPEC.md#23-end-to-end-encryption)) protects, and what it deliberately doesn't. This covers protocol v0. Reports and data packs are at the end.

## What's at stake

- Live position and speed of each member.
- Who they are: display name and car.
- Where the convoy is going: the shared route and regroup pins.
- Who is driving with whom.

## Who might want it, and what they get

| Who | Can they read positions, speeds, names, routes? | What they can still learn or do |
|---|---|---|
| Server operator | No. Payloads are encrypted with a key the server never sees. | Metadata (see below). Dropping or delaying messages. |
| Someone on the network (Wi-Fi, ISP) | No. Everything goes over TLS. | That you talk to a FoxNav server, and how much. |
| Operator of the invite-link domain | No for the app. Links open in the app, and the fragment holding the key is never sent to any server. | A web page served from that domain *can* read the fragment, so web joiners trust the link domain, like any end-to-end encrypted web app. |
| Anyone the invite link is forwarded to | Yes. The link is the key. | Everything a member can see, until the owner removes them. |
| Current members | Yes, by design. | |
| Removed members | Not after the removal, as long as the server is honest: it stops relaying to them, changes the join token so their invite stops working, and the owner rotates the key. | With a malicious server's help: the rotated key, because it's sent under the old key, and a way back in despite the new token. |

## Integrity

- **Moving messages.** A payload is bound to its convoy, sender and channel by the encryption's associated data. A server that moves it elsewhere makes decryption fail.
- **Impersonation.** The server stamps the real sender on every relayed message, and that sender is part of the associated data. So a member can't impersonate another member on their own, only with the server's help.
- **Replay.** A malicious server could replay old payloads.
  - Clients ignore a state whose position is older than the latest one they have from that member, using `fix_time_ms`.
  - An exact replay of the latest state passes as a refresh, so a server could make a member who went quiet look alive.
  - Events need a message ID or timestamp to drop duplicates. That's still to be done before v1.

## Metadata the server sees

- IP addresses and connection times of members.
- How many members a convoy has, and when they join and leave.
- Message timing and size. Members send about once per second while their position changes and every 5 s otherwise, so the server can tell moving from parked.

Possible later mitigations: sending at a constant rate, padding payloads, and connecting through a proxy or Tor.

## Out of scope

- A compromised phone. Malware on a member's phone sees what that member sees.
- Members screenshotting or sharing what they see.

## How the app handles the key

- The key exists only in memory while you're in the convoy, and inside the invite link.
- The app never logs invite links. `Invite.toString()` leaves the key out.
- A copied invite is marked sensitive, so Android leaves it out of the clipboard preview.

## Advice for users

- Share invite links only in private chats. Anyone with the link can join.
- Watch who joins. Remove anyone you don't recognise.
- End the convoy after the drive. The key is discarded with it.

## Possible upgrades

- **Per-member keys or MLS ([RFC 9420](https://www.rfc-editor.org/rfc/rfc9420)).** This makes key rotation safe against a malicious server and gives forward secrecy.
- **Join approval and expiring invite tokens.**
- **Per-member signatures.** These make forgery impossible even with the server's help.

## Reports and data packs

Neither is encrypted, because neither is private: they're meant for everyone.

- **Data packs** are downloaded whole, per region, and matched on the phone. The server learns that someone wanted Finland's cameras, not where they drive.
- **Reports** are public. A report says what, where, which way and when, and nothing about who: no device ID, no account. The server keeps the reporter's address only in memory, for rate limits (5 reports and 30 votes per 10 minutes), and rounds positions to about a metre.
- **Still, a report is a position at a time.** Someone who knows you reported from a road at a moment learns little new. But a server operator who logs connections (or a network observer) could tie a report to an address. The reference server doesn't log addresses with reports; a proxy in front of it might.
- **The feed is the same for everyone**, so polling it reveals nothing about where you are.
- **Abuse:** fake reports are possible. Rate limits, expiry and "not there" votes limit the damage. Trust scores per device would need device identity, which FoxNav avoids for now.
