# Levyra lyrics feature port: integration pass 3

This pass builds on the earlier crossfade, section-detection and phrase-matching work. The implementation is adapted to Felicity's existing `LrcData` / `LrcEntry`, `LrcRepository`, Hilt, XML Fragment and `FelicityLrcView` architecture. It does not import Levyra's Room database or Compose screen stack.

## Implemented in this pass

### Multi-provider lyrics search
- Added `MultiProviderLyricsEngine`.
- Queries Felicity's LRCLib repository and the LyricsPlus mirror endpoints in parallel, with a bounded mirror-search deadline.
- Parses LyricsPlus timed and plain results into Felicity's LRC/plain-text parser pipeline.
- Carries LyricsPlus line translations, transliteration and word-level timing into Felicity `LrcEntry` fields when supplied by the provider.
- Ranks synced results ahead of plain results and honours the user's saved provider/version selection.
- Persists provider candidates as a local SharedPreferences fallback cache for offline reuse.
- Uses bounded HTTP timeouts; provider failures do not prevent results from the other provider being used.

### Lyrics versions
- Exposes all LRCLib results returned by its search endpoint plus a LyricsPlus result when available.
- Adds a version picker to the Lyrics panel when multiple candidates are found.
- Persists the selected version per normalized title/artist key.
- Saves selected local-track lyrics to the existing sidecar format, preserving Felicity's existing storage behaviour.

### Translation
- Adds optional line translation to the system language through the Google Translate web endpoint (`translate.googleapis.com`).
- Translation is opt-in from Lyrics Studio, cached per line and target language, and concurrency-limited to four requests.
- Original lyric text and timestamps remain intact; translated text is supplied as Felicity's secondary/adlib line.
- Translation is capped at the first 80 lines per refresh and has a 30-second overall timeout to avoid unbounded network work.

### Cross-track phrase search
- Adds a lyric-phrase search action to Lyrics Studio.
- Queries LRCLib candidate results and verifies the phrase against returned lyric lines with Felicity's existing fuzzy phrase matcher.
- Displays verified title, artist, matched snippet and score-ordered results.
- This is provider-indexed phrase search, not a complete scan of every local audio file on the device.

### Lyrics Studio
- Adds a dedicated Studio entry point to the Lyrics panel.
- Live controls for text size, line spacing, inactive-line opacity, accent glow and alignment.
- Five style presets: Classic, Karaoke, Minimal, Focus and Cinematic.
- Optional translation toggle; switching it reloads the current track.
- Settings persist through Felicity's existing preference layer and are reapplied when the Lyrics panel is recreated.
- Existing word-synced karaoke rendering remains native to `FelicityLrcView` and is used automatically when a provider supplies word timing.

## Existing functionality retained
- Earlier dual-player/crossfade work and equal-power crossfade math remain in the project.
- Existing romanization, section detection and phrase matcher remain in place.
- Existing Felicity LRC parser, editor, sidecar storage, sync-offset handling and player UI remain the source of truth.

## Validation and known limitations
- XML parsing and archive integrity are checked when packaging.
- Android Gradle compilation could not be run in this environment: the wrapper requires Gradle 9.8.1 and network DNS access to `services.gradle.org` is unavailable.
- Provider availability depends on third-party endpoint uptime and their response formats. The LyricsPlus parser targets the `/v2/lyrics/get` payload shape used by Levyra.
- Translation uses an unofficial Google Translate web endpoint; it can change or rate-limit requests. It is opt-in and failure-safe, leaving original lyrics visible.
- Phrase search verifies LRCLib search candidates; it does not index the entire on-device music library.
- This archive is a source project archive, not a Git clone and contains no Git history.

## Karaoke renderer enhancement pass
- Repaired the Studio glow toggle: it now configures an actual blurred text-glow pass rather than only changing the accent colour. Glow radius, intensity and colour are independently configurable.
- Added progressive left-to-right fill for the currently active word using its start/end timestamps. Playback ticks invalidate the view while a word is active so the fill animates continuously. Previously sung words remain fully accented and upcoming words remain dimmed.
- Added a configurable inactive-line alpha API and a frosted-glass panel with a subtle border, both controlled by Lyrics Studio.
- Expanded Lyrics Studio with glow radius, glow intensity, glass opacity, progressive-fill and glass-panel controls. Settings persist and are reapplied when Studio opens; Reset restores defaults.
- Progressive fill and glow require true word timing. Plain line-timed LRC continues to render safely without fabricated word timing.
- Added live presentation controls for active-line emphasis (subtle scale), edge blur strength and background dimming. These work alongside the existing centre-focused scroll/fade presentation and the new glass panel.

## Dual-player migration groundwork (2026-11-03)

Added `DualPlayerCoordinator`, a small role-ownership primitive for two persistent Media3
players. It validates that the two slots are distinct and provides an atomic logical role
promotion without performing player operations itself.

This is intentionally groundwork, **not yet a production switch to dual-main-player playback**.
The current service still uses its established single session player plus temporary crossfade
player. Promoting a standby safely requires coordinated changes to MediaLibrarySession,
player listeners, queue mutation, audio focus, analytics, audio pipeline ownership, USB/AAudio/
Oboe routing, and error recovery. In particular, the current DSP renderer path owns stateful
processors and cannot be shared between concurrently active ExoPlayer instances. Those paths
must be made independent or explicitly excluded before enabling dual-player crossfade.
