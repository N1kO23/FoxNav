# 0007: Convoy members sign what they send, so convoys work without a relay

**Status:** accepted, October 2026. Replaces the parts of [ADR 0003](0003-convoy-encryption.md) that rely on the relay: knowing who sent a message, and rotating the key. Details are in the [spec](../../protocol/SPEC.md#23-end-to-end-encryption).

## Context

Off-grid convoys will carry their messages over LoRa radios through [Meshtastic](https://meshtastic.org), with a car that still has internet acting as a gateway to the relay ([architecture](../architecture.md)). ADR 0003's encryption leans on the relay in ways a radio mesh can't:

- **The relay names the sender.** It assigns member IDs and stamps every relayed message with the real sender, and the encryption checks that stamp. On a mesh nobody does either. With one shared key, any member could send as any other: a fake position, a "police ahead" in someone else's name, a key rotation.
- **The relay shuts removed members out.** The new key goes out under the old one, which is safe only because the relay stops relaying to the removed member. Over the radio they keep hearing everything, the new key included.
- **The relay enforces the owner.** It decides who may remove members and rotate the key, and who takes over when the owner leaves.
- **Replays.** Only positions are protected, by their fix time. Over the radio, anyone in range can record frames and send them again.
- **Size.** A Meshtastic packet carries at most 233 bytes, and every byte costs airtime. Today's `MemberState` bundles profile, position, route and song into one message, so every position would carry all the rest, and a long route or song title wouldn't fit at all.

Meshtastic's own encryption doesn't fill these gaps. A channel's key is shared by every radio on it, and the default channel's key is public. Its AES-CTR has no integrity check unless every node on the channel turns on the newer authenticated mode. Its signatures and direct messages belong to radios, not convoy members, and the relay has neither.

## Decision

One frame format, the same over the relay and the radio, so a gateway forwards frames as they are. Since they're signed, a gateway or a relay can drop or delay frames but not change them.

- **Members make their own identity.** On joining, a member's app generates an Ed25519 key pair for signing and an X25519 key pair for receiving keys. A member *is* their signing key. The member ID is the first 4 bytes of its SHA-256, a short hint for finding the key.
  - Members announce both public keys with their profile in a **card**, sent now and then and when asked for. Frames from a sender whose card hasn't arrived wait for it briefly.
  - The relay no longer assigns IDs: it takes the member's own and refuses one already in the convoy. On the radio, a member who hears another card with its own ID makes a new identity.
  - A forged card with someone else's ID gets nowhere, because frames are checked against keys, not IDs.
- **Every frame is signed by its sender and sealed with the convoy key.**
  - **Cleartext header, 13 bytes:**
    - kind (1 byte);
    - convoy tag (4 bytes, derived from the convoy key, so it changes with every rotation and tells receivers which key to use);
    - sender ID (4);
    - counter (4).
  - **Sealing:** the sender signs the convoy ID, header and content with Ed25519, then encrypts content and signature with AES-256-GCM under the convoy key. The convoy ID and header are the associated data.
  - **Nonce:** made from the sender ID and counter, so frames carry none. The counter goes up by one with every frame. It lives exactly as long as its identity: an app that loses it, say in a restart, comes back as a new identity.
  - **Receiving:** receivers decrypt, which proves the sender holds the convoy key, then check the signature against the sender's card.
- **Replays are dropped.**
  - Receivers remember each sender's highest counter, with a window for frames that arrive out of order. A frame that comes over both the radio and the relay counts once.
  - Positions and events carry the time they were made. An old position shows as old, and events more than a few minutes old are dropped, so a new joiner can't be fed old ones either.
- **Ownership is a key.** The invite carries the owner's signing key. Removals, rotations and handovers count only when the owner signs them. Each handover names the next owner's key, so the chain checks out from the invite alone, even after the first owner left.
  - **Handover:** the owner hands over by signing. The app does so when the owner leaves, to the longest-standing member, as the relay does today.
  - **The relay checks too.** It learns the owner's key when the convoy is created and checks the same signatures, so it enforces only what members would accept anyway.
- **A new key never travels under the old one.** On a removal, the owner generates a new key and wraps it for each remaining member separately, under a key from X25519 between the owner's and that member's keys.
  - **Delivery:** one signed rotation frame carries a few members; a big convoy takes several.
  - **Old-key frames** are accepted for a couple of minutes after a rotation, so those already on their way still arrive, but never from removed members.
  - **Catching up:** whoever missed a rotation gets the owner's signed frames again from the relay's history. And when the owner hears someone still using an older key, it wraps the current one for them: that covers a member whose card hadn't reached the owner in time, and one out of reach of the rotation.
- **Frames fit one LoRa packet.** A frame is at most 233 bytes, 93 of them overhead: header, signature and the 16-byte tag. That leaves at most 140 bytes of content.
  - `MemberState` splits into separate kinds: card, position, route and song. The relay keeps the latest of each kind per member for late joiners, instead of one `STATE`.
  - Positions get a compact encoding of about 25 bytes.
  - A shared route takes as many stops as fit in one frame, with their names shortened if need be. Only the owner's key rotations span several frames.

Left for the next ADR, on the radio link:

- how the app reaches the radio, and Meshtastic's port number and channel;
- how often each kind goes out over the radio;
- what gateways forward;
- convoys started with no server at all.

## What was considered

- **Signing what matters, but not every position.** Signatures are most of a position frame. On Meshtastic's default LongFast preset, a signed position takes about 1.3 s on air, against 0.85 s for one sealed with the convoy key only. Six cars sending every 30 s would keep the channel busy about half the time instead of a third. But members could then send fake positions as each other over the radio, and gateways would carry those onto the relay. Sending positions less often over the radio costs less trust.
- **Delayed key disclosure (TESLA).** Cheaper per frame, but a position could be checked only an interval later, 10–30 s, and it needs synchronised clocks.
- **A MAC per receiver.** Grows with the convoy, and breaks when members' lists of each other differ.
- **MLS ([RFC 9420](https://www.rfc-editor.org/rfc/rfc9420)).** Made for group keys, with forward secrecy. But it needs its handshake messages delivered to everyone in order, those run to hundreds of bytes or more, and the convoy page would need a browser implementation. A convoy lasts hours, and its keys go with it.
- **Meshtastic's encryption.** It works per radio, not per member, and only over the radio.

## Consequences

| | Today, over the relay | With this, over the relay and the radio |
|---|---|---|
| A member sending as another | Only with the server's help | Not possible |
| A removed member reading on | Only with the server's help | Not possible |
| Replays | Positions only | Every frame |
| Someone in radio range | – | Can't read or forge; sees radio IDs, timing and sizes |

- **Size.** Each frame has 93 bytes of overhead instead of 28. A position frame is about 120 bytes. Over the relay that hardly matters; over the radio it sets how often positions can go out.
- **Recorded radio traffic.** Anyone in range can record frames, and whoever later gets that key's invite can read them. There's no forward secrecy, as before; the key goes when the convoy ends.
- **No owner, no removals.** If the owner's phone vanishes without handing over, nobody can remove members or rotate the key, and a new convoy is the remedy. Co-owners would fix this if it matters.
- **The realtime protocol changes incompatibly.** v0 is a draft with no released clients, so §2.2–2.4 change in place. The server, the app and the convoy page update together, and so does the threat model.
- **Platforms.** iOS's CryptoKit has Ed25519 and X25519, and so do current browsers' WebCrypto: Chrome added the last, Ed25519, in version 137 (2025). Older browsers can't follow a convoy. Android doesn't have them on every version the app supports, so the app (and the server) use BouncyCastle's own classes for them.
- **Work.** Signing and checking a frame takes well under a millisecond on a phone.
