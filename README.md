# mz music

A native offline Android music player built with Kotlin, Jetpack Compose and Media3.
Android 8.0+ (API 26); version 0.3.0, version code 6.

![mz music logo](design/mz-music-icon.svg)

This repository contains source code, synthetic test generators and local build tools.
No music, personal library data, original signing credentials or prebuilt APKs are included.

## Included

The app uses a custom **mz + heart** logo in the launcher and header. Vector artwork is in `design/`; Android themed icons use the same silhouette.

- Device music library with title, artist and album artwork when available; sensible metadata/artwork fallbacks. Duration and playback progress appear in Now Playing.
- Minimal library and Now Playing screens, mini-player, play/pause, previous/next, and seeking.
- Songs loads 200 results at a time using Load more, up to 2,000 displayed results. Search queries MediaStore by title, artist or album before that limit, so it can find songs beyond the initial page. Selecting a song queues the displayed results, starting at that song. Favorites and playlists queue their displayed collection instead.
- Background playback with a media foreground service, media notification, and lock-screen controls.
- Standard Android media-button integration for connected Bluetooth headphones/speakers/cars. Pair and select the output in Android's settings; the app does not scan for or pair Bluetooth devices.
- Audio focus handled by ExoPlayer, and pause on the platform's headphone-disconnection/noisy event. Reconnecting a device alone does not autoplay.
- Queue, track, and position saved privately on the device. A recreated service restores paused; reopening the UI while the existing service is playing keeps it playing. Playback progress is checkpointed every five seconds and at transitions/pause/seek, so abrupt process termination may lose up to approximately five seconds of progress.
- Errors for missing, inaccessible, and unsupported tracks. Next/Previous can leave a failed track from both the app and system media controls. A single bad file stops with an error rather than silently skipping a whole library.

## Playlists, favorites and queues

- **Playlists:** create, rename, delete, play, and reorder with Move up/down in a song menu. Names are unique ignoring case, up to 60 characters; limits are 100 playlists and 2,000 songs per playlist. A playlist contains each device song once. Delete/remove affects references only, never your files. Editing a saved playlist does not change the active playback queue.
- **Favorites:** add/remove from a song menu, browse the Favorites tab, and search favorites. Up to 2,000 favorites are saved privately. Missing/deleted files remain in saved collections so you can remove references deliberately; playback gives the existing actionable error.
- **Search:** search Songs by title, artist or album across the device MediaStore, or filter Favorites. `%`, `_`, backslashes and quotes are treated as literal search text. The current browser shows at most 2,000 matching results; refine the search for larger libraries. Matching follows Android's MediaStore/SQLite case handling.
- **Queue:** open Queue from the library or Now Playing. Tap an entry to play it; use its menu to move or remove it. Save as playlist stores an independent named collection in displayed order, with repeated songs included once. A playing/paused current item is retained when other items move; removing the current item advances according to the player's queue behavior.
- **Play next / Add to queue:** available in each song menu, including Now Playing. Add to queue appends and does not start playback. Play next inserts immediately after the current track's shuffled traversal position, keeps paused playback paused, and turns Repeat one off so the new item can play next. Both respect the existing 2,000-entry queue limit. Queue duplicates are allowed.
- **Shuffle/repeat:** toggle Shuffle and cycle Repeat off → all → one → off in Queue or Now Playing. Defaults are off. Modes restore with a saved nonempty queue, paused. The shuffle traversal is regenerated on process recreation; displayed queue order is retained. Under shuffle, the queue screen explicitly labels its displayed order as distinct from playback order.

Collections use a private Room database with transactional edits, foreign keys and an exported schema. They survive process recreation and local APK updates; backups/device transfer remain disabled. Clearing listening data removes the queue/position but preserves deliberately saved playlists, favorites and sound settings. Uninstalling or clearing Android app storage removes those collections. Playlist export/import is future work.

This release records **no listening history**, skip statistics, time/day patterns or recommendations. Learning remains a separate, opt-in future feature. Private listening continues to hide system metadata; the authorized in-app queue and library remain visible to you.

## Sound controls

Open **Sound** from the library or Now Playing screen. The default is **Original**, which bypasses all app-added effects. **Tuned** enables your saved EQ, gain settings and limiter. Switching is smoothed; the comparison is not loudness-matched. Switching from Tuned to Original requires confirmation because removing attenuation may make playback much louder.

- **10-band EQ:** 31, 62, 125, 250, 500 Hz; 1, 2, 4, 8, 16 kHz. Each band has ±12 dB adjustment in 0.5 dB steps. Bands at/above the current source's Nyquist frequency are disabled. Reset to Flat resets EQ and preamp while keeping the global leveling preference.
- **Headroom and preamp:** automatic attenuation accounts for the combined EQ response. The displayed headroom is calculated at the source sample rate, or previews 44.1 kHz before playback. Additional preamp attenuation ranges from −12 to 0 dB. Positive EQ boosts can therefore make overall playback quieter; this provides room for peaks.
- **Limiter:** always active in Tuned, with a 5 ms lookahead, linked gain across channels, 100 ms release, and a −1 dBFS sample-peak ceiling. This is not a true-peak guarantee. Original bypasses it; during the short crossfade, Original peaks can exceed the Tuned ceiling. This is signal protection, not hearing protection.
- **Presets:** Flat is protected; Headphones and Car start flat and can be edited/saved. Save as creates a named preset, and custom presets can be renamed/deleted. Deleting a selected preset preserves the current sound as unsaved settings. Current edits and selected mode survive restarts. Preset changes do not change the global leveling mode. Selection is manual; no Bluetooth-device mapping or extra Bluetooth permission is used.
- **Volume leveling:** Off, Track or Album. Uses existing ReplayGain metadata in ID3 TXXX, FLAC/Vorbis comments and MP4/M4A iTunes freeform tags. Album mode uses album gain, without substituting track gain if it is missing. Missing/invalid tags leave leveling at unity and show an explicit message. Positive tag boosts are capped to +6 dB by default, and the applied/tag gains are displayed when capped. Disabling that limit requires confirmation. This limit is a global preference, independent of presets. There is no loudness scan, tag writing, or volume adjustment based on file names. Unrecognized WAV tags report no data.

The app keeps normal speed and original file URIs. Its custom processor runs on Media3's standard 16-bit integer PCM path with double-precision internal calculations; it adds no resampling. Encoded passthrough and hardware audio offload are disabled so processing cannot be silently bypassed. Original is sample-exact at the processor input after Media3's decoding/conversion; it is not a promise of bit-perfect or high-resolution output from the phone. Android, system EQ, Bluetooth codecs and the output device can still affect sound. Physical listening and battery impact remain to be tested on your devices.

No backend, accounts, analytics, internet permission, Bluetooth scanning permission, or cloud backup. Music files are read, never copied, edited, or deleted by the app. Only the **disposable emulator test runner** modifies its own synthetic QA files. Android 8–9 requires a broader storage permission even though this app reads audio only.

## Privacy controls

Under Sound → Privacy & saved data, **Private listening** replaces player/system/car titles and artwork with generic metadata and blocks app screenshots and recent-app previews. The library remains visible inside the app. It cannot recall metadata already seen or cached by another device. Unplayed queue entries are always redacted in legacy Android media queues, and external Media3 controllers cannot browse the full timeline.

**Clear listening data** stops playback and removes the persistent queue and position; it leaves your music, playlists, favorites and sound preferences intact. The success message appears only after the store acknowledges the latest write. Save failures are shown instead of being silently ignored. Preferences remain Android-sandbox private; this is not protection against a compromised OS or root.

## Build locally

Requirements: Python 3, JDK 17, Android SDK platform `android-37.0`, build-tools
`36.0.0`, and platform-tools. The Gradle wrapper pins Gradle 9.6.0; Android
Gradle Plugin 9.5.1 and dependency versions/checksums are pinned in source.
Set these variables to your own installations:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./tools/build.sh
```

The first build downloads dependencies. Playback itself needs no network permission.
The script creates an owner-only `.local-signing/` directory with a random local
password and a new signing key, then builds a non-debuggable release, runs JVM
tests and lint, and writes the APK, source ZIP and checksums to `dist/`.
Packaging requires a Git checkout with reviewed source staged/tracked; it excludes
local validation reports and checks the public-source guard before archiving.
Run `python3 tools/prepare_signing.py` with `JAVA_HOME` configured before using
Gradle directly or syncing a fresh checkout in Android Studio.

**A self-built APK has a different signing key from any previously distributed
personal build.** It cannot update that installation unless you privately retain
and use its matching credentials. Do not uninstall an existing installation
without considering loss of playlists and settings: collection export is not yet
implemented. Back up your own `.local-signing/` securely; never commit or share it.
The certificate pin inside that directory detects accidental key changes.

After building, install on your own phone through Android's package installer or:

```sh
adb -s YOUR_DEVICE_SERIAL install -r dist/mz-music-0.3.0-release.apk
```

## Architecture and validation

- `library/`: MediaStore queries, permission handling and song metadata.
- `playback/`: one service-owned ExoPlayer, trusted MediaSession controllers and private saved queue.
- `sound/`: app-owned DSP, ReplayGain parsing, presets and service-authoritative settings.
- `collection/`: Room playlists and favorites with transactional edits.
- `ui/`: Compose screens controlled through MediaController.

See [VALIDATION.md](VALIDATION.md) for recorded checks and reproduction instructions,
[SECURITY.md](SECURITY.md) for safeguards and limits, and
[PUBLIC_SOURCE.md](PUBLIC_SOURCE.md) for publication hygiene.

## Next improvements

1. Optional local listening history and explainable time/day suggestions, with pause/delete/reset controls and no learning during private sessions.
2. Album/artist browsing, playlist import/export, gapless-album verification, sleep timer and richer missing-file recovery.
3. Android Auto library browsing, widgets and separate investigation of USB DAC/high-resolution output.

These are future work. No accounts, backend, hosting or store publication is required.

## Licensing

No license granting general reuse rights has been selected for the original project
code or artwork. Public visibility alone is not an open-source license. Third-party
components retain their respective licenses; existing notices are preserved.
