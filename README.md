# KaboomIPTV

A native Android **IPTV player** for Android 16 (API 36). Load M3U playlists, browse and search
channels, favorite them, watch live TV, and cast to a Chromecast. Distributed as a **sideloaded
APK** (no Play Store).

## Features

- **Playlists** — add remote **M3U URLs** or import a **local `.m3u` file**; save multiple
  playlists and switch between them; refresh on demand.
- **Channels** — grouped by category, with logos, **search/filter**, and **favorites**.
- **Recently watched** — quick access to channels you've played.
- **Program guide (EPG)** — optional XMLTV guide URL per playlist, matched to channels by `tvg-id`:
  now-playing with a progress bar on the channel list, now/next on the player, and a per-channel
  schedule sheet. Guides are parsed (gzip supported) and cached.
- **Player** — full-screen immersive playback, keep-screen-on, HLS support, buffering and
  dead-channel error handling with retry.
- **Channel zapping** — next/previous channel and a channel-number jump (like a TV remote).
- **Chromecast** — cast the current channel to a Cast device. Because the Chromecast streams
  directly, **casting continues when you minimize the app**.
- **Foldables & tablets** — adaptive layout: on a wide/unfolded screen the channel list and
  player appear side-by-side with a navigation rail (with full-screen and list-collapse buttons);
  on a phone (or folded) it's the single-pane layout. Folding/unfolding does not restart the app
  or interrupt playback.
- **Picture-in-Picture** — leaving the app while a channel is open keeps the video playing in a
  floating window (local playback; auto-enters on Android 12+).
- **Now-playing bar** — on a phone, when a channel is playing and you're browsing the list, a bar
  above the navigation lets you jump straight back to the player.
- **Android TV** — single APK that also appears on the TV (leanback) launcher and is navigable by
  remote (D-pad focus + select); the Cast button and PiP are disabled on TV. Runs on Android TV
  8+ (API 26+), e.g. Mi TV 4S (Android 9 / API 28). Remote-focus polish (initial focus, on-screen
  keyboard for search) is a known rough edge.

## Tech stack

- Kotlin + Jetpack Compose (Material 3), single-Activity, MVVM.
- Media3 ExoPlayer (`media3-exoplayer`, `-hls`, `-ui`, `-cast`) + Google Cast SDK.
- Coil 3 for channel logos, OkHttp for fetching playlists.
- Persistence via small JSON files in app storage (kotlinx.serialization) — no Room / annotation
  processors, to keep the build simple.
- AGP 9.0.1, Gradle 9.4.1, Kotlin 2.2.10, JDK 21, `compileSdk`/`targetSdk` 36, `minSdk` 26.

## Compatibility

`minSdk 26` (Android 8.0) → `targetSdk 36` (Android 16). One APK installs on phones, tablets,
foldables, and Android TV. **Anything below API 26 (Android 7.x and older) cannot install** — the
EPG/date code uses `java.time`, which requires API 26+.

### Phones / tablets / foldables

| Android | API | Status |
|---|---|---|
| 8.0 / 8.1 (Oreo) | 26 / 27 | ✅ Minimum supported |
| 9 (Pie) | 28 | ✅ Supported |
| 10 | 29 | ✅ Supported |
| 11 | 30 | ✅ Supported |
| 12 / 12L | 31 / 32 | ✅ Supported (PiP auto-enters from here) |
| 13 | 33 | ✅ Supported |
| 14 | 34 | ✅ Supported |
| 15 | 35 | ✅ Supported (edge-to-edge handled) |
| 16 | 36 | ✅ Target — primary test target |
| 7.x and older | ≤ 25 | ❌ Won't install (below `minSdk`) |

### Android TV

| Android TV | API | Status |
|---|---|---|
| 8.0 (Oreo) | 26 | ✅ Minimum supported |
| 9 (Pie) | 28 | ✅ Verified on emulator (matches Mi TV 4S) |
| 10 | 29 | ✅ Supported |
| 11 | 30 | ✅ Supported |
| 12 | 31 | ✅ Supported |
| 13 (Google TV) | 33 | ✅ Supported |
| 14 | 34 | ✅ Supported |
| 7.x and older | ≤ 25 | ❌ Won't install (below `minSdk`) |

**Notes**
- *Verified* = exercised directly (Android 16 phone/foldable emulator; Android TV 9 / API 28). Other
  versions run the same codebase and are forward-compatible; the version-specific paths (PiP,
  edge-to-edge on 15+, 16 KB page size on 15+) are handled.
- **Picture-in-Picture** auto-enters on Android 12+ (API 31+); on Android 8–11 it enters when you
  leave the app. PiP is disabled on Android TV.
- On **Android TV** the Cast button is hidden and the UI is driven by the remote (D-pad).

## Project layout

```
app/src/main/java/com/lopeici/tvplayer/
  TvPlayerApp.kt            Application + AppContainer (manual DI)
  MainActivity.kt           Single Activity; file-import (SAF) launcher
  di/AppContainer.kt        App-scoped singletons (repository, player)
  data/                     Models, M3uParser, TvRepository (JSON persistence)
  playback/PlayerManager.kt ExoPlayer wrapped by CastPlayer (auto local/remote switch)
  ui/                       TvViewModel, TvApp (navigation), screens/, components/
app/src/test/...            M3uParser unit tests
```

## Building the APK

> **Important — non-ASCII username workaround (Windows).** If the Windows username contains a
> non-ASCII character, the JVM's default temp dir breaks Gradle's loopback selector
> (`Unable to establish loopback connection`). Builds therefore force an **ASCII** `java.io.tmpdir`.
> The project's `gradle.properties` does this for the Gradle daemon and the **`build.ps1`** wrapper
> does it for the Gradle launcher too — so always build with `build.ps1` from a plain terminal (or
> use Android Studio — see below). Set the temp path in those two files to an ASCII path on your
> machine.

From the project root:

```powershell
# Debug build
.\build.ps1 assembleDebug

# Signed release build (the sideload APK)
.\build.ps1 assembleRelease

# Unit tests
.\build.ps1 testDebugUnitTest
```

Outputs:
- Debug:   `app\build\outputs\apk\debug\app-debug.apk`
- Release: `app\build\outputs\apk\release\app-release.apk`  ← sideload this one

### Signing

Release builds are signed with `tvplayer-release.jks` using credentials in `keystore.properties`
(both are git-ignored). To rebuild on another machine, recreate them or copy them in.

## Installing on your phone

1. Enable **Install unknown apps** for your file manager/browser in Android settings.
2. Copy `app-release.apk` to the phone and tap it, **or** with USB debugging:
   ```powershell
   adb install -r app-release.apk
   ```

## Opening in Android Studio

Open the project folder in Android Studio. It uses the SDK path from `local.properties`
(git-ignored; created automatically by Android Studio, or by `sdkmanager`).

> If Gradle sync fails with `Unable to establish loopback connection` (the non-ASCII-username issue
> noted above), add this line via **Help ▸ Edit Custom VM Options** and restart, pointing at an
> ASCII path:
> ```
> -Djava.io.tmpdir=C:\tmp
> ```

### Running in the emulator

Create an AVD in Android Studio's Device Manager and launch it there, or from a terminal:

```powershell
emulator -avd <your-avd-name>
```

## Casting — what works and what doesn't

Casting uses Google Cast's **default media receiver**, which plays **HLS (`.m3u8`)** streams well.
It generally **cannot** play raw **MPEG-TS over UDP** or bare `.ts` HTTP streams — many IPTV
providers use those, and those channels will **play locally on the phone but fail to cast**.
Also, casting needs the stream reachable from the Chromecast (HTTPS / correct CORS). This is a
limitation of the Cast receiver, not the app; a custom receiver or transcoder would be needed and
is out of scope.

Casting can only be tested on a real Chromecast on the same Wi-Fi (not in the emulator).

## Not in v1 (possible future work)

- A full timeline/grid EPG view (current EPG shows now/next + per-channel schedule).
- A foreground `MediaSessionService` with a media notification and lock-screen controls.
- Parental PIN lock, sleep timer.
