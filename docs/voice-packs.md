# Voice packs

A voice pack makes FoxNav speak guidance and warnings in a recorded voice. It is a set of short clips, one per phrase. FoxNav joins them, like "In 300 metres" + "Turn left", and uses the phone's speech for anything a pack can't say, such as street names, or a phrase the pack doesn't have.

Packs are made in the app (**Settings → Voice packs**): record each phrase, choose an audio file for it, or add many files at once. They are also shared as zip files: **Export** saves one, and **Import a voice pack (zip)** reads one on any phone.

Only record or share a voice with its owner's permission. That includes cloned or synthesized voices of real people.

## The zip format

```
My voice.zip
├── voice.json          optional: {"format": 1, "name": "My voice"}
├── in_300_m.mp3
├── turn_left.ogg
├── turn_right.wav
└── ...
```

- **Clips** are named after their phrase: the file name without its extension is the phrase's name from the tables below, like `turn_left`. Capitals, spaces and hyphens are fine (`Turn Left.mp3`, `turn-left.mp3`).
- **Formats:** anything the phone plays, such as MP3, AAC (M4A), Ogg Vorbis, Opus, FLAC or WAV. FoxNav converts each clip to mono 16-bit WAV at 22.05 kHz.
- **Length:** up to 10 seconds per clip. Leading and trailing silence is trimmed, and the volume is evened out, so clips recorded at different levels sound alike.
- **Folders** inside the zip don't matter; only file names count.
- **`voice.json`** names the pack. Without it, the pack takes the zip's name.
- Other files are ignored. A pack doesn't need every phrase; whatever it lacks is spoken by the phone.

An exported pack is in this format, with WAV clips, so it imports unchanged.

## Phrases

Say each phrase as written, calmly and clearly, as you would to a driver. A pack for another language says the same thing in that language: `turn_left` is that language's "turn left".

### Distances

Guidance rounds the distance to the nearest of these. Packs used with miles need the imperial ones; packs used with kilometres need the metric ones.

| File name           | Say               |
| ------------------- | ----------------- |
| `in_100_m`          | In 100 metres     |
| `in_200_m`          | In 200 metres     |
| `in_300_m`          | In 300 metres     |
| `in_400_m`          | In 400 metres     |
| `in_500_m`          | In 500 metres     |
| `in_800_m`          | In 800 metres     |
| `in_1_km`           | In 1 kilometre    |
| `in_1_5_km`         | In 1.5 kilometres |
| `in_2_km`           | In 2 kilometres   |
| `in_3_km`           | In 3 kilometres   |
| `in_5_km`           | In 5 kilometres   |
| `in_500_feet`       | In 500 feet       |
| `in_1000_feet`      | In 1000 feet      |
| `in_a_quarter_mile` | In a quarter mile |
| `in_half_a_mile`    | In half a mile    |
| `in_1_mile`         | In 1 mile         |
| `in_2_miles`        | In 2 miles        |
| `in_5_miles`        | In 5 miles        |

### Turns and lanes

| File name         | Say                        |
| ----------------- | -------------------------- |
| `turn_left`       | Turn left                  |
| `turn_right`      | Turn right                 |
| `bear_left`       | Bear left                  |
| `bear_right`      | Bear right                 |
| `sharp_left`      | Turn sharp left            |
| `sharp_right`     | Turn sharp right           |
| `keep_left`       | Keep left                  |
| `keep_right`      | Keep right                 |
| `go_straight`     | Go straight ahead          |
| `continue`        | Continue on this road      |
| `u_turn`          | Make a U-turn              |
| `merge`           | Merge                      |
| `ramp_left`       | Take the ramp on the left  |
| `ramp_right`      | Take the ramp on the right |
| `exit_left`       | Take the exit on the left  |
| `exit_right`      | Take the exit on the right |
| `roundabout`      | Enter the roundabout       |
| `exit_roundabout` | Exit the roundabout        |
| `arriving`        | Your destination is ahead  |
| `arrive`          | You have arrived           |

### Warnings

Spoken alone ("Speed camera") or followed by a distance ("Speed camera" + "In 400 metres"), depending on the warning setting.

| File name             | Say                 |
| --------------------- | ------------------- |
| `speed_camera`        | Speed camera        |
| `red_light_camera`    | Red light camera    |
| `average_speed_check` | Average speed check |
| `police`              | Police              |
| `speed_check`         | Speed check         |
| `accident`            | Accident            |
| `hazard`              | Hazard              |
| `stopped_vehicle`     | Stopped vehicle     |
| `roadworks`           | Roadworks           |
| `slippery_road`       | Slippery road       |
| `animal`              | Animal on the road  |
| `road_closed`         | Road closed         |

The list is defined in `shared/src/commonMain/kotlin/app/nullpointr/foxnav/shared/voice/Phrases.kt`. New phrases may be added; existing names won't change.
