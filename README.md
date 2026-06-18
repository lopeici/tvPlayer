# tvPlayer

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

## Tech stack

- Kotlin + Jetpack Compose (Material 3), single-Activity, MVVM.
- Media3 ExoPlayer (`media3-exoplayer`, `-hls`, `-ui`, `-cast`) + Google Cast SDK.
- Coil 3 for channel logos, OkHttp for fetching playlists.
- Persistence via small JSON files in app storage (kotlinx.serialization) — no Room / annotation
  processors, to keep the build simple.
- AGP 9.0.1, Gradle 9.4.1, Kotlin 2.2.10, JDK 21, `compileSdk`/`targetSdk` 36, `minSdk` 26.

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

> **Important — non-ASCII username workaround.** This account name contains a non-ASCII
> character, which makes the JVM's default temp dir break Gradle's loopback selector
> (`Unable to establish loopback connection`). Builds therefore force an **ASCII** `java.io.tmpdir`
> (`D:\tmp`). The project's `gradle.properties` does this for the Gradle daemon; the
> **`build.ps1`** wrapper does it for the Gradle launcher too. Always build with `build.ps1` from a
> plain terminal (or use Android Studio — see below).

From the project root (`C:\Andre\01-Work\08-IPTV`):

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
   D:\Android\Sdk\platform-tools\adb.exe install -r app-release.apk
   ```

## Opening in Android Studio

Android Studio is installed at `D:\Android\AndroidStudio`. Open `C:\Andre\01-Work\08-IPTV` as a project; it will
use the SDK at `D:\Android\Sdk`.

> If Gradle sync fails with `Unable to establish loopback connection`, add this line via
> **Help ▸ Edit Custom VM Options** and restart:
> ```
> -Djava.io.tmpdir=D:\tmp
> ```

### Running in the emulator

An Android 16 AVD named `tv16` already exists. Launch it from Android Studio's Device Manager, or:

```powershell
D:\Android\Sdk\emulator\emulator.exe -avd tv16
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
- Picture-in-Picture for local playback.
- Parental PIN lock, sleep timer.
