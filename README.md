# Vision TV Home

A cinematic Android TV launcher modelled on the tvOS 26/27 Liquid Glass home screen.
Built as a native Java app (`com.aref.visiontv`) for TCL / Android TV (SDK 30+).

## What it does

- **tvOS-style app bar.** A floating glass bar holds the six pinned apps as landscape tiles,
  using each app's own Android TV banner art. Apps without a banner get a composite tile built
  from their real icon and brand colour. No text labels, as in the reference.
- **Second app row.** Every other launchable TV app sits in a scrollable row below the bar,
  directly on the wallpaper.
- **Full-screen featured artwork.** Focusing a top-bar streaming app crossfades the background to
  a real backdrop from TMDB, with title, year, rating, genres and synopsis.
- **Wallpaper state.** Focusing an app outside the top bar, or an app with no cinematic data,
  returns to a calm teal wallpaper instead of showing unrelated artwork.
- **Soft focus halo.** A 1.05 scale and a white halo ring drawn outside the tile; the artwork
  keeps its original colours and there is no 3D tilt.
- **Calm motion.** 230 ms focus springs, 650 ms artwork crossfade, 26 s Ken Burns drift, and a
  reduced-motion mode that follows the system animation scale.

## Design system

Neutral glass over the artwork, with colour coming from the app art itself:

| Token | Value | Use |
| --- | --- | --- |
| Tile | `122 × 88 dp` | landscape app tile |
| Tile radius | `14 dp` | app tile corners |
| Tile gap | `24 dp` | spacing between tiles |
| Bar radius | `32 dp` | floating glass bar |
| Focus halo | `#E6FFFFFF` ring + soft glow | focused tile |
| Wallpaper | `#3B7A8C` | neutral ambient state |

Scrims are neutral black, not warm bronze: a top gradient for the title, a left gradient for
readability, and a bottom gradient behind the bar.


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

## Recommendations (wired, currently hidden)

The TMDB discovery engine and recommendation cards still exist, but the row is hidden in this
build because the tvOS home screen has no recommendation shelf. It will return as an optional
shelf in a later stage.

- **Source.** TMDB `/discover` for that app's mapped watch provider, filtered to the configured
  region. This is **general discovery for that provider**, not the app's internal recommendations.
- **Fallback.** With no TMDB key, no cached data, offline, or an unmapped app, no cards are shown.
  The launcher never invents placeholder titles.
- **Attribution.** Real metadata on screen is labelled `Artwork & data: TMDB`.

## Navigation model

| From | Key | Goes to |
| --- | --- | --- |
| Top bar | Down | Second app row, same column where possible |
| Top bar | Up / Back | Stays on the bar |
| Second row | Up | Top bar, same column where possible |
| Second row | Down | Stays in the row |
| Any row | Left, Right | Contained at row ends (no sideways escape) |
| Second row | Back | Top bar |
| Top bar | Back | Nothing (Home stays put) |

`OK` only launches the focused app; browsing never launches anything.


## Enable real artwork (TMDB API key)

1. Create a free key at https://www.themoviedb.org/settings/api (v3 auth).
2. Build with the key:

   ```bash
   cd vision-tv-launcher
   TMDB_API_KEY=your_key_here gradle :app:assembleDebug
   ```

   or put `TMDB_API_KEY=your_key_here` in `local.properties` (git-ignored) or
   `~/.gradle/gradle.properties`.

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
- `Inputs` (TCL source picker) and Control Center are not in this build yet; they return in a
  later stage.

## Build

```bash
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
