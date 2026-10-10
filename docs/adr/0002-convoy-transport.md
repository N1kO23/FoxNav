# 0002: Convoys use a WebSocket relay, not WebRTC

**Status:** accepted, October 2026

## Context

Convoy members need each other's position about once per second. Peer-to-peer WebRTC looks attractive for low latency, but:

- Phones on mobile data sit behind carrier NAT, so WebRTC would often need a TURN relay server anyway.
- A full mesh needs n(n−1)/2 connections; 20 cars means 190 connections.
- WebRTC connections are fiddly to restore when a phone changes cell tower or loses signal in a tunnel.
- People who join late need everyone's current position, which some server has to hold.

Location messages are tiny (under 100 bytes), and a server is needed for signalling anyway.

## Decision

Members connect to a relay server over a WebSocket and exchange small protobuf messages ([spec](../../protocol/SPEC.md#2-realtime-draft)). The server forwards each message to the other members and keeps each member's latest state for late joiners.

## Consequences

- Each phone has one connection, whatever the convoy size, and reconnecting is simple.
- The relay is easy to implement, which helps third-party servers.
- The server sees metadata: who is in which convoy, and when they send. Payloads are encrypted ([ADR 0003](0003-convoy-encryption.md)).
- WebRTC remains the right tool for push-to-talk voice later. That will use an SFU such as LiveKit, not a mesh.
