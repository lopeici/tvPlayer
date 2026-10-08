# TODO

Review findings, most important first.

## Bugs

- [x] 1. **Duplicate stream URLs crash the channel list.** Playlists that list the same URL under
      two groups produce duplicate `Channel.key`s; `LazyColumn(key = { it.key })` throws
      "Key was already used" (Channels/Favorites/Recents/PlaylistEdit). Also confuses now-playing
      detection.
- [x] 2. **Commas inside attributes break channel names.** `M3uParser` takes the first comma as the
      name separator, so `group-title="Movies, HD",HBO` yields the name ` HD",HBO`.
- [x] 3. **Zapping isn't saved to Recents.** Only `play()`/`jumpToNumber()` record recents;
      next/previous, D-pad and channel keys don't, so TV auto-resume returns to the wrong channel.
- [x] 4. **TV auto-resume can start the wrong channel.** If the last channel isn't in the filtered
      `visible` list, `indexOfFirst` = -1 is coerced to 0 and `visible[0]` plays instead.
- [x] 5. **Toggling "Cast in HLS" restarts local playback.** The cast watcher's `combine` re-emits
      `false` when the toggle changes while not casting, reloading the stream.
- [x] 6. **Play queue lives in the ViewModel, player is app-scoped.** Backing out while casting and
      reopening gives an empty queue: no now-playing, no mini-player, wrong PiP state.
- [x] 7. **http↔https stream redirects fail.** ExoPlayer's default HTTP data source disallows
      cross-protocol redirects. Use `OkHttpDataSource` with a shared client and User-Agent.
- [x] 8. **Compressed EPG detection misses common URLs.** Gunzip is decided by `url.endsWith(".gz")`,
      which fails for `guide.xml.gz?token=…` or gzip served without `.gz`. Sniff magic bytes.
- [x] 9. **Deleting a playlist leaves its favorites/recents behind**, despite the dialog saying they
      are removed; orphans also eat into the 50-entry recents limit.
- [x] 10. **EPG progress bars freeze.** `currentProgrammes` only emits when a programme changes, so
      the progress read at composition time goes stale.

## Robustness

- [x] JSON writes aren't atomic — a crash mid-write silently resets favorites/playlists/hidden.
      Write to a temp file and rename (or `AtomicFile`).
- [x] `guarded {}` catches `CancellationException` and shows it as an error. Rethrow it.
- [x] `_loading` is shared by all operations; overlapping refreshes clear it early. Use a counter.
- [x] Recover automatically from `ERROR_CODE_BEHIND_LIVE_WINDOW` (`seekToDefaultPosition()` +
      `prepare()`); consider one silent retry on network errors before showing the overlay.
- [x] A stream that ends mid-queue silently advances to the next channel. (Fixed by loading only
      the current channel into the player — see the queue item under Performance.)
- [x] Repository reads its JSON files on the main thread at startup (forced by `seedIfNeeded` in
      `Application.onCreate`).

## Performance (large playlists)

- [x] The whole visible list becomes the player queue (tens of thousands of `MediaItem`s; huge cast
      queue). Use a window around the selected channel, or zap in the ViewModel.
- [x] Search filtering runs on the main thread per keystroke — add `debounce` +
      `flowOn(Dispatchers.Default)`. `recentChannels` does a linear search per key; use a map.
- [x] EPG cache keeps every guide channel — pass the playlist's `tvgId` set to `XmltvParser`.
- [x] Playlist download is read into one `String`; parse from the stream instead.
- [x] `isTelevision()` runs on every recomposition in `PlaylistsScreen`/`CastButton`; provide it
      once via a `CompositionLocal`.

## Features

- [x] Auto-fill the EPG URL from `url-tvg` / `x-tvg-url` in the `#EXTM3U` header.
- [x] Per-channel headers from `#EXTVLCOPT:http-user-agent=` / `http-referrer=`.
- [x] Use `tvg-chno` for "Go to channel" instead of the position in the filtered list.
- [x] Match EPG channels by name (`tvg-name` / display-name) when there's no `tvg-id`.
- [x] Auto-refresh playlists older than a set age (e.g. 24h).
- [x] `MediaSessionService` (`media3-session` is already a dependency but unused): lock-screen and
      notification controls, headset buttons, Android TV now-playing card, foreground service.
- [x] PiP: use the video's real aspect ratio; add play/pause and next actions.
- [x] Wrap-around zapping past the last/first channel.

## Build, security, tests

- [x] Release build isn't shrunk (`isMinifyEnabled = false`; debug APK is 27 MB). Enable R8 +
      `shrinkResources`, narrow the keep rules for `media3.**` / `gms.cast.**`.
- [x] `allowBackup="true"` backs up `filesDir`, including Xtream URLs with credentials. Exclude via
      `dataExtractionRules` or disable backup.
- [x] `versionCode = 1` never changes; derive it from the git commit count.
- [x] Tests only cover `M3uParser`. Add `hlsVariant`, `HiddenState.isHidden`, XMLTV time parsing.
- [x] Add a "Share crash log" action so `crash_log.txt` can be retrieved on device.
- [x] Move hard-coded UI strings to `strings.xml`.
- [x] Update lagging dependencies (coroutines, serialization, OkHttp 4, navigation, lifecycle).
