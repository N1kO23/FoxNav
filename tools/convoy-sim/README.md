# convoy-sim

Bot cars for testing FoxNav convoys. The bots join a convoy (or start one) on any FoxNav server and drive one behind the other along a real route, there and back. They publish positions the way phones do: end-to-end encrypted, about once a second.

One developer with one phone or emulator can test a whole convoy this way: markers, the timing tower, quick messages, reconnects. With `--bots 10 --minutes 60` it's the soak test from the roadmap.

## Build

```sh
./gradlew :tools:convoy-sim:installDist
tools/convoy-sim/build/install/convoy-sim/bin/convoy-sim --help
```

## Bots start the convoy, you join

```sh
convoy-sim --create --server http://192.168.1.10:8080 --invite-out invite.txt --chatty
```

Then open the invite on the phone or emulator. With adb:

```sh
adb shell "am start -a android.intent.action.VIEW -d '$(cat invite.txt)'"
```

The server address goes into the invite, so the bots and the phone must both reach it under that address. Use your computer's LAN address, not `localhost`: the emulator can't reach your `localhost`, and the bots can't use the emulator's `10.0.2.2`.

## You start the convoy, the bots join

Start a convoy in the app and share its invite to your computer, for example through a note or a chat with yourself. Save the link in a file, then:

```sh
convoy-sim --invite-file invite.txt --server http://localhost:8080
```

`--server` is only needed when your computer reaches the server under a different address than the one in the invite, like the emulator's `10.0.2.2`.

## Where the bots drive

By default they drive from central Helsinki to Leppävaara and back, routed by the server's Valhalla. Put the emulator at the start, so the cars appear right next to you:

```sh
android/scripts/emulator-location.py set 60.1699 24.9384
```

Use `--from LAT,LON --to LAT,LON` for a different route, or `--gpx track.gpx` for a recorded track. `--kmh` sets their cruising speed; each bot drifts a few percent around it, so the gaps between them change.

## What it prints

- When each bot is in, and anything that happens in the convoy: quick messages, regroup points, people leaving.
- Every 15 s, where the lead bot is. It also lists everyone else in the convoy, with their speed, how far they are from the lead bot, when the bots last heard from them, and the destination they share, if any.

That last part checks the other direction: your phone's position reaching the bots and decrypting there.

The bots leave the convoy when you press Ctrl+C or when `--minutes` runs out.

## Keep the invite private

An invite link carries the convoy's encryption key. `--invite-file` and `--invite-out` keep it out of your shell history. Don't paste it into issues or logs.
