# Vision TV Home

A cinematic Android TV launcher inspired by Apple TV's full-bleed artwork and Vision Pro's
layered glass. Built as a native Java app (`com.aref.visiontv`) for TCL / Android TV (SDK 30+).

## What it does

- **Full-screen featured artwork.** Focusing a streaming app crossfades the entire background to
  a real backdrop from TMDB, with title, year, rating, genres and synopsis.
- **Atmospheric fallback.** No key, no network or unsupported app: a painterly accent bloom built
  from the app's own icon colours, with the app icon ghosted into the scene. Never a blank screen.
- **Glass icons.** Rounded app art, specular sheen, accent bloom, edge light and a restrained
  ±7° depth tilt toward the focused position.
- **Hover recommendations.** Focusing a mapped streaming app reveals a discovery row of real
  titles and artwork; the row stays hidden when no real data is available.
- **Floating dock.** Centred translucent pill with the six pinned apps and labels.
- **Metadata layer.** Readable lower-left block over adaptive scrims.
- **Calm motion.** 230 ms focus springs, 650 ms artwork crossfade, 26 s Ken Burns drift, and a
  reduced-motion mode that follows the system animation scale.

## Design system

Warm cinematic glassmorphism, tuned for 10-foot viewing:

| Token | Value | Use |
| --- | --- | --- |
| Warm charcoal | `#0A0807` / `#0B0908` | scrims, window |
| Smoked bronze | `#8A6A46` | default ambience |
| Soft amber | `#E3B778` | icon bloom blend, glass sheen |
| Champagne | `#E8D5B8` | badges, secondary labels |
| Warm ivory | `#FFF8F0` | primary type |
| Focus rim | `#FFF4E2` | focused icon edge |

Ambience is sampled from the displayed artwork (warm-shifted) so artwork, bloom, dock and
focus light share one palette. Artwork sits under a filmic warm veil, warm top/left/bottom
scrims, and a floating warm-glass dock. Motion: 230 ms focus spring, 500 ms parallax,
650 ms artwork crossfade, 26 s Ken Burns, all suspended under reduced-motion settings.

## Supported providers (TMDB watch-provider mapping)

| App | TMDB provider |
| --- | --- |
| Netflix | 8 |
| Prime Video | 9 |
| Disney+ | 337 |
| Apple TV+ | 350 |
| Hulu | 15 |
| Peacock | 386 |
| Paramount+ | 531 |
| Crunchyroll | 283 |
| Max | 1899 |

Everything else (YouTube, YouTube Music, AirCast, Live TV, games, tools) uses the visual fallback.
YouTube and Plex are intentionally not mapped: TMDB has no reliable public provider feed for them,
and inventing metadata would be wrong.

## Recommendations (Android TV 11-style hover row)

Focusing a mapped app reveals a horizontal recommendation row under the metadata block, the way the
stock Android TV 11 launcher surfaces content when an app tile is highlighted.

- **Source.** TMDB `/discover` for that app's mapped watch provider, filtered to the configured
  region. This is **general discovery for that provider**, not the app's internal recommendations.
  No private app APIs, content channels or program data are read; none are publicly available.
- **Card focus.** Moves the full-screen backdrop to the card's artwork and updates title, synopsis,
  year, rating and genres. The row's backdrop is warm-sampled for ambience like the app scene.
- **OK on a card** launches the focused provider app. Third-party launchers cannot deep-link into a
  specific title without a public content URI, and those are not exposed.
- **Fallback.** With no TMDB key, no cached data, offline, or an unmapped app, the row stays hidden
  and the app keeps its cinematic icon/backdrop scene. The app never shows placeholder titles —
  cards appear only when real data was fetched and cached.
- **Attribution.** While a card is focused the metadata block reads
  `Recommendations from TMDB · general discovery`.

## Navigation model

| From | Key | Goes to |
| --- | --- | --- |
| Dock | Up | Recommendation row if visible, otherwise the app row |
| Dock | Down / Back | Stays in the dock |
| App row | Up | Header actions |
| App row | Down | Recommendation row if visible, otherwise the dock |
| App row / dock | Left, Right | Contained at row ends (no focus escaping sideways) |
| Recommendation row | Up / Down | App row / dock |
| Recommendation row | Back | App row |
| Anywhere else | Back | Dock |

`OK` only ever launches the focused app or the focused card's provider app; browsing the rows never
launches anything.

## Enable real artwork (TMDB API key)

1. Create a free key at https://www.themoviedb.org/settings/api (v3 auth).
2. Build with the key:

   ```bash
   cd vision-tv-launcher
   TMDB_API_KEY=your_key_here gradle :app:assembleDebug
   ```

   or put `TMDB_API_KEY=your_key_here` in `~/.gradle/gradle.properties`
   (or `local.properties` via `-PTMDB_API_KEY=...`).

3. Already-built APK? Push the key to the device instead (no rebuild):

   ```bash
   adb shell am start -n com.aref.visiontv/.MainActivity --es tmdb_key YOUR_KEY
   ```

   Region defaults to `US`; override with `--es tmdb_region GB` (ISO 3166-1).

The key is stored in the app's private SharedPreferences. Clearing app data removes it.

Attribution: *This product uses the TMDB API but is not endorsed or certified by TMDB.*
The launcher shows "Artwork & data: TMDB" while real metadata is on screen.

## Install

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aref.visiontv/.MainActivity
```

Wireless ADB requires Developer options → Network debugging on the TV.

Make it the home screen:

```bash
adb shell cmd package set-home-activity com.aref.visiontv/.MainActivity
```

Roll back to the stock launcher:

```bash
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

## Behavior and limits

- Artwork/metadata cached on disk for 6 hours, bitmaps in an LRU memory cache; neighbours are
  prefetched on focus. All network work runs off the main thread with short timeouts.
- Offline: last cached title is shown; otherwise the accent fallback. No blocking dialogs.
- Missing or disabled apps are skipped during discovery; launch failures show a toast.
- 720p, 1080p and 4K layouts scale from dp; artwork is requested at 1280 px wide.
- Recommendation data uses the same 6-hour metadata cache and LRU bitmap cache; card posters are
  prefetched off the main thread when the row appears.
- `Inputs` opens TCL's source picker.

## Build

```bash
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
