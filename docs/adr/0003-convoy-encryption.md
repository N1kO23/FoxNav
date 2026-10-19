# 0003: Convoy payloads are end-to-end encrypted

**Status:** accepted, October 2026. Partly replaced by [ADR 0007](0007-convoy-member-keys.md): members sign what they send, and new keys go to each member separately, so convoys work without a relay.

## Context

Users can point the app at any server, and can join convoys hosted on servers they know nothing about. A convoy reveals exactly where people are and how fast they drive. Server operators shouldn't be able to see that, and the official server shouldn't hold such data at all.

## Decision

- The convoy creator generates a random 256-bit key. It travels only inside the invite link's URL fragment, which browsers never send to servers, or in the QR code.
- Every payload is encrypted with AES-256-GCM. The associated data binds each payload to its convoy, sender and channel.
- When someone is removed, the creator rotates the key.

Details are in the [spec](../../protocol/SPEC.md#23-end-to-end-encryption); what this does and doesn't protect is in the [threat model](../threat-model.md).

## Consequences

- Servers relay ciphertext and can't read positions, speeds, names or routes. This makes the official server's privacy and GDPR story simple.
- Features that would need the server to read positions (server-side alerts, server-rendered convoy maps) are impossible by design. Clients do that work.
- Members share one key, so they can read each other's messages, which is intended.
  - A member working together with a malicious server could forge messages as another member, and a removed member could learn the rotated key.
  - Per-member keys or MLS (RFC 9420) fix both if that ever matters (see the threat model).
