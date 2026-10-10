# 0001: Build on Ferrostar and MapLibre

**Status:** accepted, October 2026

## Context

Turn-by-turn navigation is where solo navigation apps fail: snapping to the road, off-route detection, rerouting, and timing instructions despite noisy GPS and tunnels. The commercial SDKs (Mapbox, Google, HERE, TomTom) solve this, but they tie the app to their paid backends and terms. That contradicts FoxNav's core idea of working with any server.

## Decision

Use [Ferrostar](https://github.com/stadiamaps/ferrostar) for navigation and [MapLibre Native](https://maplibre.org) for the map.

- Ferrostar is BSD-licensed, with a Rust core and native Kotlin/Compose and SwiftUI UIs.
- It works with any routing backend through route adapters, and has CarPlay UI and Android Auto support.
- It's production-ready on iOS and Android, but still pre-1.0.

## Consequences

- Navigation works from day one with self-hosted Valhalla, and with any other server a user picks.
- Ferrostar's API still changes before 1.0. We pin its version, upgrade deliberately, and fix problems upstream instead of forking.
- The app must be native (Kotlin, later Swift). Ferrostar's React Native bindings are only beta, and there are no Flutter bindings.
