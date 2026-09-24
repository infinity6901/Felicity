# Felicity — Roadmap Completion Changes

This documents every file added or edited while working through the README roadmap.
Nothing in here has been compiler-verified (no Android SDK / emulator available in the
environment this was built in) — treat it as a strong first draft to build and test, not
a finished, shipped PR.

## 1. Sleep Timer

**New**
- `preferences/.../preferences/SleepTimerPreferences.kt`
- `engine/.../engine/managers/SleepTimerManager.kt`
- `music/.../dialogs/player/SleepTimerDialog.kt`
- `music/src/main/res/layout/dialog_sleep_timer.xml`
- `music/src/main/res/values/strings_sleep_timer.xml`

**Edited**
- `engine/.../engine/services/FelicityPlayerService.kt` — registers as a
  `SleepTimerManager.Listener`, does an 8s volume fade before pausing, hooks
  `onMediaItemTransition`'s `MEDIA_ITEM_TRANSITION_REASON_AUTO` case for "stop after this
  song".
- `music/.../extensions/fragments/BasePlayerFragment.kt` — new `abstract val sleepTimer`,
  click listener, icon dim/highlight reflecting armed state.
- `DefaultPlayer.kt`, `CarouselPlayer.kt`, `PlayerFaded.kt` — bind `sleepTimer`.
- `fragment_default_player.xml`, `fragment_player_carousel.xml`,
  `fragment_player_faded_waveform.xml` — added the sleep-timer icon button (reuses existing
  `ic_timer` drawable).

## 2. Global Search Provider

**New**
- `music/.../search/FelicitySearchProvider.kt` — suggestions `ContentProvider`.
- `music/src/main/res/xml/searchable.xml`

**Edited**
- `MainActivity.kt` — completed the previously-stubbed `handleSearchIntent()`:
  `ACTION_SEARCH` opens the Search panel with the query pre-filled; `MEDIA_PLAY_FROM_SEARCH`
  builds a fresh queue from matches and plays it; `ACTION_VIEW` on a suggestion tap resolves
  and plays that exact song.
- `AndroidManifest.xml` (music) — `SEARCH`/`MEDIA_PLAY_FROM_SEARCH` intent-filters,
  `android.app.searchable` / `android.app.default_searchable` meta-data, provider
  registration, `MainActivity` set to `launchMode="singleTop"`.

⚠️ **Worth checking:** `singleTop` is a real behavior change beyond just search — a quick
regression pass on notification/widget taps and multi-window is worth doing.

## 3. More Widgets

**New**
- `widget/CompactWidgetProvider.kt` + `res/layout/widget_compact.xml` +
  `res/xml/widget_compact_info.xml` (4×1 now-playing bar)
- `widget/MinimalWidgetProvider.kt` + `res/layout/widget_minimal.xml` +
  `res/xml/widget_minimal_info.xml` (2×1 icon-only)

**Edited**
- `FelicityPlayerService.kt` — `broadcastWidgetUpdate()` was hardcoded to a single
  component (`FelicityWidgetProvider`), which meant any new widget class would render once
  and then never update again. Fixed to loop over a list of known widget class names.
- `AndroidManifest.xml` (music) — registered both new widget receivers.

⚠️ Neither new widget has `android:previewImage` set — no way to generate real preview art
in this environment. They'll show Android's generic fallback preview until real art is
added.

## 4. Multiple Player Interface Styles (added "Vinyl")

**New**
- `ui/player/VinylPlayer.kt` — spins a decorative record behind the album art while
  playing (paused, not reset, when playback pauses), driven by
  `MediaPlaybackManager.playbackStateFlow`.
- `res/layout/fragment_player_vinyl.xml` — same control chrome as the Default layout
  (diffed line-by-line against the real file), redesigned art area.
- `shape_vinyl_disc_base.xml`, `shape_vinyl_groove.xml`, `shape_vinyl_center_label.xml`,
  `shape_vinyl_spindle.xml`
- `strings_vinyl_player.xml`

**Edited**
- `UserInterfacePreferences.kt` — `PLAYER_INTERFACE_VINYL` + `isVinylInterface()`.
- `PreferenceFragment.kt` — added to the player-style picker.
- `MainActivity.kt`, `MediaFragment.kt`, `BasePlayerFragment.kt` (shared-element `when`) —
  all three independently decide which player fragment to open; all three needed a Vinyl
  branch. The `BasePlayerFragment` one is a non-exhaustive `Int` `when` with no `else` — a
  missed branch there fails silently at runtime, not at compile time. Worth remembering for
  a 5th style later.

⚠️ **Unverified assumption:** `FelicityPager` is sized to a fixed 190dp square instead of
its usual full-bleed size. I inferred from its `onMeasure()` (it derives sizing from its own
measured bounds) that this is safe, but never saw it rendered.

## 5. Cue Sheet Support (parsing + discovery + schema only — playback wiring removed)

**New**
- `repository/.../parsers/CueSheetParser.kt` — parses `.cue` sheets into `CueSheet`/
  `CueTrack` (title/performer/start-end in ms).
- `AudioScanner.kt` — `getCueFiles()` for both filesystem and SAF scanning.

**Edited**
- `AudioDatabase.kt` — `MIGRATION_22_23`, adds nullable `cue_start_ms`/`cue_end_ms` to the
  `audio` table (version bumped 22 → 23). This is plain SQL against the table, not the
  `Audio` entity class, so it compiles and runs fine even though nothing maps those columns
  to a Kotlin property yet.

**Removed (previously in this changelog) — struck back out so the project compiles as-is:**
The `MediaItem.ClippingConfiguration` wiring in `MediaPlaybackManager.toMediaItem()` and the
two by-ID/by-search resolution paths in `FelicityPlayerService.kt` referenced
`audio.cueStartMs` / `audio.cueEndMs`, which don't exist on the real `Audio.kt` (not present
in this export). That was a hard compile error, so all three were reverted to their
pre-cue-sheet form at the user's request.

🛑 **Net state:** `CueSheetParser.kt`, the scanner additions, and the DB migration are all
still here and compile-safe on their own — they don't touch `Audio.kt` or the player at all.
Nothing currently *uses* them: no playback clipping, and nothing yet reads a found `.cue`
file and inserts one `Audio` row per track (`AudioDatabaseLoader.processAudioFiles()` was
never touched — see the original reasoning above, still applicable). Picking this back up
later means: (1) add `cueStartMs`/`cueEndMs` to `Audio.kt`, (2) wire `CueSheetParser` into
the scan pipeline, (3) reintroduce the three `ClippingConfiguration` call sites reverted here.

## Post-delivery fixes (found via real build/run testing)

- `fragment_player_vinyl.xml` used `"85%"`-style percentage strings for `layout_width`/
  `layout_height` inside a plain `FrameLayout` — that syntax only works in `ConstraintLayout`
  via its dedicated percent attributes. Converted to explicit dp values against the 280dp
  disc size.
- `sleep_timer` was missing from the `layout-land/` (landscape) copies of
  `fragment_default_player.xml`, `fragment_player_carousel.xml`, and
  `fragment_player_faded_waveform.xml` — only the portrait versions were edited originally.
  This made `binding.sleepTimer` nullable (ViewBinding does that whenever a view isn't
  guaranteed present across every configuration of a layout name) and failed the build.
  Added the button to all three landscape copies too.
- `FelicitySearchProvider`'s `@AndroidEntryPoint` was wrong — Hilt does not support that
  annotation on `ContentProvider` (only Activity/Fragment/View/Service/BroadcastReceiver).
  Replaced with Hilt's `@EntryPoint`/`EntryPoints.get()` pattern, resolved lazily on first
  use rather than eagerly (a ContentProvider's `onCreate()` can run before
  `Application.onCreate()` finishes on some Android versions).
- `FelicitySearchProvider`'s `arrayOf(index.toLong(), audio.title, ...)` mixed `Long` and
  `String` values, so Kotlin inferred an awkward `Comparable<*> & Serializable`
  intersection type instead of `Any?`. Made it explicit: `arrayOf<Any?>(...)`.
- `Online.kt` crashed with `NoSuchMethodException` on `OnlineViewModel.<init>` —
  none of `PanelFragment`/`MediaFragment`/`ScopedFragment` actually carry
  `@AndroidEntryPoint` (an earlier wrong assumption, based on `Search.kt` appearing to work
  without one — it actually works because it scopes its ViewModel to `requireActivity()`,
  piggybacking on `MainActivity`'s Hilt setup instead). Added `@AndroidEntryPoint` directly
  to `Online`, matching how every other fragment needing its own injection does it
  (`DefaultPlayer`, `CarouselPlayer`, `PlayerFaded`, `VinylPlayer`).
- `fragment_online.xml`'s RecyclerView used `android:layoutAnimation="@anim/grid_list_animation_controller"`
  — a `GridLayoutAnimationController` resource, which only works with a `GridLayoutManager`.
  This list uses a plain `LinearLayoutManager` (see the earlier `GridRecyclerView` caveat),
  so the mismatch threw a `ClassCastException` at draw time. Removed the attribute.
- **Jamendo's client_id had no in-app settings UI at all** — it was a hardcoded placeholder
  constant (`AppConstants.JAMENDO_CLIENT_ID`) that required editing source and rebuilding.
  Replaced with a real settings field: `preferences/JamendoPreferences.kt` (SharedPreferences-backed),
  a new "Online" section in Settings with a "Jamendo client ID" row, and
  `dialogs/app/JamendoClientIdDialog.kt` for entering/editing it. `JamendoRepository` now
  reads from this preference instead of the removed constant.

## Post-delivery fixes, round 2

**Jamendo settings still not visible — this was a placement problem, not a missing-code problem.**
The Settings entry I added earlier was real and present (`createUserInterfacePanel()` →
`UserInterface.kt`), but I'd tucked it inside the "User Interface" tab, sandwiched between
unrelated toggles — a genuinely unintuitive place to look for an API key. Rather than just
relocate it to another guess of a settings tab, fixed it at the actual point of need: tapping
the Jamendo chip in the Online panel itself now opens the client-ID dialog immediately if
none is set yet (`Online.kt`'s source-chip click handler). The Settings entry stays too, for
changing it later without re-tapping the chip.

**The player screen showing stale local-song info during online playback was a real,
structural gap — bigger than what I'd flagged when I built `playOnlineTrack()`.** Root
cause: `BasePlayerFragment`'s title/artist/album/pcmInfo/waveform are driven entirely by two
places — `updateState()` (called once, at `onViewCreated`) and `onAudio(audio: Audio)` (the
reactive per-track-change callback) — and *both* read exclusively from
`MediaPlaybackManager.getCurrentSong()`, an `Audio`-typed getter that `playOnlineTrack()`
never touches. So the audio playing was correct; the screen showing it was not.

Fixed with a parallel, additive path rather than touching that `Audio`-hardwired logic:
- `MediaPlaybackManager.currentOnlineTrackFlow: StateFlow<OnlineTrack?>` — set by
  `playOnlineTrack()`, cleared by `setSongs()`/`playSong()` (so resuming local playback
  correctly hands control back to the normal `Audio`-based path).
- `BasePlayerFragment` now collects that flow (`handleOnlineTrackChanged()`) and, when an
  online track is active, repaints title/artist directly from it and **disables** (dims,
  doesn't just leave silently non-functional) favorite, equalizer, lyrics, shuffle, queue,
  and repeat — all of which would otherwise act on the stale local `Audio` still cached in
  `getCurrentSong()`, which is worse than doing nothing. Play/pause and seeking are left
  alone, since those already operate on the real controller state.

**Known gaps still remaining, stated plainly:**
- **Album art doesn't update.** The art pager (`ImagePageAdapter`) reads local `Audio`
  objects by list index; retargeting just its current page to `OnlineTrack.artworkUrl`
  without touching that adapter's internals wasn't something I could do with confidence in
  this pass.
- **The pager's swipe gesture isn't disabled** during online playback. Its gesture-to-playback
  wiring wasn't something I had full visibility into, so swiping left/right while an online
  track plays may produce confusing results (it acts on the local song list/position, which
  online playback never touched).
- **The seekbar shows 0 duration for radio stations** (Radio Browser has no fixed duration by
  nature — a live stream). Jamendo/Audius tracks do have known durations and should scrub
  correctly.

## Trending / discovery (before any search)

Each source now has its own real "trending" concept, verified against current API docs
rather than guessed, shown by default the moment a source is selected — before the user
types anything:

- **Radio Browser** — `GET /json/stations/topvote/{limit}`, the most-voted stations
  directory-wide right now.
- **Jamendo** — `GET /tracks/?order=popularity_month`, the same mechanism Jamendo's own
  site uses for its "popular this month" chart. Still returns empty until a client_id is set.
- **Audius** — `GET /v1/tracks/trending?time=week`, Audius's own official top-100 chart.
- **Internet Archive** — `GET /advancedsearch.php?q=mediatype:audio&sort[]=downloads desc`,
  the most-downloaded audio items archive-wide. Same two-step caveat as search results:
  still needs `resolveStreamUrl()` before playback.

`OnlineViewModel.observeSearch()` now branches on whether the (debounced) query is blank:
blank → `trendingFor(source)`, non-blank → `searchFor(source, query)` — both feed the same
result list and adapter, so switching sources with an empty search box re-fetches that
source's trending list automatically. `Online.kt`'s empty-state message was generalized
accordingly, since "nothing to show" can now mean either a failed/empty trending fetch or
a genuine no-results search.

## Post-delivery fixes, round 3 — more UI surfaces were stuck on the stale local song

The title/artist fix from round 2 was real but incomplete — three more surfaces were still
independently bound to the stale local `Audio`, each for its own reason:

- **The floating miniplayer** (`MainActivity.kt`) builds its entire swipeable item list from
  `MediaPlaybackManager.songListFlow` — i.e., the local queue — and never found out an
  online track was playing. Fixed by gating that collector (skip rebuilding while an online
  track is active) and adding a new collector on `currentOnlineTrackFlow` that overrides the
  miniplayer with a single item built from the online track. Its `onLoadArt` callback now
  also handles an `OnlineTrack` payload — turns out this needed zero new plumbing, since the
  existing `loadArtIntoBitmap(item: Any, ...)` already accepts a plain URL string; Glide's
  default loader handles that natively.
- **The album art pager**'s image provider lambda (in `BasePlayerFragment.setupPager()`)
  indexes into `MediaPlaybackManager.getSongs()[pos]` unconditionally. Fixed by checking
  `getCurrentOnlineTrack()` first and, when non-null and `pos` matches the current position,
  loading `OnlineTrack.artworkUrl` via a plain `Glide.with(iv).load(url)` instead — falling
  back to the original `Audio`-based loading for every other position. `handleOnlineTrackChanged()`
  now also calls `pager.notifyDataSetChanged()` so the current page actually re-fetches
  through this updated logic the moment online playback starts or stops.
- **The lyrics overlay** kept redrawing the old song's synced lyrics because
  `lyricsViewModel.lrcData` is a persistent, shared `StateFlow` that `playOnlineTrack()`
  never touches — the one-time `lrc.clear()` call added in round 2 wasn't enough, because
  this collector could still act on it again. Gated the collector itself: it now checks
  `getCurrentOnlineTrack()` first and clears/returns early instead of calling `setLrcData`
  with stale data.

**Still not fixed, and worth knowing why:** the waveform seekbar's amplitude bars themselves
(the visual shape behind the seekbar, as opposed to the duration/position numbers, which are
correct) still show the old song's waveform. `WaveformSeekbar.setAmplitudes(FloatArray)`
exists and could probably clear this, but its normalization logic wasn't something I could
verify handles an empty or degenerate (all-zero) array safely without the kind of testing
this session doesn't have — given how many crash-fix round-trips this feature has already
needed, I chose not to guess at one more edge case in a component I can't inspect. This is a
purely cosmetic leftover, not a functional bug.

The pager's swipe-to-change-song gesture is also still not disabled during online playback,
per the round-2 note — that remains unaddressed.

## Before merging any of this

1. Full build + install on a device/emulator — nothing here has been compiled.
2. Spot-check the Vinyl player's art sizing and the `singleTop` launch-mode change.
3. Cue sheets are parked at parsing/discovery/schema only, per above, if you want to
   finish them later.

## 6. Online sources (Radio Browser, Jamendo, Audius, Internet Archive)

online sources.

**New**
- `repository/.../online/OnlineTrack.kt` — unified model (`OnlineSource` enum + a single
  `OnlineTrack` shape) all four sources map into.
- `repository/.../repositories/RadioBrowserRepository.kt` — free internet radio, no auth.
  Hits a single mirror (`de1.api.radio-browser.info`) directly rather than doing the
  DNS-based multi-mirror failover the API is designed around.
- `repository/.../repositories/JamendoRepository.kt` — CC/free-licensed indie tracks.
  Requires a free `client_id` from devportal.jamendo.com — ships with a placeholder in
  `AppConstants.JAMENDO_CLIENT_ID` that must be replaced, and `search()` returns an empty
  list (not a crash) until it is.
- `repository/.../repositories/AudiusRepository.kt` — decentralized, artist-opt-in
  streaming. Stream URLs are the discovery node's `/stream` endpoint, which 302-redirects
  to the real CDN URL; ExoPlayer follows that automatically.
- `repository/.../repositories/InternetArchiveRepository.kt` — public-domain/CC audio.
  Two-step by necessity: `search()` never returns a playable URL (Archive.org's search API
  doesn't have one), so `resolveStreamUrl()` must be called separately, right before
  playback, against that one item's file manifest.

**Edited**
- `shared/.../AppConstants.kt` — added the three new source identifiers/placeholder
  (`RADIO_BROWSER_USER_AGENT`, `AUDIUS_APP_NAME`, `JAMENDO_CLIENT_ID`).
- `engine/.../MediaPlaybackManager.kt` — added `playOnlineTrack(track, streamUrlOverride)`.

⚠️ **Scope, stated plainly:** `playOnlineTrack()` is a minimal, deliberately
non-integrated "play this one thing now" primitive. It does not touch the local-library
queue/shuffle/stats system (`setSongs`, `originalQueue`, `shuffledQueue`), all of which are
typed around the `Audio` Room entity — an online track has no corresponding row. Practical
consequence: no queue mixing between online and local tracks, no favorites/stats for online
plays, and "skip to next" after an online track does nothing meaningful. Folding this into
the full queue system would mean making that entire pipeline polymorphic beyond `Audio`,
which is a separate, larger change than what was done here.

**Also not done:** no UI screen yet (a search panel with a source picker + result list is
the natural next step, mirroring `ui/panels/Search.kt`'s structure) — this turn was the
backend foundation only.

### 6a. Online panel UI (follow-up to the above)

**New**
- `ui/panels/Online.kt` — search-as-you-type panel, mirroring `Search.kt`'s shape but
  flattened to one result list and a 4-way source picker instead of category sections.
- `viewmodels/panels/OnlineViewModel.kt` — debounced search (400ms) across whichever
  source is selected, mirrors `SearchViewModel`'s `combine`/`debounce`/`flatMapLatest`
  pattern with a network call in place of a DB query.
- `adapters/ui/lists/AdapterOnline.kt` — reuses the existing generic
  `adapter_style_list.xml` row (title/secondary/tertiary/cover) rather than a new layout.
- `res/layout/fragment_online.xml`, `res/layout/header_online.xml`, `strings_online.xml`.

**Edited**
- `SimpleHomeViewModel.kt` — added an "Online" tile to the dashboard's Library group.
- `BaseHomeFragment.kt` — routes that tile to `Online.newInstance()`.

⚠️ **Two things I deliberately avoided rather than guessed at**, both because the actual
source files weren't in this export to verify against:
- Used a plain `RecyclerView` instead of the app's custom `GridRecyclerView` for the result
  list — `GridRecyclerView`'s internals (fast-scroll, nav-bar-padding insets) weren't
  visible to check whether they tolerate a `LinearLayoutManager` instead of a
  `GridLayoutManager`, so this list won't auto-pad for edge-to-edge nav bars the way other
  panels do.
- Highlighted the active source chip via `alpha` (1.0 vs 0.5) rather than `HighlightTextView`'s
  own `isSelected` state, since nothing else in the codebase exercises that state and I
  couldn't confirm it has a defined visual effect on this custom view. Alpha dimming is the
  same pattern already proven for the sleep-timer and repeat buttons.
