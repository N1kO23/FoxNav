# 0006: Search works offline from search packs that come with road data

**Status:** accepted, October 2026

## Context

With road data on the phone ([ADR 0005](0005-offline-routing.md)), routes, guidance and speed limits work without a connection, but finding where to go still needed Photon on the server. An offline route is little use if you can't search for the destination.

Photon itself is far too big to run on a phone. Finland's index is gigabytes, and it runs on the JVM with Elasticsearch-style storage. What drivers search for is much smaller: towns and districts, streets, addresses, and named places such as shops, fuel stations and sights.

## Decision

- **Servers publish search packs** ([spec §3.4](../../protocol/SPEC.md#34-search-packs)), built from the Photon dump they already import: per region, in cells of 0.25°.
- **The cells line up with the local-road routing tiles.** The phone takes the search data for every local-road tile it keeps, so search works wherever routes do, with nothing more for drivers to choose. Sizes shown for road data include it.
- **On the phone, an SQLite full-text index (FTS4)** holds the entries. Every Android version has FTS4; FTS5 isn't reliably there.
  - Text is compared in lower case and without accents: "aanekoski" finds Äänekoski. Every word typed matches the start of a word.
  - Results are ranked by:
    - how well the name matches;
    - kind: towns, then landmarks, then streets, with addresses first when the query has a house number;
    - how notable the place is;
    - distance.
- **Photon first, when there's a connection.** The phone's index answers when there's no connection or the server can't be reached.
- **Offline, only places the phone can route to.** A result stays only if the phone has road data where it is. Within 300 km of the car, the phone's Valhalla must also find a way there, in one matrix request (`sources_to_targets`) for all the candidates. Further off, or with the car outside its road data, road data at the place has to do.
- **Typos forgiven when the exact search finds too little.** A second pass allows one typo in words of five to eight letters and two in longer ones (a wrong, missing, extra or swapped letter). It looks up the index's own words (SQLite's `fts4aux`) with the same first letter, so "mannerhem" and "koksikatu" still find what was meant. Results spelt as typed come first.
- **Names offline too.** A downloaded area takes its town's name from the addresses and streets around it, or for a small area its district's. A dropped pin takes the name of the address or place within 40 m, or else stays a dropped pin, with its district as the second line. Online, Photon's reverse lookup does the same.

## Consequences

- **Smaller than the road data, but not small.** All of Finland is 18 MB to download (1.29 million entries), but the index takes about ten times that on the phone, some 190 bytes an entry. Central Helsinki's two densest cells are 1.5 MB to download and 16 MB on the phone, next to 52 MB of routing tiles for the same cells.
- **Only where there's road data.** Searching offline finds nothing outside the downloaded areas, which is where offline routes couldn't go anyway.
- **Coarser than Photon.** A typo in a word's first letter isn't forgiven, and the ranking is simple. Online search stays Photon's.
- **Honest, but not free.** Each offline search makes one matrix request on the phone's Valhalla, on top of the index lookup.
- **Cells update independently.** Unlike routing tiles, search cells don't reference each other, so a new build replaces cell by cell; there's no need to switch whole sets.
- **The builder needs the Photon dump.** Servers that run Photon from a dump have it; others can build packs from any similar source in the same format.
