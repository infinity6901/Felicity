# Dual-player migration status

## This increment: independent DSP graph ownership

- Added `DualAudioProcessorSet`, which owns two separately constructed `AudioProcessorManager` instances.
- Enforces distinct manager, native DSP, and visualizer identities.
- Provides stable first/second slot lookup and applies persisted preferences independently to both graphs.
- Provisioned the second manager in `FelicityPlayerService`; the existing production renderer remains attached to the first manager.
- Added unit tests for processor identity and stable slot lookup.

## Important limitation

This is DSP ownership groundwork, not a completed dual-player runtime. The second manager is not yet connected to a second `ExoPlayer`, `FelicityAudioSink`, or independent native output route. MediaSession, queue, audio focus, visualizer mix selection, and decoder rebuild handoff remain on the existing single-player path. Do not enable crossfade using the second manager until the paired renderer and service transaction are implemented and tested.

## Validation

ZIP integrity and source-level checks are performed for this archive. Android Gradle compilation and device playback tests have not been completed in this environment.

## Output routing safety policy (latest increment)

Added `DualPlayerOutputPolicy`, a pure policy resolver for simultaneous playback. The existing
USB DAC, AAudio and Oboe routes are treated as exclusive unless a backend explicitly declares
independent-session capability. During overlap, those routes fall back to Android's shared
AudioTrack mixer for both players. Outside overlap, the requested route is preserved. This avoids
opening competing exclusive streams or switching the audible output path halfway through a fade.

Unit tests cover default fallback, explicit independent-session capability, and non-overlap route
preservation. The policy is a renderer-factory integration primitive; it does not by itself enable
the second production player.

## Standby renderer-factory increment

Added `DualPlayerStandbyRenderersFactory`, a dedicated Media3 renderer factory backed by the
second `AudioProcessorManager`. It constructs a separate `DefaultAudioSink`, installs only the
standby graph's processor instances, disables audio offload during overlap, and intentionally
uses Android's shared AudioTrack mixer instead of opening Felicity's singleton USB/AAudio/Oboe
router a second time. The service now provisions this factory during initialization.

This is renderer-factory groundwork only: the standby ExoPlayer is not yet created or connected
to the transition state machine, and MediaSession/queue ownership remains on the primary player.
The primary route and existing playback behaviour are unchanged.

## Permanent standby ExoPlayer lifecycle increment

The service now constructs a long-lived standby `ExoPlayer` after the primary player is built,
using `DualPlayerStandbyRenderersFactory` and the second independent DSP manager. The standby
player does not request audio focus, does not handle noisy-device events, starts muted and paused,
and does not own MediaSession or user-facing listeners. A `DualPlayerCoordinator` instance tracks
the primary/standby roles. Decoder/audio-mode rebuild paths recreate the standby alongside the
primary, and service teardown releases the standby before detaching the USB driver.

This is the first actual second ExoPlayer instance in the service, but it is intentionally not yet
fed queue items or played. The current primary remains the only user-facing playback/session owner.
No crossfade promotion is enabled until queue mirroring, listener routing, audio-focus ownership,
visualiser selection and MediaSession handoff are implemented as one transaction.

## Queue mirroring and next-item preloading increment

- Added `syncStandbyToNextQueueItem()` to mirror the primary player's full `MediaItem` playlist into the permanent standby player.
- The standby playlist is positioned at the primary player's `nextMediaItemIndex`, respecting Media3's next-item calculation (including shuffle/repeat-all semantics).
- The standby player remains muted and `playWhenReady=false`; it only calls `prepare()` to buffer/decode the next item.
- Preloading is skipped for repeat-one and when no next item exists. Empty queues clear the standby playlist.
- Standby inherits the primary playback parameters and skip-silence preference before preparation.
- Sync is triggered on primary timeline changes, media-item transitions, and standby-player construction/rebuild.
- This is queue mirroring/preparation only. It does not yet perform dual-player audible crossfade or MediaSession promotion.

### Active-player promotion primitive

A guarded service-level promotion transaction now exists. It swaps the active/standby player references and their DSP manager roles, rebinds MediaSession and listeners, and retains the outgoing player as muted standby. Promotion is intentionally rejected while USB DAC, AAudio, or Oboe is active because the two renderer factories are not yet symmetric. The primitive is not yet connected to the crossfade ticker, so this does not mean production dual-player crossfade is complete.

## 2026-11-04: Permanent-player fade integration

- Replaced the active crossfade start path with a fade using the already-prepared permanent standby ExoPlayer.
- The fade uses the existing equal-power gain curve and playback-speed-aware timing.
- The incoming player is checked against the active queue's next index before playback and throughout the fade.
- On completion, the existing guarded promotion transaction swaps player roles, listeners, analytics, DSP managers, visualizer/equalizer processors, and MediaSession ownership.
- The outgoing player is muted and paused, then reused as the next standby. No third temporary ExoPlayer is created for the new path.
- The service dispatches one logical AUTO media-item transition after promotion because the standby loaded the target before its listener was attached. This keeps the existing track-stat, sleep-timer, widget, queue-position, and snapshot handling in the normal transition path.
- Cancellation now stops and mutes the standby deck as well as cancelling the fade job.

Validation: source-level inspection completed. Gradle compilation could not start because the wrapper attempted to download Gradle 9.8.1 and `services.gradle.org` could not be resolved in this environment. Device playback, output routing, and race behaviour still require runtime testing.
