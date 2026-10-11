# Design direction: "Tokyo night drive"

A dark, high-tech car-navigation feel with Japanese accents. Modern and restrained, not a pile of clichés.

**Status:**

- The UI direction was approved in October 2026.
- The values live in [`tokens.json`](tokens.json), the source of truth for the Compose theme and both map styles.
- Branding (mark and wordmark) gets another pass later.
- Colour palettes become user-customizable later. Every theme in the tokens defines the same keys, so a custom palette is just one more theme.

The mockups are in a [design canvas](https://claude.ai/artifact/G7KEQUyDuin7LevwHHtcp2), visible to the project owner until shared.

## Mockups

| Board | Shows |
|---|---|
| Direction | Kitsune mark, palettes, type, convoy markers, the over-limit chime (playable) |
| Navigation, night | Maneuver card, lanes, camera alert, speedometer with limit sign, ETA. Tap the speedometer to preview the over-limit warning. |
| Navigation, day | The same screen in the day palette |
| Free drive | Driving without a destination: current road, speed limit, camera ahead, convoy, "Where to?" |
| Convoy timing tower | Members in road order with gaps and live speeds, stopped and lost-signal states, what the leader is playing, quick messages |

## Brand

- **Mark:** a geometric kitsune (fox) mask, after the fox messengers of Inari shrines. White face, vermilion markings.
- **Wordmark:** FOXNAV in Chakra Petch, with 狐ナビ as the Japanese accent.

## Colour

| Role | Night | Day |
|---|---|---|
| Ground | Ink `#0B0D12` | Washi `#F4F1EA` |
| Panels | Raised `#141821` | `#FFFFFF` |
| Borders | `#232A38` | `#DAD4C6` |
| Text | `#E8EAF0` | Sumi `#16171A` |
| Secondary text | `#8A93A6` | `#5B6070` |
| Brand, primary actions, *you* | Vermilion `#E5462E` (small text `#FF6B4F`) | `#D93A22` |
| Route, live data | Cyan `#35D6F0` | Teal `#0E88A6` |
| Warning | Amber `#FFB020` | Amber `#FFB020` chips with dark text |
| Danger | Red `#FF4D4D` | `#C62828` |
| Map water | `#11203F` | `#B7CDE2` |

**Convoy member colours:** `#4C8DFF`, `#3DDC97`, `#FF5FA2`, `#B79BFF`, plus vermilion for yourself. They stay clear of amber and red, which mean warnings.

## Type

- **Chakra Petch:** HUD numerals and headings (speed, distances, times). JetBrains Mono is the alternative to compare in the navigation mockup's tweaks.
- **IBM Plex Sans:** interface text.
- **IBM Plex Sans JP:** Japanese accents.

All are open-licensed (SIL OFL) and can be bundled with the app.

## Rules

1. Japanese is an accent, never the only label.
2. Amber and red mean warning, always with an icon, never colour alone.
3. Day mode for sunlight. Switch at sunrise, sunset and in tunnels.
4. Speed appears only inside your convoy.
5. On Android Auto and CarPlay only the map is ours, so the identity lives in the map style and markers.
6. Glow and grid textures sparingly; legibility at a glance wins.
7. Avoid the Rising Sun flag motif and copyrighted IP (Initial D, Wangan Midnight, Gran Turismo). Mood boards only.

## Sound

The over-limit warning is an original take on the kin-kon chime (速度警告音) that Japanese-market cars rang above 105 km/h until 1986: two bell strikes, high then low. The mockup synthesizes it in the browser; the app ships a sample synthesized the same way (`sounds/kinkon.py`).

## In the app

- **Theme:** the Android theme and both map styles use these values. The map styles are generated from `tokens.json` by `infra/scripts/styles/generate.mjs`.
- **Fonts:** Chakra Petch, IBM Plex Sans and DSEG7 Classic (the 7-segment speedometer; by keshikan, SIL Open Font License 1.1) are bundled. IBM Plex Sans JP isn't, until the Japanese accents ship.
- **Japanese accents:** they stay out of the app until a native speaker has checked them.
- **Points of interest:** the map shows only landmarks you navigate by, such as stations, airports, ferries, parks, peaks and attractions. Fuel stations need their own icon and data.
- **Sounds:** scripts in `sounds/` generate the WAV files the app plays. The over-limit choices are the kin-kon (`kinkon.py`), a single strike of its bell (`ding.py`) and three even beeps (`beep.py`); drivers can also use a sound of their own. The camera and report warning is two rising pips (`alert.py`), so it never sounds like a speeding warning.
- **Map credits:** the mockups' small "© OpenStreetMap contributors" tag, on the top edge of the bottom panel. The app takes its text from the map style's attribution.
- **Glow:** the night route line is the one glowing thing on the map, a soft bloom, a glow and a bright core like the mockup's blurred line, plus a faint underglow in your car's colour under your own car. By day there's no glow.

## Japanese strings to check

Every string needs a native speaker's check before it ships:

| Text | Reading | Meaning | Used for |
|---|---|---|---|
| 狐ナビ | kitsune nabi | fox navi | wordmark accent |
| ナビ | nabi | navigation | section accent |
| コンボイ | konboi | convoy | convoy screen |
| 速度 | sokudo | speed | speed accent |
| 報告 | hōkoku | report | reports |
| オービス | ōbisu | speed camera (colloquial) | camera alert |
| 右方向 | migi hōkō | to the right | maneuver card |
| 到着 | tōchaku | arrival | ETA |
| 再生中 | saisei-chū | now playing | convoy soundtrack |
| 走行中 | sōkō-chū | driving | free drive |
| 給油 · 休憩 · 停車 · 警察 · 危険 · 集合 | kyūyu · kyūkei · teisha · keisatsu · kiken · shūgō | fuel · break · pull over · police · danger · assemble | quick messages |
| 速度警告音 | sokudo keikoku on | speed warning sound | chime |
| 夜 · 昼 | yoru · hiru | night · day | palette names |
| 墨 · 和紙 · 朱 · 浅葱 · 琥珀 · 紅 · 藍 | sumi · washi · shu · asagi · kohaku · beni · ai | ink · Japanese paper · vermilion · pale blue-green · amber · crimson · indigo | colour names |
