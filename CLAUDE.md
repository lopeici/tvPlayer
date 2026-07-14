# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**KaboomIPTV** (package `com.lopeici.tvplayer`) — a native Android IPTV player. Load M3U playlists,
browse/search/favorite channels, watch live TV with an optional XMLTV EPG, and cast to Chromecast.
One APK runs on phones, tablets, foldables, and Android TV (leanback). Sideloaded only (no Play
Store). Kotlin + Jetpack Compose (Material 3), single-Activity MVVM, Media3 ExoPlayer.

`minSdk 26` / `targetSdk 36`. API 26 is a hard floor: EPG/date code uses `java.time`.

## Build & test

The working environment here is macOS, so use the Gradle wrapper directly:

```bash
./gradlew assembleDebug              # debug APK (all flavors)
./gradlew assembleRelease            # signed release APK (needs keystore.properties)
./gradlew testDebugUnitTest          # all unit tests (aggregate over flavors)
./gradlew testGenericDebugUnitTest --tests "com.lopeici.tvplayer.M3uParserTest.parsesAttributesAndUrls"   # single test
```

There are **product flavors** (`generic`, `personal`), so flavor-specific task names exist
(`assembleGenericDebug`, `testPersonalDebugUnitTest`, etc.). The non-flavor tasks above are
aggregates that run every flavor.

**Windows caveat (from README):** the maintainer's Windows box has a non-ASCII username that breaks
Gradle's loopback selector, so on Windows builds must go through `build.ps1` (and Android Studio
needs `-Djava.io.tmpdir=C:\tmp` in custom VM options). This is irrelevant on macOS/Linux — `gradlew`
works directly. Unit tests are plain JUnit (no instrumentation/emulator required).

## Signing & flavors

- **Release signing** is optional and only wired up if `keystore.properties` exists (git-ignored,
  references `tvplayer-release.jks`). A fresh clone without it still builds debug fine; release just
  won't be signed. See `app/build.gradle.kts`.
- **`generic` flavor** ships empty (add playlists in-app). **`personal` flavor** bakes a pre-loaded
  playlist into `BuildConfig.SEED_PLAYLIST_URL/NAME` from `personal.properties` (git-ignored).
  Same `applicationId`, so `personal` upgrades a `generic` install in place. Seeding happens once via
  `TvRepository.seedIfNeeded` (guarded by a `seeded.txt` marker so a deleted playlist won't return).

## Architecture (the parts that span files)

**Manual DI, app-scoped singletons.** `TvPlayerApp` (Application) owns an `AppContainer`
(`di/AppContainer.kt`) holding the two singletons: `TvRepository` and `PlayerManager`. No Hilt/Room.
The single `TvViewModel` is Activity-scoped and pulls both out of the container. Critically,
**`PlayerManager` is app-scoped, not ViewModel-scoped** — `TvViewModel.onCleared()` deliberately does
NOT release it, so playback and an active cast session survive Activity recreation (rotation, fold).

**Data flow is one-directional through StateFlow.** `TvRepository` is the single source of truth for
playlists/channels/favorites/recents/EPG, exposed as `StateFlow`s. `TvViewModel` derives UI state
(filtered `visibleChannels`, `favoriteChannels`, `currentProgrammes`, now/next) by `combine`-ing
those flows, and forwards user actions back to the repo. Compose screens only read ViewModel flows
and call ViewModel functions.

**Persistence is plain JSON files in `filesDir`** (kotlinx.serialization) — deliberately no Room/KSP
to keep the build simple. Files: `playlists.json`, `favorites.json`, `recents.json`, `active.txt`,
`channels_<id>.json`, `epg_<id>.json`, plus toggle/marker files (`cast_hls.txt`, `seeded.txt`).
Uncaught crashes are written to `filesDir/crash_log.txt` by a handler installed in `TvPlayerApp`.

**`Channel.key = "$playlistId|$url"`** (`data/Models.kt`) is the stable identity used everywhere —
favorites set, recents list, and the ExoPlayer `MediaItem` mediaId. `currentChannel` is derived by
matching the player's active mediaId against the playback queue. When touching channel identity,
keep this contract intact or favorites/recents/now-playing detection silently break.

**Player = ExoPlayer wrapped by CastPlayer** (`playback/PlayerManager.kt`). `CastPlayer.setLocalPlayer`
transparently routes to Chromecast when connected and back to local on disconnect. Because the
Chromecast pulls the stream directly, casting continues when the app is minimized. If Cast isn't
available it falls back to the bare `ExoPlayer`. Buffer durations are tuned small for fast channel
zapping. Every `MediaItem` gets an explicit MIME type — required or casting crashes.

**Casting & the HLS variant.** A stock Chromecast receiver plays HLS (`.m3u8`) but generally not raw
mpegts/`.ts`. The `castAsHls` toggle (persisted in `cast_hls.txt`) makes cast playback rewrite each
URL via `hlsVariant()` (`data/StreamUrls.kt`) while local playback keeps the original stream. The
ViewModel watches `isCasting × castAsHls` and reloads the current channel when either changes.

**Adaptive UI in `ui/TvApp.kt`.** `TvApp` picks layout by width at the 720.dp breakpoint:
- `CompactLayout` (phone) — bottom `NavigationBar` + Navigation-Compose graph with a full-screen
  `player` route, plus a `MiniPlayer` bar when a channel plays and you're browsing.
- `WideLayout` (tablet/unfolded) — `NavigationRail` + channel list + an always-present player pane,
  with collapse-list and full-screen toggles.

  On **Android TV** (detected via `Context.isTelevision()`, leanback feature / UI mode), selecting a
  channel jumps straight to full-screen; the Cast button and PiP are hidden.

**Picture-in-Picture** is driven from `MainActivity`, not Compose. On Android 12+ it auto-enters
(via `setAutoEnterEnabled`) whenever a channel plays locally and not casting; on 8–11 it enters
manually in `onUserLeaveHint()`. PiP is disabled while casting and on TV. `configChanges` on the
Activity keeps folding/rotating from restarting playback. In PiP, `TvApp` renders only the video
surface.

## Conventions

- Media3 APIs are largely `@UnstableApi`; the module opts in globally in `app/build.gradle.kts`
  (`kotlin.compilerOptions.optIn`) — no need to annotate individual usages.
- Repository writes run on `Dispatchers.IO` and are wrapped in `guarded {}` (sets loading/error
  state, catches exceptions into `error` flow). File reads/writes are `runCatching`-guarded so a
  corrupt/missing file degrades to a default rather than crashing.
- Dependencies are managed via the version catalog (`gradle/libs.versions.toml`), referenced as
  `libs.*`.
