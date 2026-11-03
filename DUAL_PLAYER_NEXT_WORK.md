# Dual-player next work

The service now owns two long-lived ExoPlayer instances. The primary remains authoritative; the
standby is muted, paused, has no audio-focus request, and uses its own Android-mixer renderer/DSP
pipeline. The coordinator tracks the two player roles and is recreated whenever the primary is
rebuilt for decoder/audio-mode changes.

Remaining before audible dual-player transitions can be enabled:

1. Mirror the authoritative media queue and current playback parameters to standby without
   triggering duplicate play statistics or user-facing item-transition callbacks.
2. Implement standby preloading with local-only and online-source policy checks.
3. Add explicit audio-focus/noisy-device ownership and propagate pause/resume to both players.
4. Route visualiser output to the audible mix (or selected active stream) and avoid standby-only
   FFT data replacing the active visualiser.
5. Perform MediaLibrarySession player reassignment and observer rebinding during a guarded,
   rollback-capable handoff.
6. Update decoder fallback and device-output rebuilds to rebuild both players transactionally.
7. Add service-level tests and validate on physical Android hardware, including USB DAC attach/detach.

Do not enable crossfade promotion until these are complete and the Android build passes.

### Completed in this increment
- Primary queue mirroring into the permanent standby player.
- Standby pre-positioning at `nextMediaItemIndex` and silent `prepare()` of the next logical item.
- Queue changes and natural item transitions trigger resynchronization.
- Standby remains muted and does not request audio focus.

### Next integration target
- A guarded transition transaction that transfers the already-prepared standby item into audible playback, updates MediaSession and service observers, then turns the former active player into the next standby without releasing either player.

## Queue/preload synchronization follow-up

The primary player listener now refreshes the standby target on `onMediaItemTransition`, repeat-mode changes, shuffle-mode changes, and playback-parameter changes, in addition to timeline edits. This closes a stale-preload case where the current media item could advance without a timeline mutation. The standby receives updated speed/pitch immediately and remains paused/muted.

## Active-player promotion transaction (implementation step)

- Added `promoteStandbyPlayerAfterFade()` to the playback service.
- Promotion is restricted to the Android shared-mixer path; USB DAC, AAudio and Oboe routes are rejected until both renderer factories can guarantee compatible simultaneous output and role reversal.
- The transaction rebinds player/analytics listeners, swaps the active and standby ExoPlayer references, swaps their independent DSP-manager references, reattaches visualizer/equalizer ownership, and points the MediaLibrarySession at the promoted player.
- The outgoing player is muted/paused and retained as the permanent standby instance; the queue-preload synchronizer then prepares its next target.
- A rollback path restores player references, listeners, DSP ownership, and MediaSession if a handoff step throws.
- This is the promotion primitive, not yet the end-to-end crossfade path: the legacy temporary-player crossfade still does not invoke it. A future integration step must call it only after a successful dual-player fade and must test all route/lifecycle cases.

## Next validation tasks after permanent-player fade integration

1. Compile `:engine:compileDebugKotlin` and run the engine unit tests in an environment with the Gradle 9.8.1 distribution available.
2. Verify a complete transition on Android AudioTrack, including notification/MediaSession controls and track-stat updates.
3. Test pause/resume, manual next/previous, seek, queue replacement, repeat/shuffle changes, and service destruction during the fade.
4. Confirm native USB DAC/AAudio/Oboe routes remain on the guarded non-overlap path.
5. Only then remove the now-unused temporary-player compatibility code and consider enabling dual overlap for additional output backends.
