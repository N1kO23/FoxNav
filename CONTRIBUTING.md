# Contributing to FoxNav

Thanks for helping. This guide covers setting up, the rules the project holds itself to, and how contributions are licensed.

## Setting up

| You want to work on          | You need                                                                               |
| ---------------------------- | -------------------------------------------------------------------------------------- |
| `shared/`, `server/`         | JDK 21. Gradle picks it up automatically, or downloads one if you don't have it.       |
| `android/`                   | [Android Studio](https://developer.android.com/studio), which installs the Android SDK |
| `infra/` (the local backend) | Docker with Compose v2, and about 8 GB of disk                                         |

```sh
./gradlew check            # tests for everything that's part of the build
./gradlew :server:run      # the reference server on http://localhost:8080
```

**The Android app** only joins the build when an Android SDK is configured. That means `sdk.dir` in `local.properties`, which Android Studio writes when you open the project, or `ANDROID_HOME`. Without one, Gradle prints a note and builds the rest, so server-only work doesn't need the SDK. Debug builds talk to the local backend at `http://10.0.2.2:8080`, which is how the emulator reaches your machine. Start the backend as described in [infra/README.md](infra/README.md).

## Dev builds

- **Every CI run** keeps both APKs for 14 days, as the `foxnav-dev-apks` artifact of the run.
- **Every push to `main`** also replaces the [`dev` pre-release](https://github.com/N1kO23/FoxNav/releases/tag/dev), with direct download links for phones:
  - `foxnav-dev-play.apk` for phones with Google Play services;
  - `foxnav-dev-foss.apk` for phones without.
- **They're a separate app.** Debug builds are FoxNav Dev (`app.nullpointr.foxnav.dev`), so they install next to a store build.
- **They update each other.** Builds from CI and from any machine sign with the same key (`android/app/debug.keystore`, checked in on purpose), so each installs over the previous one. CI builds carry their run number and commit in the version name.

## Testing on the emulator

- Debug builds simulate the drive along the route by default: the switch is in the route preview.
- For free drive, or to replay a recorded drive, feed locations with `android/scripts/emulator-location.py`. Current emulator images ignore `adb emu geo fix`.

```sh
android/scripts/emulator-location.py set 60.1699 24.9384           # stand in central Helsinki
android/scripts/emulator-location.py replay android/scripts/tracks/helsinki-centre.gpx --speedup 3
```

If an Android build fails with `jlink executable ... does not exist`, Gradle is running on an IDE's bundled Java runtime. Point the IDE's Gradle at a full JDK 21. For VS Code's Java extension that's `"java.import.gradle.java.home"` in `.vscode/settings.json`.

## Product rules

These come from the project's goals. Please don't send changes that break them, and point them out in review.

- **Speed stays private.** Convoy members may see each other's live speed. Nothing else may show, rank, store or share speeds: no leaderboards, no top-speed stats or badges, no speeds in exports or on servers.
- **Servers never see positions.** Convoy payloads are end-to-end encrypted ([threat model](docs/threat-model.md)). Server-side features that would need plaintext locations don't fit.
- **Speed camera alerts respect local law.** They're switchable per country, and some countries forbid them entirely ([data sources](docs/data-sources.md)).
- **No destination needed.** Free drive is a first-class mode: everything except turn-by-turn works without a route.
- **No distractions while driving.** No typing while moving, big touch targets, glanceable screens.
- **Japanese text is checked by a native speaker.** The design uses Japanese accents; wrong Japanese looks worse than none.
- **Credit the data.** OpenStreetMap attribution stays visible on the map, and new data sources get listed in [data-sources.md](docs/data-sources.md) with their licence.

## Making changes

- Kotlin follows the [official style](https://kotlinlang.org/docs/coding-conventions.html), and `.editorconfig` covers the basics.
- Keep the [protocol spec](protocol/SPEC.md) and the `.proto` files in step with the code, and discuss changes that would break existing clients or servers before making them.
- Decisions that shape the architecture get a short record in [docs/adr/](docs/adr/).
- Add tests for behaviour you change. The server's tests double as the executable description of the protocol.

## Licensing and sign-off

Your contribution is licensed under the licence of the directory you change ([LICENSE.md](LICENSE.md)):

- the apps: GPL-3.0-or-later with the app-store exception;
- the server: AGPL-3.0-or-later;
- everything else: Apache-2.0.

Sign off every commit (`git commit -s`). That certifies the [Developer Certificate of Origin](https://developercertificate.org): you wrote the change or have the right to submit it under that licence.
