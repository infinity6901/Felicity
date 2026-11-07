package app.simple.felicity.engine.services

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.simple.felicity.engine.R
import app.simple.felicity.engine.audio.FelicityAudioSink
import app.simple.felicity.engine.managers.AudioPipelineManager
import app.simple.felicity.engine.managers.AudioProcessorManager
import app.simple.felicity.engine.managers.EqualizerManager
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.engine.managers.PlaybackStateManager
import app.simple.felicity.engine.managers.SleepTimerManager
import app.simple.felicity.engine.managers.VisualizerManager
import app.simple.felicity.engine.model.AudioPipelineSnapshot
import app.simple.felicity.engine.notifications.PlaybackErrorNotifier
import app.simple.felicity.engine.playback.CrossfadeMath
import app.simple.felicity.engine.usb.UsbDacDriver
import app.simple.felicity.engine.usb.UsbDacManager
import app.simple.felicity.manager.SharedPreferences.initRegisterSharedPreferenceChangeListener
import app.simple.felicity.manager.SharedPreferences.unregisterSharedPreferenceChangeListener
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.AudioPreferences
import app.simple.felicity.preferences.CrossfadePreferences
import app.simple.felicity.preferences.EqualizerPreferences
import app.simple.felicity.preferences.PlayerPreferences
import app.simple.felicity.preferences.ShufflePreferences
import app.simple.felicity.preferences.UserInterfacePreferences
import app.simple.felicity.repository.constants.MediaConstants
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.repositories.AudioRepository
import app.simple.felicity.repository.repositories.SongStatRepository
import app.simple.felicity.repository.utils.AudioUtils.getProperAlbum
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import app.simple.felicity.repository.youtube.YoutubeConstants
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Service responsible for managing audio playback using ExoPlayer with dynamic decoder switching support.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class FelicityPlayerService : MediaLibraryService(), SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject
    lateinit var audioRepository: AudioRepository

    @Inject
    lateinit var songStatRepository: SongStatRepository

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    /** Permanent standby ExoPlayer. It owns a separate Android-mixer renderer/DSP graph. */
    private var standbyPlayer: ExoPlayer? = null
    private var dualPlayerCoordinator: app.simple.felicity.engine.playback.DualPlayerCoordinator? = null
    private var renderersFactory: DefaultRenderersFactory? = null
    /** Independent Android-mixer renderer graph reserved for the permanent standby player. */
    private var standbyRenderersFactory: DefaultRenderersFactory? = null

    /**
     * Shared by both places [player] gets (re)built. A yt-dlp-resolved YouTube/googlevideo
     * URL is bound to the User-Agent that was used to resolve it (see
     * `YtDlpManager`/`YoutubeRepository` in the `repository` module) — ExoPlayer's own
     * default User-Agent doesn't match, and googlevideo's CDN rejects the mismatch with an
     * HTTP error surfaced here as `ERROR_CODE_IO_BAD_HTTP_STATUS`. The UA string itself
     * lives once in `YoutubeConstants.STREAMING_USER_AGENT` (repository module) and is
     * reused for the yt-dlp resolve/download requests in `YtDlpManager` too — it's a
     * superset of what every other online source (Radio Browser, Jamendo, Audius,
     * Internet Archive) already accepts, so it's set globally rather than only for YouTube.
     */
    private fun buildStreamingMediaSourceFactory(): MediaSource.Factory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(app.simple.felicity.repository.youtube.YoutubeConstants.STREAMING_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to YoutubeConstants.STREAMING_USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Encoding" to "gzip, deflate, br"
                )
            )

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        return DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
    }

    /**
     * The mediaId of the media item that was playing before the most recent item transition.
     * Used in conjunction with [previousItemEndPositionMs] and [previousItemDurationMs] to
     * decide whether the previous song was skipped.
     */
    private var previousItemMediaId: String? = null

    /**
     * The playback position (ms) captured just before the most recent item transition.
     * Populated in {@link Player.Listener#onPositionDiscontinuity}.
     */
    private var previousItemEndPositionMs: Long = 0L

    /**
     * The total duration (ms) of the previous media item captured just before the transition.
     * Populated in {@link Player.Listener#onPositionDiscontinuity}.
     */
    private var previousItemDurationMs: Long = 0L

    /**
     * Whether the player was actively playing audio just before the most recent item transition.
     * This acts as a gatekeeper so stats are only recorded for songs the user actually heard,
     * not ones they skipped past while the player was paused or idle.
     */
    private var wasPlayingBeforeTransition: Boolean = false

    /**
     * Holds the media ID of a song that needs its play recorded once the player actually
     * starts playing. This happens when a new queue is set — ExoPlayer fires the item
     * transition callback before [Player.play] is called, so [Player.playWhenReady] is
     * still false at that moment and the play would be silently missed. We park the ID here
     * and flush it as soon as playback begins.
     */
    private var pendingPlayRecordMediaId: String? = null

    /**
     * The duration of the song that is CURRENTLY loaded and ready to play, kept fresh by
     * reading [player.duration] once the player reaches STATE_READY (the only state where
     * the duration is guaranteed to be accurate).
     *
     * Why do we need this? When the user skips a track, ExoPlayer fires
     * onPositionDiscontinuity AFTER it has already transitioned its internal state to the
     * new item. At that point, player.duration already reflects the INCOMING song (or
     * C.TIME_UNSET if it is still buffering) — not the outgoing one we actually want.
     * By caching the duration here we always have the correct value ready when we need it.
     */
    private var currentItemDurationMs: Long = 0L

    /**
     * Manages the balance and downmix [androidx.media3.common.audio.ChannelMixingAudioProcessor]
     * instances. Extracted to keep audio processing logic out of the service.
     */
    // Two independent DSP graphs are provisioned for the eventual permanent dual-player path.
    // The current production player continues to use the first graph until the paired renderer,
    // output-device and MediaSession handoff migration is completed.
    private val dualAudioProcessorSet = app.simple.felicity.engine.playback.DualAudioProcessorSet()
    // These references follow the active/standby player roles and are swapped atomically
    // during promotion. The underlying DSP graphs remain independent.
    private var audioProcessorManager = dualAudioProcessorSet.first
    private var standbyAudioProcessorManager = dualAudioProcessorSet.second

    /**
     * Posts silent error notifications when a track cannot be played.
     * Initialized in [onCreate] once a valid [Context] is available.
     */
    private lateinit var playbackErrorNotifier: PlaybackErrorNotifier

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var periodicStateSaveJob: Job? = null

    /** Runs the gradual volume ramp-down when a [SleepTimerManager] countdown finishes with fade-out enabled. */
    private var sleepTimerFadeJob: Job? = null

    // ---- Crossfade ---------------------------------------------------------------------------
    //
    // A temporary, second ExoPlayer plays the first few seconds of the upcoming track while
    // [player]'s volume fades out and the temp player's volume fades in; once the fade completes,
    // [player] jumps straight to the same position the temp player had reached and takes back
    // over, so the MediaSession/notification/controllers stay bound to the one stable [player]
    // instance throughout — no swapping the player object the session was built with.
    //
    // Deliberately built with a *plain* ExoPlayer.Builder(this) rather than [renderersFactory],
    // so the temp player does NOT share [audioProcessorManager]'s native DSP chain (EQ, reverb,
    // etc.) with the main player — that chain's native processors are one shared instance tied to
    // one active audio stream, not built for two ExoPlayer instances driving it concurrently.
    // The practical effect: the few seconds of overlap play back through the stock Android audio
    // path (no custom EQ/effects yet) until the handoff completes, at which point the track
    // continues through the real pipeline exactly as normal. Also only ever engages for the local
    // queue — an [MediaPlaybackManager]-driven online (YouTube) track skips this entirely.
    private var crossfadePlayer: ExoPlayer? = null
    private var crossfadeFadeJob: Job? = null
    private var dualPlayerFadeJob: Job? = null
    private var crossfadeTickerJob: Job? = null
    private var dualFadeCooldownUntilMs = 0L

    /** The artificial silence gap used when gapless playback is off; cancelled if the user/timer intervenes. */
    private var gapJob: Job? = null
    private val isCrossfading get() = crossfadePlayer != null || dualPlayerFadeJob?.isActive == true

    /**
     * Set by [finishCrossfade] just before it seeks [player] onto the next item. That seek
     * makes ExoPlayer report `MEDIA_ITEM_TRANSITION_REASON_SEEK`, but from the app's point of
     * view it is an automatic track change — [onMediaItemTransition] consumes this flag and
     * treats the transition as AUTO so stats (skip/replay), and the end-of-track sleep timer,
     * don't misread every crossfade handoff as a manual navigation.
     */
    private var crossfadeHandoffPending = false

    /**
     * An in-memory copy of the full audio library, kept fresh by collecting the Room
     * Flow in [onCreate]. Using a Flow means we never need to hit the database on every
     * [onGetChildren] call — the list is always ready and up to date.
     *
     * Populated asynchronously on first launch; empty list is the safe default while
     * the first DB emission is still on its way.
     */
    private var cachedSongList: List<Audio> = emptyList()

    /**
     * Tracks whether we are currently in a silent FFmpeg fallback retry for a failed track.
     * When true the next decoding error on the same item is treated as a final failure,
     * the original decoder is restored and the track is skipped.
     */
    private var ffmpegFallbackActive = false

    /** The [MediaItem] that triggered the decoding error we are retrying via FFmpeg. */
    private var ffmpegFallbackItem: MediaItem? = null

    /** The decoder the user had configured before a fallback attempt was started. */
    private var preFallbackDecoder: Int = AudioPreferences.LOCAL_DECODER

    /**
     * The name of the most recently initialized audio decoder, captured via [analyticsListener].
     * Defaults to "Unknown" until [AnalyticsListener.onAudioDecoderInitialized] fires.
     */
    private var currentDecoderName: String = "Unknown"

    /**
     * The compressed source [Format] most recently delivered to the audio renderer.
     * Updated via [AnalyticsListener.onAudioInputFormatChanged]; `null` before the
     * first track is decoded.
     */
    private var currentAudioInputFormat: Format? = null

    /**
     * The currently active audio output device, or `null` if detection has not yet
     * run. Updated whenever [audioDeviceCallback] fires or [detectActiveOutputDevice]
     * is called explicitly.
     */
    private var currentOutputDevice: AudioDeviceInfo? = null

    /** Coroutine job that pushes a fresh [AudioPipelineSnapshot] every 3 seconds while playing. */
    private var snapshotPulseJob: Job? = null

    /**
     * Debounce job that coalesces rapid-fire [buildAndPushSnapshot] calls into a single
     * trailing push. During a song transition, up to 6 callbacks can fire within ~200ms
     * (decoder init, format change, item transition, state ready, playing changed, pulse).
     * Rather than pushing 6 duplicate snapshots, we cancel any pending push and schedule
     * one new push after a short cooldown — only the last call in the burst wins.
     */
    private var snapshotDebounceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        initRegisterSharedPreferenceChangeListener(applicationContext)
        playbackErrorNotifier = PlaybackErrorNotifier(applicationContext)

        // Register the USB DAC driver so it starts listening for permission results.
        // This must happen before any USB device could be plugged in during the service lifetime.
        UsbDacDriver.getInstance(applicationContext).attach()

        // Listen for USB DAC attach/detach so the audio pipeline snapshot stays current
        // and we can log routing changes for diagnostics.
        UsbDacManager.addListener(usbDacManagerListener)

        // Listen for Sleep Timer events so a countdown or end-of-track timer armed from the
        // UI actually pauses (and optionally fades out) playback when it fires.
        SleepTimerManager.registerListener(sleepTimerListener)

        // Re-derive ExoPlayer's repeat mode whenever an online queue starts/ends (see applyRepeatMode).
        serviceScope.launch(Dispatchers.Main) {
            MediaPlaybackManager.onlineQueueFlow
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect {
                    if (::player.isInitialized) applyRepeatMode(PlayerPreferences.getRepeatMode())
                }
        }
        app.simple.felicity.engine.managers.BluetoothProfileManager.register(this)

        // Expose the processor via VisualizerManager so the player fragment can call
        // setDirectOutput() and wire the lock-free twin-buffer path without a service bind.
        // Keep both independent graphs configured identically. Only the active graph is currently
        // connected to the production renderer; standby is reserved for the paired renderer path.
        dualAudioProcessorSet.applyCurrentPreferences()
        // Build the standby renderer graph against its own DSP manager. It deliberately uses
        // DefaultAudioSink via DualPlayerStandbyRenderersFactory so a second player cannot open
        // the service's singleton USB/AAudio/Oboe route while the primary player is audible.
        standbyRenderersFactory = app.simple.felicity.engine.playback.DualPlayerStandbyRenderersFactory(
            this,
            standbyAudioProcessorManager
        )
        VisualizerManager.processor = audioProcessorManager.visualizerProcessor

        // Wire the native DSP processor into EqualizerManager so gain, preamp, and enable
        // changes driven by the UI are forwarded to the live audio pipeline immediately.
        EqualizerManager.attachProcessor(audioProcessorManager.nativeDspProcessor)

        // Initialize the RenderersFactory once.
        renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableOffload: Boolean): AudioSink {
                val hiresEnabled = AudioPreferences.isHiresOutputEnabled()

                // Check if the user WANTS to preserve surround sound for their USB DAC
                // You'd add this boolean to your AudioPreferences
                val forceStereoDownmix = AudioPreferences.isStereoDownmixForced()

                // Apply the complete persisted DSP configuration to this pipeline. Keeping
                // configuration application on the manager instance allows a future second
                // player to receive identical settings without sharing processor/native state.
                audioProcessorManager.applyCurrentPreferences()

                // Build the processor array dynamically
                val processors = mutableListOf<AudioProcessor>()

                if (AudioPreferences.isSkipSilenceEnabled()) {
                    // Trim digital silence first while the stream is uncolored.
                    processors.add(audioProcessorManager.silenceTrimmingProcessor)
                }

                if (forceStereoDownmix) {
                    processors.add(audioProcessorManager.downmixProcessor)
                }

                // Vocal removal runs before the EQ/effects chain so center-channel
                // subtraction is not colored by subsequent tonal processing.
                processors.add(audioProcessorManager.karaokeProcessor)

                // Unified native DSP: EQ → bass/treble shelves → M/S widening → balance → saturation.
                // Also feeds the processed mono downmix to the shared FFTContext.
                processors.add(audioProcessorManager.nativeDspProcessor)

                // Dynamic range compression runs after all tonal/spatial effects so it
                // can respond to the final loudness of the mix.
                processors.add(audioProcessorManager.nightModeProcessor)

                // Visualizer always goes last so the spectrum display reflects every
                // active effect in the chain.
                processors.add(audioProcessorManager.visualizerProcessor)

                val audioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(hiresEnabled)
                    // CRITICAL FOR USB DACs: Tell ExoPlayer to read the USB/HDMI capabilities
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .setAudioProcessors(processors.toTypedArray())
                    .build()

                // If the user has a home theater / USB DAC, we MIGHT want offload for Atmos/Dolby
                val offloadMode = if (!forceStereoDownmix) {
                    if (AudioPreferences.isGaplessPlaybackEnabled()) {
                        DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
                    } else {
                        DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED
                    }
                } else {
                    DefaultAudioSink.OFFLOAD_MODE_DISABLED
                }

                audioSink.setOffloadMode(offloadMode)

                /**
                 * Wrap the [DefaultAudioSink] with [FelicityAudioSink] as an exclusive router.
                 * The wrapper selects exactly one output path — USB DAC, AAudio, Oboe, or the
                 * standard AudioTrack — and routes all audio there. The [DefaultAudioSink] is
                 * only used when the AudioTrack path is active; for native paths it is kept
                 * idle (via the factory) so no AudioTrack is ever created unnecessarily.
                 */
                return FelicityAudioSink(
                        defaultSinkProvider = { audioSink },
                        context = context,
                        nativeDsp = audioProcessorManager.nativeDspProcessor,
                        visualizer = audioProcessorManager.visualizerProcessor)
            }

            override fun buildAudioRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    audioSink: AudioSink,
                    eventHandler: Handler,
                    eventListener: AudioRendererEventListener,
                    out: ArrayList<Renderer>
            ) {
                super.buildAudioRenderers(
                        context,
                        extensionRendererMode,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        audioSink,
                        eventHandler,
                        eventListener,
                        out
                )
            }
        }

        // Build the initial player instance
        buildPlayer()

        // Initialize MediaSession
        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivityIntent!!)
            .setId("ExoPlayerServiceSession")
            .build()

        // Set initial repeat button in the notification
        mediaSession?.setCustomLayout(listOf(buildRepeatCommandButton(PlayerPreferences.getRepeatMode())))

        // Keep the notification's favorite button in sync with the currently playing song's
        // favorite state no matter where the toggle happened (notification button, in-app song
        // menu, mini/full player heart icon, etc.). Every one of those call sites eventually
        // calls MediaPlaybackManager.notifyCurrentSongUpdated() (or otherwise emits
        // songPositionFlow), so collecting it here guarantees both surfaces stay consistent.
        serviceScope.launch(Dispatchers.Main.immediate) {
            MediaPlaybackManager.songPositionFlow.collect {
                val isFavorite = MediaPlaybackManager.getCurrentSong()?.isFavorite ?: false
                val repeatMode = PlayerPreferences.getRepeatMode()
                mediaSession?.setCustomLayout(
                        listOf(buildRepeatCommandButton(repeatMode), buildFavoriteCommandButton(isFavorite))
                )
            }
        }

        // Detect the current output device and subscribe to future device changes so the
        // snapshot is refreshed whenever headphones or a BT device is connected / disconnected.
        currentOutputDevice = detectActiveOutputDevice()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))

        // Respond to on-demand snapshot requests emitted by the UI (e.g., AudioPipelineDialog
        // opening). The collect runs on the main dispatcher so player APIs are safe to call.
        serviceScope.launch(Dispatchers.Main.immediate) {
            AudioPipelineManager.refreshRequestFlow.collect {
                buildAndPushSnapshot()
            }
        }

        // Keep an up-to-date song list in memory. Room emits a fresh list every time the
        // library changes (scan, delete, etc.) so onGetChildren always returns current data
        // without a blocking database call.
        serviceScope.launch {
            audioRepository.getAllAudio().collect { list ->
                cachedSongList = list
            }
        }
    }

    /**
     * configures the RenderersFactory based on user preferences and builds a new ExoPlayer instance.
     * If a player already exists, it is released before creating the new one.
     */
    private fun buildPlayer() {
        // Configure extension mode based on preferences
        val extensionMode = if (AudioPreferences.getAudioDecoder() == AudioPreferences.FFMPEG) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }

        renderersFactory?.setExtensionRendererMode(extensionMode)

        // Configure LoadControl with optimized buffer settings based on hi-res mode
        val hiresEnabled = AudioPreferences.isHiresOutputEnabled()

        val loadControl = if (hiresEnabled) {
            // Hi-Res mode: 32-bit float processing requires larger buffers
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs = */ 5000,   // 5s minimum for smooth float processing
                        /* maxBufferMs = */ 15000,  // 15s maximum for hi-res content
                        /* bufferForPlaybackMs = */ 2000,   // 2s to start playback
                        /* bufferForPlaybackAfterRebufferMs = */ 3000  // 3s rebuffer threshold
                )
                .setPrioritizeTimeOverSizeThresholds(false) // Prioritize size for hi-res
                .build()
        } else {
            // Standard mode: 16-bit PCM processing uses smaller, efficient buffers
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs = */ 2500,   // 2.5s minimum for standard playback
                        /* maxBufferMs = */ 10000,  // 10s maximum for efficiency
                        /* bufferForPlaybackMs = */ 1000,   // 1s quick start
                        /* bufferForPlaybackAfterRebufferMs = */ 2000  // 2s rebuffer threshold
                )
                .setPrioritizeTimeOverSizeThresholds(true) // Prioritize time for responsiveness
                .build()
        }

        Log.i(TAG, "LoadControl configured for ${if (hiresEnabled) "Hi-Res" else "Standard"} mode")

        // Build new player instance
        player = ExoPlayer.Builder(this, renderersFactory!!)
            .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                        .build(),
                    true
            )
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(buildStreamingMediaSourceFactory())
            .build()

        // Set initial silence state based on preferences
        setSilenceState()

        // Configure gapless playback
        configureGaplessPlayback()

        // Apply saved repeat mode
        applyRepeatMode(PlayerPreferences.getRepeatMode())

        // Restore the saved playback speed and pitch so the user's settings are honored
        // from the very first song — no need to touch a knob to get it going.
        applyPlaybackParameters(
                EqualizerPreferences.getPlaybackSpeed(),
                EqualizerPreferences.getPitch()
        )

        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        buildStandbyPlayer()
    }

    private fun createStandbyLoadControl(): androidx.media3.exoplayer.LoadControl =
        if (AudioPreferences.isHiresOutputEnabled()) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 15000, 2000, 3000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 10000, 1000, 2000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

    /**
     * Creates the long-lived standby player with its own renderer/audio-sink/DSP graph.
     * It does not request audio focus, handle noisy-device events, or own the MediaSession;
     * those responsibilities remain with the active service player until the handoff migration
     * is completed. The standby is intentionally muted and idle after construction.
     */
    private fun buildStandbyPlayer() {
        releaseStandbyPlayer()
        val factory = standbyRenderersFactory ?: return
        val standby = ExoPlayer.Builder(this, factory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                    .build(),
                false
            )
            // A LoadControl instance must never be shared between players: ExoPlayer throws
            // "Players that share the same LoadControl must share the same playback thread"
            // (surfacing as ERROR_CODE_FAILED_RUNTIME_CHECK) as soon as the second player
            // starts. The standby gets its own, with the same buffer sizes as the active one.
            .setLoadControl(createStandbyLoadControl())
            .setHandleAudioBecomingNoisy(false)
            .setMediaSourceFactory(buildStreamingMediaSourceFactory())
            .build()
        standby.volume = 0f
        standby.repeatMode = Player.REPEAT_MODE_OFF
        standby.playWhenReady = false
        standbyPlayer = standby
        dualPlayerCoordinator = app.simple.felicity.engine.playback.DualPlayerCoordinator(player, standby)
        syncStandbyToNextQueueItem()
        Log.i(TAG, "Permanent standby player created with independent renderer and DSP graph")
    }

    /**
     * Mirrors the primary playlist into the permanent standby player and prepares the next
     * logical item silently. The standby queue starts at the primary player's next item, so its
     * current item is already decoded/buffered when a later transition is requested. We do not
     * preload for repeat-one or for queues with no next item. The standby player never requests
     * audio focus and remains paused/muted; this is preparation only, not a second playback path.
     */
    /**
     * Promotes the prepared standby player after a completed overlap fade.
     *
     * This is deliberately guarded to the Android shared-mixer route. The standby renderer is
     * currently a stock DefaultAudioSink while the primary renderer can own exclusive USB,
     * AAudio or Oboe output. Promoting across those routes would silently change devices or
     * leave the next standby unable to overlap safely. The caller must finish the fade and set
     * the incoming player's gain before invoking this method.
     *
     * All mutations run on the service/player application looper. The MediaSession is rebound
     * before the coordinator commits its logical role swap, so a failed precondition never
     * leaves the coordinator and service pointing at different active players.
     */
    private fun promoteStandbyPlayerAfterFade(): Boolean {
        val incoming = standbyPlayer ?: return false
        val coordinator = dualPlayerCoordinator ?: return false
        if (!::player.isInitialized || incoming === player) return false
        if (UsbDacManager.isActive || FelicityAudioSink.isAAudioStreamActive ||
            FelicityAudioSink.isOboeStreamActive) {
            Log.w(TAG, "Dual-player promotion skipped: exclusive/native output route is active")
            return false
        }
        if (incoming.playbackState != Player.STATE_READY || incoming.currentMediaItem == null) {
            Log.w(TAG, "Dual-player promotion skipped: standby player is not ready")
            return false
        }

        val token = coordinator.beginTransition()
        if (!coordinator.markReady(token) || !coordinator.markFading(token) ||
            !coordinator.markHandingOff(token)) {
            coordinator.abortTransition(token)
            return false
        }

        val outgoing = player
        val outgoingDsp = audioProcessorManager
        val incomingDsp = standbyAudioProcessorManager
        try {
            // Keep the old player silent before changing ownership. It remains alive and becomes
            // the next standby instance, preserving the permanent two-player allocation.
            // Detach the app listener from the outgoing deck BEFORE silencing it. Its pause is
            // delivered synchronously; with the listener still attached the app announced
            // PLAYBACK_PAUSED, and since the incoming deck was already playing no later event
            // corrected it - the song kept playing while the UI showed a paused player.
            outgoing.removeListener(playerListener)
            outgoing.removeAnalyticsListener(analyticsListener)

            incoming.volume = 1f
            incoming.playWhenReady = true
            outgoing.volume = 0f
            outgoing.playWhenReady = false

            incoming.addListener(playerListener)
            incoming.addAnalyticsListener(analyticsListener)

            player = incoming
            standbyPlayer = outgoing
            audioProcessorManager = incomingDsp
            standbyAudioProcessorManager = outgoingDsp

            VisualizerManager.processor = audioProcessorManager.visualizerProcessor
            EqualizerManager.attachProcessor(audioProcessorManager.nativeDspProcessor)
            mediaSession?.player = player

            // Match the outgoing standby to the new active player's current settings before
            // preparing the next queue target.
            standbyPlayer?.playbackParameters = player.playbackParameters
            standbyPlayer?.skipSilenceEnabled = player.skipSilenceEnabled
            standbyPlayer?.volume = 0f
            standbyPlayer?.playWhenReady = false

            val promoted = coordinator.commitPromotion(token)
            check(promoted === player) { "Coordinator promoted a different player instance" }
            // The incoming deck loaded its queue item before playerListener was attached, so
            // ExoPlayer cannot replay the original transition callback for this handoff.
            // Deliver the logical AUTO transition once after ownership is committed so stats,
            // sleep-timer handling, queue position, widgets and pipeline snapshots stay aligned.
            playerListener.onMediaItemTransition(
                player.currentMediaItem,
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            )
            // The incoming deck was already playing, so ExoPlayer will not emit state events
            // for it. Re-announce its state so the UI, widgets, notification and the periodic
            // state saver reflect that playback is running.
            playerListener.onPlaybackStateChanged(player.playbackState)
            playerListener.onIsPlayingChanged(player.isPlaying)
            syncStandbyToNextQueueItem()
            Log.i(TAG, "Standby player promoted; MediaSession and DSP ownership transferred")
            return true
        } catch (error: Throwable) {
            // Best-effort rollback keeps the service controllable if a manager/session update
            // unexpectedly fails. Player listener ownership is restored to the outgoing player.
            runCatching { mediaSession?.player = outgoing }
            runCatching { incoming.removeListener(playerListener) }
            runCatching { incoming.removeAnalyticsListener(analyticsListener) }
            runCatching { outgoing.addListener(playerListener) }
            runCatching { outgoing.addAnalyticsListener(analyticsListener) }
            player = outgoing
            standbyPlayer = incoming
            audioProcessorManager = outgoingDsp
            standbyAudioProcessorManager = incomingDsp
            runCatching { VisualizerManager.processor = audioProcessorManager.visualizerProcessor }
            runCatching { EqualizerManager.attachProcessor(audioProcessorManager.nativeDspProcessor) }
            runCatching { incoming.volume = 0f }
            runCatching { coordinator.abortTransition(token) }
            Log.e(TAG, "Dual-player promotion failed; active player restored", error)
            return false
        }
    }

    /**
     * Listener-driven re-sync. While a dual-player fade is running the standby *is* the incoming
     * track; a queue/transition event must not re-point it at the following item, or the song
     * being faded in is torn down mid-fade (and the promotion that follows is rejected as "not
     * ready"). The fade job re-syncs the standby itself once it has finished or aborted.
     */
    private fun syncStandbyUnlessFading() {
        if (dualPlayerFadeJob?.isActive == true) return
        syncStandbyToNextQueueItem()
    }

    private fun syncStandbyToNextQueueItem() {
        val standby = standbyPlayer ?: return
        if (!::player.isInitialized || player.mediaItemCount == 0) {
            standby.playWhenReady = false
            standby.stop()
            standby.clearMediaItems()
            return
        }
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return

        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until player.mediaItemCount) {
            standby.playWhenReady = false
            standby.stop()
            standby.clearMediaItems()
            return
        }

        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val sameQueue = standby.mediaItemCount == queue.size &&
            (0 until standby.mediaItemCount).all { index ->
                standby.getMediaItemAt(index) == queue[index]
            }
        val alreadyPreparedForNext = sameQueue && standby.currentMediaItemIndex == nextIndex &&
            standby.playbackState != Player.STATE_IDLE
        if (alreadyPreparedForNext) return

        standby.playWhenReady = false
        standby.volume = 0f
        standby.repeatMode = Player.REPEAT_MODE_OFF
        standby.playbackParameters = player.playbackParameters
        standby.skipSilenceEnabled = player.skipSilenceEnabled
        standby.setMediaItems(queue, nextIndex, 0L)
        standby.prepare()
        Log.d(TAG, "Standby preloading next queue item: ${queue[nextIndex].mediaId}")
    }

    private fun releaseStandbyPlayer() {
        dualPlayerCoordinator?.resetTransitions()
        dualPlayerCoordinator = null
        standbyPlayer?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
            release()
        }
        standbyPlayer = null
    }

    /**
     * Handles the dynamic switching of the audio decoder.
     * Captures current playback state (full queue + position), rebuilds the player with new
     * decoder settings, restores the entire queue and resumes from the same track/position.
     */
    private fun switchDecoder() {
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex
        val currentPos = player.currentPosition
        val playWhenReady = player.playWhenReady

        // Release the old player to free up codecs/resources
        // The temp crossfade player was built against the OLD player's state; finishing it
        // onto a freshly built player would seek the wrong item. Abort it first.
        cancelCrossfadeIfActive()
        player.removeListener(playerListener)
        player.release()

        // Build the new player with updated Factory settings
        buildPlayer()

        // Restore the full queue and position
        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems, currentIndex, currentPos)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        // Update the session to point to the new player instance
        mediaSession?.player = player
    }

    /**
     * Handles the dynamic switching between hi-res and standard audio modes.
     * Captures current playback state, rebuilds the player with new audio output settings,
     * restores the state seamlessly for real-time mode switching.
     */
    private fun switchAudioMode() {
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex
        val currentPos = player.currentPosition
        val playWhenReady = player.playWhenReady
        val hiresEnabled = AudioPreferences.isHiresOutputEnabled()

        Log.i(TAG, "Switching audio mode to: ${if (hiresEnabled) "Hi-Res (32-bit Float)" else "Standard (16-bit PCM)"}")

        // Release the old player to free up audio resources
        // The temp crossfade player was built against the OLD player's state; finishing it
        // onto a freshly built player would seek the wrong item. Abort it first.
        cancelCrossfadeIfActive()
        player.removeListener(playerListener)
        player.release()

        // Build the new player with updated audio sink and buffer settings
        buildPlayer()

        // Restore the full queue and position seamlessly
        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems, currentIndex, currentPos)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        // Update the session to point to the new player instance
        mediaSession?.player = player

        Log.i(TAG, "Audio mode switch completed successfully")
    }

    /**
     * Configures gapless playback based on user preferences.
     * When enabled, the player will seamlessly transition between tracks without silence.
     */
    private fun configureGaplessPlayback() {
        val gaplessEnabled = AudioPreferences.isGaplessPlaybackEnabled()
        player.pauseAtEndOfMediaItems = !gaplessEnabled
    }

    private fun applyRepeatMode(repeatMode: Int) {
        when (repeatMode) {
            MediaConstants.REPEAT_ONE -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            MediaConstants.REPEAT_QUEUE -> {
                // While an online queue is active ExoPlayer only ever holds the ONE current
                // item, so REPEAT_MODE_ALL would loop that single track forever and never reach
                // STATE_ENDED. Keep it OFF and let MediaPlaybackManager.handleQueueEnded() wrap
                // the online queue itself.
                player.repeatMode = if (MediaPlaybackManager.isOnlineQueueActive()) {
                    Player.REPEAT_MODE_OFF
                } else {
                    Player.REPEAT_MODE_ALL
                }
            }
            else -> { // REPEAT_OFF
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
        MediaPlaybackManager.notifyRepeatMode(repeatMode)
        // Push the updated repeat button to the media notification alongside the favorite button
        val isFavorite = MediaPlaybackManager.getCurrentSong()?.isFavorite ?: false
        mediaSession?.setCustomLayout(listOf(buildRepeatCommandButton(repeatMode), buildFavoriteCommandButton(isFavorite)))
        Log.d(TAG, "Repeat mode applied: $repeatMode")
    }

    /** Builds a CommandButton representing the current repeat state for the notification. */
    @Suppress("DEPRECATION")
    private fun buildRepeatCommandButton(repeatMode: Int): CommandButton {
        val (iconRes, displayName) = when (repeatMode) {
            MediaConstants.REPEAT_ONE -> Pair(R.drawable.ic_repeat_one, "Repeat One")
            MediaConstants.REPEAT_QUEUE -> Pair(R.drawable.ic_repeat, "Repeat Queue")
            else -> Pair(R.drawable.ic_repeat_off, "Repeat Off")
        }

        return CommandButton.Builder(
                CommandButton.ICON_REPEAT_OFF)
            .setDisplayName(displayName)
            .setIconResId(iconRes)
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_REPEAT, Bundle.EMPTY))
            .build()
    }

    /**
     * Builds a CommandButton for the notification that lets the user toggle the current song
     * as a favorite. The icon respects the user's preference for a heart vs. thumbs-up style.
     *
     * @param isFavorite Whether the current song is already marked as a favorite.
     */
    @Suppress("DEPRECATION")
    private fun buildFavoriteCommandButton(isFavorite: Boolean): CommandButton {
        val useLikeIcon = UserInterfacePreferences.isLikeIconInsteadOfThumb()
        val (iconRes, displayName) = if (isFavorite) {
            if (useLikeIcon) Pair(R.drawable.ic_thumb_up, "Remove from Favorites")
            else Pair(R.drawable.ic_favorite_filled, "Remove from Favorites")
        } else {
            if (useLikeIcon) Pair(R.drawable.ic_thumb_up_off, "Add to Favorites")
            else Pair(R.drawable.ic_favorite_border, "Add to Favorites")
        }

        return CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(displayName)
            .setIconResId(iconRes)
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build()
    }

    private fun setSilenceState() {
        // Skip silence is always disabled for natural audio playback
        player.skipSilenceEnabled = AudioPreferences.isSkipSilenceEnabled()
    }

    /**
     * Silently retries [failedItem] using the FFmpeg extension decoder.
     *
     * The full queue and playback position are preserved; only the renderer mode is changed.
     * The preference store is NOT modified so the user's chosen decoder is kept intact.
     * If [failedItem] is null the call is a no-op (track already gone).
     */
    private fun retryWithFfmpegFallback(failedItem: MediaItem?) {
        if (failedItem == null) {
            Log.w(TAG, "retryWithFfmpegFallback: no failed item, aborting.")
            ffmpegFallbackActive = false
            return
        }

        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex

        // Grab the play/pause state BEFORE we tear down the player so we can put
        // things back exactly the way the user left them after the engine is rebuilt.
        // Without this, a paused player would spring back to life after the fallback
        // swap — not what anyone wants at 2 AM with the volume cranked up.
        val wasPlayWhenReady = player.playWhenReady

        // Temporarily force the FFmpeg extension without touching user preferences.
        renderersFactory?.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // The temp crossfade player was built against the OLD player's state; finishing it
        // onto a freshly built player would seek the wrong item. Abort it first.
        cancelCrossfadeIfActive()
        player.removeListener(playerListener)
        player.release()

        buildPlayerWithExtensionMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        if (mediaItems.isNotEmpty()) {
            // Always retry from the very beginning of the track (position 0) since we
            // can't guarantee the partial buffer from the failed decoder is usable.
            // However, we do respect the user's pause state — paused stays paused.
            player.setMediaItems(mediaItems, currentIndex, 0L)
            player.playWhenReady = wasPlayWhenReady
            player.prepare()
        }

        mediaSession?.player = player
        Log.i(TAG, "FFmpeg fallback: re-trying '${failedItem.mediaId}' from the start with FFmpeg.")
    }

    /**
     * Restores the engine to [decoderMode] without writing to shared preferences.
     * Called after a failed FFmpeg fallback so the user sees no change in settings.
     */
    private fun restoreDecoderMode(decoderMode: Int) {
        val extensionMode = if (decoderMode == AudioPreferences.FFMPEG) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }

        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex
        val currentPos = player.currentPosition
        val playWhenReady = player.playWhenReady

        // The temp crossfade player was built against the OLD player's state; finishing it
        // onto a freshly built player would seek the wrong item. Abort it first.
        cancelCrossfadeIfActive()
        player.removeListener(playerListener)
        player.release()

        buildPlayerWithExtensionMode(extensionMode)

        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems, currentIndex, currentPos)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        mediaSession?.player = player
        Log.d(TAG, "Decoder restored to mode $decoderMode (extensionMode=$extensionMode) without preference change.")
    }

    /**
     * Skips to the next track if available; otherwise restarts the current item.
     * Shared helper used by the fallback logic.
     */
    /** One-line "Type: message <- Type: message" summary of an exception and its causes. */
    private fun describeCauseChain(t: Throwable): String {
        val parts = ArrayList<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            parts += "${cur.javaClass.simpleName}: ${cur.message}"
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    private fun skipOrRestartTrack() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            Log.i(TAG, "Skipped to next track after decoder failure.")
        } else {
            player.seekToDefaultPosition()
            Log.i(TAG, "Restarted current track (no next track available).")
        }
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Variant of [buildPlayer] that uses a specific [extensionMode] directly, bypassing the
     * shared-preference read. Used for transient fallback / restore operations.
     */
    private fun buildPlayerWithExtensionMode(extensionMode: Int) {
        renderersFactory?.setExtensionRendererMode(extensionMode)

        val hiresEnabled = AudioPreferences.isHiresOutputEnabled()
        val loadControl = if (hiresEnabled) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 15000, 2000, 3000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 10000, 1000, 2000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory!!)
            .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                        .build(),
                    true
            )
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(buildStreamingMediaSourceFactory())
            .build()

        setSilenceState()
        configureGaplessPlayback()
        applyRepeatMode(PlayerPreferences.getRepeatMode())
        applyPlaybackParameters(
                EqualizerPreferences.getPlaybackSpeed(),
                EqualizerPreferences.getPitch()
        )
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        buildStandbyPlayer()
    }

    /**
     * Delegates balance panning to [audioProcessorManager].
     *
     * @param pan Stereo pan value in the range [-1.0, 1.0].
     */
    private fun applyBalanceToProcessor(pan: Float) {
        audioProcessorManager.applyBalance(pan)
    }

    /**
     * Delegates stereo width to [audioProcessorManager].
     *
     * @param width Stereo width in the range [0.0, 2.0]. 1.0 = natural stereo (no change).
     */
    private fun applyStereoWidthToProcessor(width: Float) {
        audioProcessorManager.applyStereoWidth(width)
    }

    /**
     * Delegates tape saturation drive to [audioProcessorManager].
     *
     * @param drive Saturation drive in [0.0, 4.0]. 0.0 = off (clean bypass).
     */
    private fun applyTapeSaturationDriveToProcessor(drive: Float) {
        audioProcessorManager.applyTapeSaturationDrive(drive)
    }

    /**
     * Delegates karaoke mode toggle to [audioProcessorManager].
     *
     * @param enabled True to activate center-channel removal, false to bypass.
     */
    private fun applyKaraokeModeToProcessor(enabled: Boolean) {
        audioProcessorManager.applyKaraokeMode(enabled)
    }

    /**
     * Delegates night mode toggle to [audioProcessorManager].
     *
     * @param enabled True to activate the dynamic compressor, false to bypass.
     */
    private fun applyNightModeToProcessor(enabled: Boolean) {
        audioProcessorManager.applyNightMode(enabled)
    }

    /**
     * Parses an embedded ReplayGain gain string (e.g. "+5.32 dB" or "-2.1dB") into a float
     * in dB. Returns 0.0 if the string is null, empty, or cannot be parsed.
     *
     * The function strips the trailing " dB" / "dB" suffix (case-insensitive) and any
     * leading/trailing whitespace before parsing the number, so it handles all common
     * tag formats from EAC, foobar2000, MusicBrainz Picard, and mp3gain.
     */
    private fun parseReplayGainDb(audio: Audio, mode: String): Float {
        val raw = if (mode == EqualizerPreferences.REPLAY_GAIN_MODE_ALBUM) {
            audio.replayGainAlbumGain ?: audio.replayGainTrackGain
        } else {
            audio.replayGainTrackGain ?: audio.replayGainAlbumGain
        } ?: return 0f

        return try {
            raw.trim()
                .replace(Regex("(?i)\\s*dB$"), "")
                .trim()
                .toFloat()
        } catch (_: NumberFormatException) {
            Log.w(TAG, "Could not parse ReplayGain string: '$raw'")
            0f
        }
    }

    /** Applies a new pan value immediately to the processor and persists it. */
    fun setBalance(pan: Float) {
        EqualizerPreferences.setBalance(pan)
        audioProcessorManager.applyBalance(pan)
    }

    /**
     * Applies playback speed and pitch to the ExoPlayer instance.
     *
     * Speed is a direct multiplier in [0.5 .. 2.0] (1.0 = normal). Pitch is supplied
     * as a semitone offset in [-12 .. +12] and is converted here using the standard
     * equal-temperament formula (multiplier = 2^(n/12)) before being handed off to
     * ExoPlayer, keeping the math in one place and the stored preferences human-readable.
     *
     * @param speed          Playback speed multiplier in [0.5 .. 2.0]. 1.0 = normal.
     * @param pitchSemitones Pitch offset in semitones. 0 = concert pitch, ±12 = ±1 octave.
     */
    private fun applyPlaybackParameters(speed: Float, pitchSemitones: Float) {
        val pitchMultiplier = 2f.pow(pitchSemitones / 12f).coerceIn(0.5f, 2.0f)
        player.playbackParameters = PlaybackParameters(speed, pitchMultiplier)
    }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // Queue edits (insert/remove/replace) are reflected in the standby playlist.
            syncStandbyUnlessFading()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            // Repeat-one must not preload a different item; repeat-all may wrap to the start.
            syncStandbyUnlessFading()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // nextMediaItemIndex is shuffle-aware, so refresh the standby target whenever the
            // shuffle order changes.
            syncStandbyUnlessFading()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Keep preloaded media configured for the same speed/pitch it will use after
            // promotion. This does not start playback or alter the active player's settings.
            standbyPlayer?.playbackParameters = playbackParameters
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            Log.d(TAG, "Audio session ID changed: $audioSessionId")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val format = player.audioFormat
            if (format != null && format.pcmEncoding != C.ENCODING_INVALID) {
                val encodingName = when (format.pcmEncoding) {
                    C.ENCODING_PCM_16BIT -> "16-bit"
                    C.ENCODING_PCM_FLOAT -> "32-bit Float"
                    C.ENCODING_PCM_24BIT -> "24-bit"
                    C.ENCODING_PCM_32BIT -> "32-bit"
                    else -> "Other (${format.pcmEncoding})"
                }
                Log.i(TAG, "Audio Engine: ${format.sampleRate}Hz | Output: $encodingName")
                Log.i(TAG, "Song Info: Channels: ${format.channelCount}, Encoding: ${format.pcmEncoding}, Sample Rate: ${format.sampleRate}")
            }

            if (isPlaying) {
                startCrossfadeTicker()
                MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PLAYING)
                startPeriodicStateSaving()
                startSnapshotPulse() // internally calls buildAndPushSnapshot() immediately, no need to call it again here
                broadcastWidgetUpdate()
            } else if (player.playbackState == Player.STATE_READY) {
                MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PAUSED)
                stopPeriodicStateSaving()
                stopSnapshotPulse()
                savePlaybackStateToDatabase() // Save immediately when paused
                broadcastWidgetUpdate()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // The temporary crossfade player has no audio-focus / becoming-noisy handling of
            // its own, so a pause of the main player (user, phone call, headphones unplugged)
            // would otherwise leave the incoming track playing out loud until the fade ends.
            crossfadePlayer?.playWhenReady = playWhenReady

            // Any manual play/pause (or an external pause) supersedes the pending gap resume.
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                gapJob?.cancel()
                gapJob = null
            }

            // After an unrecoverable error the player sits in STATE_IDLE with the error latched,
            // and play() on its own never re-prepares it - the button then appears dead and the
            // track stays "paused". Re-prepare on an explicit play so the user can retry.
            if (playWhenReady &&
                    player.playbackState == Player.STATE_IDLE &&
                    player.playerError != null &&
                    player.currentMediaItem != null) {
                Log.w(TAG, "Play requested while the player is in an error state; re-preparing.")
                player.prepare()
            }

            if (!playWhenReady) {
                // Silence the active native route (AAudio/Oboe) right now, on this thread,
                // instead of waiting for the internal playback thread to get around to its
                // own queued pause(). That thread may still be working through a backlog of
                // already-decoded buffers, which is what used to make pause() take seconds
                // to actually go quiet. See FelicityAudioSink.requestImmediatePause for why
                // this is safe to call from here.
                FelicityAudioSink.requestImmediatePause()
            }

            if (playWhenReady) {
                // If there's a song that was waiting for playback to start, record its play now.
                // This covers the case where a new queue is created and play() is called after
                // the item transition callback already fired (with playWhenReady still false).
                val deferredMediaId = pendingPlayRecordMediaId
                if (deferredMediaId != null) {
                    pendingPlayRecordMediaId = null
                    val audioId = deferredMediaId.toLongOrNull()
                    if (audioId != null) {
                        serviceScope.launch(Dispatchers.IO) {
                            val audio = audioRepository.getAudioById(audioId) ?: return@launch
                            songStatRepository.recordPlay(audio.hash)
                            Log.d(TAG, "Deferred play recorded for: ${audio.title}")
                        }
                    }
                }
            }

            if (AudioPreferences.isGaplessPlaybackEnabled().not()) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    // The track ended and the player paused itself automatically.
                    // Now we introduce our artificial gap.
                    gapJob?.cancel()
                    gapJob = serviceScope.launch(Dispatchers.Main) {
                        delay(GAP_DURATION_MS.milliseconds) // time of silence
                        player.play() // Move on to the next track
                    }
                }
            } else {
                // If gapless is enabled, we don't need to do anything special here.
                // The player will handle seamless transitions automatically.
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_BUFFERING)
                Player.STATE_READY -> {
                    // Emit the dedicated ready event first so observers (e.g. waveform loading)
                    // can react the moment the decoder is ready, regardless of whether the player
                    // will immediately start playing or remain paused.
                    MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_READY)
                    if (player.playWhenReady) MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PLAYING)
                    else MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PAUSED)
                    // STATE_READY is the only moment when player.duration is guaranteed to be
                    // valid for the current track. We cache it here so that when the user skips
                    // the song, onPositionDiscontinuity can grab the outgoing track's duration
                    // from this field instead of asking player.duration (which by then already
                    // reflects the incoming track and is often C.TIME_UNSET while buffering).
                    if (player.duration > 0) {
                        currentItemDurationMs = player.duration
                    }
                    buildAndPushSnapshot()
                }
                Player.STATE_ENDED -> {
                    // Only treat as a true "ended" event in REPEAT_OFF mode.
                    // For REPEAT_ONE / REPEAT_QUEUE, ExoPlayer loops automatically and
                    // STATE_ENDED is never actually reached.
                    // An online queue is one item at a time as far as ExoPlayer is concerned, so
                    // STATE_ENDED fires for every online track regardless of repeat mode (repeat-all
                    // is mapped to OFF in applyRepeatMode while one is active); the manager decides
                    // whether to advance, wrap or stop.
                    if (PlayerPreferences.getRepeatMode() == MediaConstants.REPEAT_OFF ||
                            MediaPlaybackManager.isOnlineQueueActive()) {
                        MediaPlaybackManager.handleQueueEnded()
                    }
                    stopPeriodicStateSaving()
                    stopSnapshotPulse()
                    savePlaybackStateToDatabase()
                }
                Player.STATE_IDLE -> {
                    MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_STOPPED)
                    stopPeriodicStateSaving()
                    stopSnapshotPulse()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> {
                    Log.e(TAG, "Decoding error for current track: ${error.message} (code: ${error.errorCode})")

                    val failedItem = player.currentMediaItem

                    if (ffmpegFallbackActive) {
                        // FFmpeg also failed – give up, restore original decoder and skip.
                        Log.w(TAG, "FFmpeg fallback also failed for '${failedItem?.mediaId}', skipping track and restoring decoder.")
                        ffmpegFallbackActive = false
                        ffmpegFallbackItem = null
                        // Notify user that the track could not be played by any available decoder.
                        playbackErrorNotifier.notifyPlaybackError(
                                failedItem?.mediaId,
                                error
                        )
                        // Restore user's original decoder choice silently (no pref write – just engine mode).
                        restoreDecoderMode(preFallbackDecoder)
                        skipOrRestartTrack()
                    } else if (AudioPreferences.isFallbackToSoftwareDecoderEnabled()
                            && AudioPreferences.getAudioDecoder() != AudioPreferences.FFMPEG) {
                        // Primary decoder failed and fallback is enabled – try FFmpeg silently.
                        Log.i(TAG, "Primary decoder failed; silently retrying '${failedItem?.mediaId}' with FFmpeg.")
                        preFallbackDecoder = AudioPreferences.getAudioDecoder()
                        ffmpegFallbackActive = true
                        ffmpegFallbackItem = failedItem
                        retryWithFfmpegFallback(failedItem)
                    } else {
                        // Fallback disabled, or already on FFmpeg – just skip.
                        Log.w(TAG, "Skipping track (fallback disabled or already using FFmpeg).")
                        ffmpegFallbackActive = false
                        ffmpegFallbackItem = null
                        // Notify user why the track was skipped.
                        playbackErrorNotifier.notifyPlaybackError(
                                failedItem?.mediaId,
                                error
                        )
                        skipOrRestartTrack()
                    }
                }
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                    Log.e(TAG, "File not found: ${error.message} (code: ${error.errorCode})")
                    playbackErrorNotifier.notifyPlaybackError(
                            player.currentMediaItem?.mediaId,
                            error
                    )
                    skipOrRestartTrack()
                }
                else -> {
                    Log.e(TAG, "Playback error: ${error.message} (code: ${error.errorCode})")
                    Log.e(TAG, "Player error: ${error.errorCodeName}", error)
                    Log.e(TAG, "Player error cause chain: ${describeCauseChain(error)}")
                    playbackErrorNotifier.notifyPlaybackError(
                            player.currentMediaItem?.mediaId,
                            error
                    )
                    MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_ERROR)
                    stopPeriodicStateSaving()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reportedReason: Int) {
            // A transition changes the logical next item even when the playlist itself is stable.
            syncStandbyUnlessFading()

            // A crossfade handoff is a seek as far as ExoPlayer is concerned, but it is an
            // automatic track change as far as the app is concerned — see crossfadeHandoffPending.
            // Consumed unconditionally: finishCrossfade() arms it only for a seek that is
            // guaranteed to produce exactly one transition callback.
            val wasCrossfadeHandoff = crossfadeHandoffPending
            crossfadeHandoffPending = false
            val reason = if (wasCrossfadeHandoff && reportedReason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            } else {
                reportedReason
            }

            // Sleep timer: an automatic transition means the previous track finished playing
            // on its own — exactly the moment an end-of-track sleep timer is waiting for.
            // Pause immediately so the incoming track is never actually heard. Because this
            // runs before the "record a play" block below, player.playWhenReady is already
            // false by the time that block executes, so the new song correctly does NOT get
            // counted as played — it'll be recorded normally later if the user resumes by hand.
            //
            // REPEAT counts too: with repeat-one, "the song ended" is reported as a REPEAT
            // transition, never AUTO, so without it the timer would silently never fire.
            val songEndedNaturally = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            if (songEndedNaturally && SleepTimerManager.consumeIfArmedForEndOfTrack()) {
                Log.i(TAG, "Sleep timer: end of track reached, pausing playback.")
                player.pause()
            }

            // If a track transition happened naturally (not via fallback retry), clear any stale fallback state.
            if (ffmpegFallbackActive && mediaItem != ffmpegFallbackItem) {
                Log.d(TAG, "Track transitioned away from fallback item; restoring original decoder and clearing fallback state.")
                ffmpegFallbackActive = false
                ffmpegFallbackItem = null
                restoreDecoderMode(preFallbackDecoder)
            }

            // Only bother recording anything if the player was actually running before the
            // transition — there's no point counting stats for songs the user skipped while
            // the player was sitting paused in the background.
            val prevMediaId = previousItemMediaId
            if (wasPlayingBeforeTransition && prevMediaId != null) {
                // A skip is when the user actively moves FORWARD away from a song before hearing
                // at least 30% of it. Two things can trigger this in a forward direction:
                //   1. The user taps next / seeks to a later track (REASON_SEEK, going forward).
                //   2. The user swipes the current song out of the queue (REASON_PLAYLIST_CHANGED).
                // A natural song ending (REASON_AUTO) is never a skip — the song finished!
                // Going backward is also never a skip — that's a replay, handled separately below.
                val isForwardNavigation = MediaPlaybackManager.lastNavigationDirection
                val isManuallySwitched = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                if (isManuallySwitched
                        && isForwardNavigation
                        && previousItemDurationMs > 0
                        && previousItemEndPositionMs < previousItemDurationMs * SKIP_THRESHOLD) {
                    serviceScope.launch(Dispatchers.IO) {
                        val audioId = prevMediaId.toLongOrNull() ?: return@launch
                        val audio = audioRepository.getAudioById(audioId) ?: return@launch
                        songStatRepository.recordSkip(audio.hash)
                        Log.d(TAG, "Skip recorded for: ${audio.title} (pos=${previousItemEndPositionMs}ms / dur=${previousItemDurationMs}ms, reason=$reason)")
                    }
                }
            }

            // Record a play event for the newly active song, but only when the player is
            // actually going to play it — skip this if the queue is being browsed while paused.
            // Also check whether this was a backward navigation and count it as a replay if so.
            mediaItem?.let { item ->
                previousItemMediaId = item.mediaId
                // While a queue replacement is in flight, ExoPlayer fires intermediate transitions
                // (e.g. the first item of the new list lands before seekTo moves to the real target).
                // We must not record those as plays — instead park the ID so it can be flushed once
                // the replacement is complete and the intended song actually starts playing.
                val replacingQueue = MediaPlaybackManager.isQueueBeingReplaced
                if (player.playWhenReady && !replacingQueue) {
                    pendingPlayRecordMediaId = null
                    val audioId = item.mediaId.toLongOrNull() ?: return@let
                    val isBackwardNavigation = !MediaPlaybackManager.lastNavigationDirection
                    serviceScope.launch(Dispatchers.IO) {
                        val audio = audioRepository.getAudioById(audioId) ?: return@launch
                        songStatRepository.recordPlay(audio.hash)
                        Log.d(TAG, "Play recorded for: ${audio.title}")
                        // When the user goes back to a song on purpose, that counts as a replay —
                        // it's their way of saying "that one was worth hearing again!"
                        if (isBackwardNavigation && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                            songStatRepository.recordReplay(audio.hash)
                            Log.d(TAG, "Replay recorded for: ${audio.title}")
                        }
                    }
                } else {
                    // Either the player isn't set to play yet, or the queue is still being
                    // replaced — park the ID and wait for playback to actually begin.
                    pendingPlayRecordMediaId = item.mediaId
                    Log.d(TAG, "Deferring play stat for: ${item.mediaId} (playWhenReady=${player.playWhenReady}, replacingQueue=$replacingQueue)")
                }
            } ?: run {
                previousItemMediaId = null
                pendingPlayRecordMediaId = null
            }

            MediaPlaybackManager.notifyCurrentPosition(player.currentMediaItemIndex, player.currentMediaItem?.mediaId)

            // After notifyCurrentPosition runs, the queue-replacement guard may have just been
            // lifted (it clears itself once ExoPlayer confirms the intended seek position).
            // If the player is already set to play, and we still have a parked ID, this is our
            // window to flush it — onPlayWhenReadyChanged won't fire because playWhenReady
            // never went false during an already-playing queue swap.
            val deferredId = pendingPlayRecordMediaId
            if (deferredId != null && player.playWhenReady && !MediaPlaybackManager.isQueueBeingReplaced) {
                pendingPlayRecordMediaId = null
                val audioId = deferredId.toLongOrNull()
                if (audioId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        val audio = audioRepository.getAudioById(audioId) ?: return@launch
                        songStatRepository.recordPlay(audio.hash)
                        Log.d(TAG, "Deferred play flushed (post-replace) for: ${audio.title}")
                    }
                }
            }

            savePlaybackStateToDatabase() // Save when track changes
            buildAndPushSnapshot()
            broadcastWidgetUpdate()

            // Apply tag-based ReplayGain for the incoming track when auto-RG is on.
            // We look up the Audio object from the database on the IO thread, parse the
            // gain string (e.g. "+5.32 dB"), and forward the dB value to the processor.
            // When auto-RG is off, or the track has no tag, we reset to unity (0 dB) so
            // the previous track's gain never bleeds into the next one.
            mediaItem?.let { item ->
                val audioId = item.mediaId.toLongOrNull()
                if (audioId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        val audio = audioRepository.getAudioById(audioId)
                        val db = if (EqualizerPreferences.isAutoReplayGainEnabled() && audio != null) {
                            parseReplayGainDb(audio, EqualizerPreferences.getReplayGainMode())
                        } else {
                            0f
                        }
                        audioProcessorManager.applyTagReplayGain(db)
                        if (db != 0f) {
                            Log.d(TAG, "Auto-RG applied: ${db}dB for track: ${audio?.title}")
                        }
                    }
                }
            }

            // Refresh the notification custom layout so the favorite button reflects
            // the new song's favorite state straight away, without any extra user interaction.
            val isFavorite = MediaPlaybackManager.getCurrentSong()?.isFavorite ?: false
            val repeatMode = PlayerPreferences.getRepeatMode()
            mediaSession?.setCustomLayout(listOf(buildRepeatCommandButton(repeatMode), buildFavoriteCommandButton(isFavorite)))
        }

        override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)

            // Only a genuinely external seek/skip should abort an in-progress crossfade.
            // DISCONTINUITY_REASON_AUTO_TRANSITION is ExoPlayer's own normal gapless
            // auto-advance to the next queued item — if our fade loop's timer (driven by
            // delay() calls, not by the player's actual clock) runs even slightly behind the
            // real track boundary, the player can reach the natural end and auto-advance to
            // the next item at position 0 BEFORE finishCrossfade() gets to run. Treating that
            // as "the user skipped, cancel" is exactly what caused the bug this comment is
            // explaining: the crossfade got cancelled right as the natural transition landed
            // on the next track at 0:00, and cancelling forces volume back to 1 immediately —
            // so the next track appeared to "restart from 00:00" at full volume out of nowhere.
            // DISCONTINUITY_REASON_INTERNAL is similarly not something the user did.
            val isExternalDiscontinuity = reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                    reason == Player.DISCONTINUITY_REASON_SKIP ||
                    reason == Player.DISCONTINUITY_REASON_REMOVE

            if (isCrossfading && isExternalDiscontinuity) {
                cancelCrossfadeIfActive()
            }

            // We want to capture the outgoing song's position and duration before ExoPlayer
            // hands control to the next item. This fires BEFORE onMediaItemTransition, so
            // player.duration still reflects the old track — perfect timing for us.
            //
            // We also handle DISCONTINUITY_REASON_REMOVE here because when the currently
            // playing song is swiped away from the queue, the new current item can land at
            // the same index slot (e.g., song 0 removed → song 1 becomes the new index 0),
            // so checking mediaItemIndex alone would miss that removal entirely.
            val isItemRemoved = reason == Player.DISCONTINUITY_REASON_REMOVE
            val isItemSwitch = oldPosition.mediaItemIndex != newPosition.mediaItemIndex
            if (isItemRemoved || isItemSwitch) {
                previousItemEndPositionMs = oldPosition.positionMs
                // Use our cached duration rather than player.duration — by this point
                // ExoPlayer has already pointed its internal state at the next item, so
                // player.duration would give us the INCOMING song's length (or C.TIME_UNSET
                // if it is still buffering), not the outgoing song we actually want to measure.
                previousItemDurationMs = currentItemDurationMs
                // Snapshot the playing intent NOW — by the time onMediaItemTransition fires the
                // player is often already buffering the next track, which makes isPlaying return
                // false even though the user is absolutely in "play" mode. Using playWhenReady
                // instead captures what the user actually wants, not a fleeting buffer state.
                wasPlayingBeforeTransition = player.playWhenReady
                // Reset the cached duration so it does not bleed into the next track's skip
                // check in the unlikely event that STATE_READY is delayed for the new item.
                currentItemDurationMs = 0L
            }
        }
    }

    /**
     * Wakes roughly 3 times a second to check whether it's time to start crossfading into the
     * next track — but only while it can actually matter: crossfade enabled AND something
     * playing (or a fade already running). Otherwise the loop ends and the main thread is left
     * alone; it's restarted from [onIsPlayingChanged] and when the crossfade preference flips.
     */
    private fun startCrossfadeTicker() {
        if (crossfadeTickerJob?.isActive == true) return
        if (!CrossfadePreferences.isCrossfadeEnabled()) return
        crossfadeTickerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive && CrossfadePreferences.isCrossfadeEnabled() && (player.isPlaying || isCrossfading)) {
                delay(300)
                maybeStartCrossfade()
            }
            crossfadeTickerJob = null
        }
    }

    private fun maybeStartCrossfade() {
        if (!CrossfadePreferences.isCrossfadeEnabled()) return
        if (isCrossfading) return
        if (!player.isPlaying) return
        if (!player.hasNextMediaItem()) return
        // The temp player is a stock ExoPlayer on the default AudioTrack route: no ReplayGain,
        // no DSP chain, and it can't share a USB DAC / AAudio / Oboe stream. Crossfading there
        // would jump in loudness at the handoff or fight the exclusive output, so skip it.
        if (UsbDacManager.isActive || FelicityAudioSink.isAAudioStreamActive || FelicityAudioSink.isOboeStreamActive) return
        // Crossfading resolved, short-lived YouTube stream URLs adds a lot of fragility
        // (a second resolve, in the middle of a fade, for a URL that might already be
        // close to expiring) for comparatively little benefit — local-queue tracks only.
        if (MediaPlaybackManager.getCurrentOnlineTrack() != null) return

        // Repeat-one must repeat THIS track. hasNextMediaItem()/nextMediaItemIndex use
        // getRepeatModeForNavigation(), which maps REPEAT_MODE_ONE to OFF — so ExoPlayer
        // happily reports "there is a next item" mid-queue and the handoff would advance
        // to it instead of repeating the current song.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return

        // With a single-item queue and repeat-all, "next" resolves to the item itself.
        // Crossfading into yourself ends with finishCrossfade() seeking the main player
        // back to ~0:00 several seconds before the track actually ends.
        if (player.nextMediaItemIndex == player.currentMediaItemIndex) return

        // "Stop after this song" must play the track out to its natural end so the
        // AUTO-transition hook can pause. A crossfade would start the next song audibly
        // first, and its handoff is a seek (not an AUTO transition) anyway.
        if (SleepTimerManager.currentMode() is SleepTimerManager.Mode.EndOfTrack) return

        val duration = player.duration
        if (duration <= 0 || duration == C.TIME_UNSET) return

        var fadeMs = CrossfadePreferences.getCrossfadeDurationMs()
        if (CrossfadePreferences.isAdaptiveEnabled()) {
            // Tailor the overlap to this particular pair of songs once the standby knows its length.
            val standby = standbyPlayer
            val nextItem = standby?.currentMediaItem
            if (standby != null && nextItem != null &&
                standby.playbackState == Player.STATE_READY &&
                standby.currentMediaItemIndex == player.nextMediaItemIndex
            ) {
                val outAlbum = player.currentMediaItem?.mediaMetadata?.albumTitle?.toString()
                val inAlbum = nextItem.mediaMetadata.albumTitle?.toString()
                fadeMs = CrossfadeMath.adaptiveFadeMs(
                        baseMs = fadeMs,
                        outgoingDurationMs = duration,
                        incomingDurationMs = standby.duration,
                        sameAlbum = !outAlbum.isNullOrBlank() && outAlbum == inAlbum)
            }
        }
        val remaining = duration - player.currentPosition
        // Give the incoming decoder/network source a short head start. Starting the
        // volume ramp while it is still buffering creates an audible hole in the mix.
        if (remaining > fadeMs + CROSSFADE_PRELOAD_LEAD_MS) return

        beginCrossfade(fadeMs)
    }

    /**
     * Crossfades with the permanent standby player, then promotes it to active ownership.
     * The standby queue is maintained by [syncStandbyToNextQueueItem], so this path does not
     * allocate a third ExoPlayer or perform a mid-track seek on the outgoing player.
     */
    private fun beginCrossfade(fadeMs: Long) {
        val incoming = standbyPlayer ?: return
        val expectedNextIndex = player.nextMediaItemIndex
        if (expectedNextIndex == C.INDEX_UNSET ||
            incoming.currentMediaItemIndex != expectedNextIndex ||
            incoming.currentMediaItem == null
        ) {
            syncStandbyToNextQueueItem()
            return
        }
        if (incoming.playbackState != Player.STATE_READY) return
        if (dualPlayerFadeJob?.isActive == true || crossfadePlayer != null) return
        // After a failed attempt wait before trying again, so a slow standby cannot cause an
        // endless start/pause loop on its audio track.
        if (SystemClock.elapsedRealtime() < dualFadeCooldownUntilMs) return

        val outgoing = player
        val outgoingItem = outgoing.currentMediaItem
        val incomingItem = incoming.currentMediaItem
        if (outgoingItem == null || incomingItem == null || outgoingItem == incomingItem) return

        dualPlayerFadeJob = serviceScope.launch(Dispatchers.Main) {
            // The permanent standby is already prepared and muted. Re-check identities and
            // queue position after entering the coroutine to reject stale preload completions.
            if (standbyPlayer !== incoming || player !== outgoing ||
                outgoing.nextMediaItemIndex != expectedNextIndex ||
                incoming.currentMediaItemIndex != expectedNextIndex ||
                incoming.playbackState != Player.STATE_READY
            ) return@launch

            incoming.playbackParameters = outgoing.playbackParameters
            incoming.skipSilenceEnabled = outgoing.skipSilenceEnabled
            // The standby was prepared at 0 ms. A seek (even to 0) drops it back to BUFFERING,
            // so only seek if it somehow moved.
            if (incoming.currentPosition > 100L) incoming.seekTo(0L)
            incoming.volume = 0f
            incoming.playWhenReady = true

            // playWhenReady only requests playback; the standby is not actually playing until
            // its sink has started. Starting the ramp (and its "is the incoming deck playing?"
            // check) before that aborted every fade on step 1, and the 300 ms ticker then
            // retried forever - an AudioTrack start/pause storm and no crossfade at all.
            val readyDeadline = SystemClock.elapsedRealtime() + DUAL_FADE_READY_TIMEOUT_MS
            while (isActive && !(incoming.playbackState == Player.STATE_READY && incoming.isPlaying)) {
                if (standbyPlayer !== incoming || player !== outgoing ||
                    outgoing.currentMediaItemIndex == expectedNextIndex) {
                    incoming.playWhenReady = false
                    incoming.volume = 0f
                    return@launch
                }
                if (SystemClock.elapsedRealtime() > readyDeadline) {
                    Log.w(TAG, "Dual-player fade: standby did not start in time; skipping this crossfade")
                    abortDualFade(incoming, outgoing)
                    return@launch
                }
                delay(20L)
            }

            val actualRemainingMs = (outgoing.duration - outgoing.currentPosition).coerceAtLeast(1L)
            // Finish well before the outgoing track ends by itself. If the natural track change
            // wins the race, ExoPlayer starts the same item again from 0 on the outgoing deck
            // while the incoming deck is already playing it - an audible restart.
            val fadeDurationMs = fadeMs.coerceAtMost(actualRemainingMs - DUAL_FADE_END_MARGIN_MS)
            if (fadeDurationMs < DUAL_FADE_MIN_MS) {
                Log.w(TAG, "Dual-player fade: only ${actualRemainingMs}ms left; skipping this crossfade")
                abortDualFade(incoming, outgoing)
                return@launch
            }
            // Progress follows the outgoing track's own media clock instead of counting timer
            // ticks, so the curve stays exact through pauses, speed changes and delayed ticks.
            val fadeStartPositionMs = outgoing.currentPosition
            while (true) {
                if (!isActive || standbyPlayer !== incoming || player !== outgoing) return@launch
                // A manual pause pauses both decks. Resume only when the active deck resumes.
                if (!outgoing.isPlaying) {
                    incoming.pause()
                    while (isActive && standbyPlayer === incoming && player === outgoing && !outgoing.isPlaying) {
                        delay(50L)
                    }
                    if (!isActive || standbyPlayer !== incoming || player !== outgoing) return@launch
                    incoming.play()
                }
                if (outgoing.currentMediaItemIndex == expectedNextIndex) {
                    // Natural queue advance won the race. Do not promote a stale preload.
                    incoming.playWhenReady = false
                    incoming.volume = 0f
                    outgoing.volume = 1f
                    syncStandbyToNextQueueItem()
                    return@launch
                }
                if (incoming.playbackState != Player.STATE_READY || !incoming.isPlaying) {
                    Log.w(TAG, "Dual-player fade: standby stalled mid-fade; aborting this crossfade")
                    abortDualFade(incoming, outgoing)
                    return@launch
                }
                val progress = CrossfadeMath.fadeProgress(
                        outgoing.currentPosition - fadeStartPositionMs, fadeDurationMs)
                val gains = CrossfadeMath.equalPowerGains(progress)
                outgoing.volume = gains.outgoing
                incoming.volume = gains.incoming
                if (progress >= 1f) break
                delay(CrossfadeMath.wallClockStepMs(FADE_TICK_MEDIA_MS, outgoing.playbackParameters.speed))
            }

            if (!isActive || standbyPlayer !== incoming || player !== outgoing) return@launch
            // Promotion performs the listener, DSP, visualizer, equalizer and MediaSession
            // transfer as one guarded service transaction. If it fails, restore the old deck.
            incoming.volume = 1f
            incoming.playWhenReady = true
            if (!promoteStandbyPlayerAfterFade()) {
                outgoing.volume = 1f
                incoming.volume = 0f
                incoming.playWhenReady = false
                Log.w(TAG, "Dual-player fade completed but promotion was rejected; retained outgoing player")
                syncStandbyToNextQueueItem()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (dualPlayerFadeJob === job) dualPlayerFadeJob = null
            }
        }
    }

    /** Silences the standby, restores the active player and backs off before the next attempt. */
    private fun abortDualFade(incoming: ExoPlayer, outgoing: ExoPlayer) {
        incoming.playWhenReady = false
        incoming.volume = 0f
        outgoing.volume = 1f
        dualFadeCooldownUntilMs = SystemClock.elapsedRealtime() + DUAL_FADE_RETRY_COOLDOWN_MS
        // Listener-driven syncs were skipped while the fade ran; catch the standby up now.
        syncStandbyToNextQueueItem()
    }

    /** Hands playback back to [player] at the exact position [temp] had already reached, then releases [temp]. */
    private fun finishCrossfade(temp: ExoPlayer, nextIndex: Int) {
        val handoffPositionMs = temp.currentPosition
        crossfadePlayer = null // consumed before seekTo() below, so the discontinuity listener treats this as our own handoff, not an external skip

        // player's volume is already ~0 from the fade loop above — the seek below jumps to an
        // arbitrary mid-track position ExoPlayer's own gapless read-ahead never pre-buffered
        // (it only pre-buffers the START of the next queued item), so it can cause a brief
        // re-buffering stall. The bug this fixes: that stall used to happen AFTER volume was
        // already restored to 1, making it audible as "a gap right as the track changes".
        // Keeping player silent through the seek, and keeping temp alive an extra moment as a
        // safety-net bridge, means a stall here is inaudible even when it does happen.
        player.volume = 0f
        // Only a seek onto a DIFFERENT item produces a media-item transition. If the natural
        // auto-advance already landed on nextIndex (the race handled in the fade loop), this
        // seek is just a position correction — arming the flag then would hijack the next
        // genuine user seek.
        crossfadeHandoffPending = player.currentMediaItemIndex != nextIndex
        player.seekTo(nextIndex, handoffPositionMs)

        serviceScope.launch(Dispatchers.Main) {
            val deadlineMs = System.currentTimeMillis() + 1500L
            while (isActive && System.currentTimeMillis() < deadlineMs) {
                if (player.playbackState == Player.STATE_READY &&
                    player.isPlaying &&
                    player.currentMediaItemIndex == nextIndex
                ) {
                    break
                }
                delay(20)
            }
            player.volume = 1f
            temp.release()
        }
    }

    /** Aborts a crossfade in progress (external seek/skip, or service teardown) and restores [player] to full volume. */
    private fun cancelCrossfadeIfActive() {
        crossfadeFadeJob?.cancel()
        crossfadeFadeJob = null
        dualPlayerFadeJob?.cancel()
        dualPlayerFadeJob = null
        crossfadePlayer?.release()
        crossfadePlayer = null
        standbyPlayer?.run {
            volume = 0f
            playWhenReady = false
        }
        crossfadeHandoffPending = false
        if (::player.isInitialized) {
            player.volume = 1f
        }
    }

    /**
     * Captures decoder initialization and compressed-source format changes so the
     * [AudioPipelineSnapshot] always reflects the active decoder name and track format.
     *
     * Both callbacks fire on the main thread, so accessing [player] and calling
     * [buildAndPushSnapshot] is safe without any additional dispatching.
     */
    private val analyticsListener = object : AnalyticsListener {

        override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
        ) {
            currentDecoderName = decoderName
            Log.d(TAG, "Audio decoder initialized: $decoderName")
            buildAndPushSnapshot()
        }

        override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            currentAudioInputFormat = format
            Log.d(TAG, "Audio input format changed: ${format.sampleMimeType} @ ${format.sampleRate}Hz")
            buildAndPushSnapshot()
        }
    }

    /**
     * Listens for audio output device additions and removals (e.g., plugging in wired
     * headphones or connecting a Bluetooth device). On each change the active output device
     * is re-detected and a fresh snapshot is pushed.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {

        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            currentOutputDevice = detectActiveOutputDevice()
            Log.d(TAG, "Audio device added: ${addedDevices.firstOrNull()?.productName}")
            buildAndPushSnapshot()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            currentOutputDevice = detectActiveOutputDevice()
            Log.d(TAG, "Audio device removed: ${removedDevices.firstOrNull()?.productName}")
            buildAndPushSnapshot()
        }
    }

    /**
     * Tracks USB DAC connect/disconnect events so the pipeline snapshot is refreshed
     * any time the direct USB output path is activated or deactivated.
     *
     * When the DAC attaches, the snapshot will reflect the new direct USB output. When it
     * detaches, the snapshot reverts to showing whichever path (AAudio or AudioTrack) takes
     * over. The actual audio rerouting is handled inside [FelicityAudioSink], not here.
     */
    private val usbDacManagerListener = object : UsbDacManager.Listener {
        override fun onUsbDacAttached(sampleRate: Int, channelCount: Int) {
            currentOutputDevice = detectActiveOutputDevice()
            Log.i(TAG, "USB DAC stream active — rebuilding pipeline snapshot " +
                    "(DAC negotiated at $sampleRate Hz / $channelCount ch)")
            buildAndPushSnapshot()
        }

        override fun onUsbDacDetached() {
            currentOutputDevice = detectActiveOutputDevice()
            Log.i(TAG, "USB DAC stream stopped — reverting pipeline snapshot")
            buildAndPushSnapshot()
        }
    }

    /**
     * Reacts to [SleepTimerManager] events by actually stopping playback.
     *
     * The manager itself is a plain Kotlin singleton with no notion of a [Player] — all it
     * knows is "when" to stop. This listener is the bridge that turns that signal into a
     * real [player.pause] call, optionally preceded by a volume fade.
     */
    private val sleepTimerListener = object : SleepTimerManager.Listener {
        override fun onSleepTimerFinished(fadeOut: Boolean) {
            serviceScope.launch(Dispatchers.Main) {
                // Otherwise the crossfade loop keeps fighting over player.volume and the
                // temp player keeps playing the next track after the timer "stopped" playback.
                cancelCrossfadeIfActive()
                gapJob?.cancel()
                gapJob = null
                if (fadeOut && player.isPlaying) {
                    fadeOutAndPause()
                } else {
                    player.pause()
                }
                Log.i(TAG, "Sleep timer finished — playback paused (fadeOut=$fadeOut).")
            }
        }

        override fun onSleepTimerCancelled() {
            sleepTimerFadeJob?.cancel()
            sleepTimerFadeJob = null
        }
    }

    /**
     * Gradually ramps [player]'s volume down to silence over [SLEEP_TIMER_FADE_DURATION_MS],
     * then pauses and restores the volume back to full so the next time the user presses
     * play, playback resumes at normal volume rather than starting silently.
     *
     * Must be called on the main thread (ExoPlayer's [Player.setVolume] is not thread-safe).
     */
    private suspend fun fadeOutAndPause() {
        sleepTimerFadeJob?.cancel()
        sleepTimerFadeJob = serviceScope.launch(Dispatchers.Main) {
            val steps = 20
            val stepDurationMs = SLEEP_TIMER_FADE_DURATION_MS / steps
            try {
                for (step in steps downTo 0) {
                    if (!isActive) return@launch
                    player.volume = step / steps.toFloat()
                    delay(stepDurationMs)
                }
                player.pause()
            } finally {
                // Also runs when the job is cancelled mid-fade, which used to leave the
                // player stuck at a partial volume.
                player.volume = 1f
            }
        }
        sleepTimerFadeJob?.join()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            AudioPreferences.AUDIO_DECODER -> {
                Log.d(TAG, "Audio decoder preference changed, switching decoder...")
                switchDecoder()
            }
            AudioPreferences.FFT_RESOLUTION -> {
                val newFftSize = AudioPreferences.getFftResolution()
                Log.d(TAG, "FFT resolution changed to: $newFftSize")
                audioProcessorManager.visualizerProcessor.updateFftResolution(newFftSize)
            }
            AudioPreferences.HIRES_OUTPUT -> {
                val hiresEnabled = AudioPreferences.isHiresOutputEnabled()
                Log.d(TAG, "Hi-Res output preference changed to: $hiresEnabled")
                switchAudioMode()
            }
            AudioPreferences.GAPLESS_PLAYBACK -> {
                // Reconfigure gapless playback when preference changes
                configureGaplessPlayback()
                Log.d(TAG, "Gapless playback preference changed to: ${AudioPreferences.isGaplessPlaybackEnabled()}")
            }
            AudioPreferences.SKIP_SILENCE -> {
                setSilenceState()
                Log.d(TAG, "Skip silence preference changed to: ${AudioPreferences.isSkipSilenceEnabled()} (Note: Skip silence is currently disabled for all modes)")
            }
            AudioPreferences.IS_STEREO_DOWNMIX_FORCED -> {
                val enabled = AudioPreferences.isStereoDownmixForced()
                Log.d(TAG, "Stereo downmix preference changed to: $enabled — rebuilding audio pipeline...")
                // Rebuilding the player re-invokes buildAudioSink which re-reads the preference
                // and re-assembles the processor chain with or without the downmix processor.
                switchAudioMode()
            }
            AudioPreferences.OUTPUT_SINK -> {
                val sink = AudioPreferences.getOutputSink()
                Log.d(TAG, "Output sink preference changed to: $sink — rebuilding audio pipeline...")
                // Rebuilding the player creates a fresh FelicityAudioSink, which will open the
                // correct stream immediately. Without this, the change only takes effect the
                // next time a song starts — the existing sink never learns about the preference flip.
                switchAudioMode()
            }
            CrossfadePreferences.CROSSFADE_ENABLED -> {
                if (CrossfadePreferences.isCrossfadeEnabled()) startCrossfadeTicker() else cancelCrossfadeIfActive()
            }
            PlayerPreferences.REPEAT_MODE -> {
                val repeatMode = PlayerPreferences.getRepeatMode()
                Log.d(TAG, "Repeat mode preference changed to: $repeatMode")
                applyRepeatMode(repeatMode)
            }
            ShufflePreferences.SHUFFLE -> {
                val shuffleEnabled = ShufflePreferences.isShuffleEnabled()
                Log.d(TAG, "Shuffle preference changed to: $shuffleEnabled")
                MediaPlaybackManager.setShuffleEnabled(shuffleEnabled)
            }
            AppearancePreferences.THEME,
            AppearancePreferences.ACCENT_COLOR -> {
                // Theme or accent color changed — nudge the widget so it redraws
                // with the fresh colors without waiting for the next song event.
                broadcastWidgetUpdate()
            }
            EqualizerPreferences.BALANCE -> {
                val pan = EqualizerPreferences.getBalance()
                Log.d(TAG, "Balance preference changed to: $pan")
                applyBalanceToProcessor(pan)
            }
            EqualizerPreferences.STEREO_WIDTH -> {
                val width = EqualizerPreferences.getStereoWidth()
                Log.d(TAG, "Stereo width preference changed to: $width")
                applyStereoWidthToProcessor(width)
            }
            EqualizerPreferences.TAPE_SATURATION_DRIVE -> {
                val drive = EqualizerPreferences.getTapeSaturationDrive()
                Log.d(TAG, "Tape saturation drive preference changed to: $drive")
                applyTapeSaturationDriveToProcessor(drive)
            }
            EqualizerPreferences.KARAOKE_MODE_ENABLED -> {
                val enabled = EqualizerPreferences.isKaraokeModeEnabled()
                Log.d(TAG, "Karaoke mode preference changed to: $enabled")
                applyKaraokeModeToProcessor(enabled)
            }
            EqualizerPreferences.NIGHT_MODE_ENABLED -> {
                val enabled = EqualizerPreferences.isNightModeEnabled()
                Log.d(TAG, "Night mode preference changed to: $enabled")
                applyNightModeToProcessor(enabled)
            }
            EqualizerPreferences.EQ_ENABLED -> {
                val enabled = EqualizerPreferences.isEqEnabled()
                Log.d(TAG, "Equalizer enabled preference changed to: $enabled")
                EqualizerManager.setEnabled(enabled)
            }
            EqualizerPreferences.EQ_MODE -> {
                // When the user flips between graphic and parametric mode, reload the
                // appropriate EQ state so the transition is seamless with no dead silence.
                val mode = EqualizerPreferences.getEqMode()
                Log.d(TAG, "EQ mode changed to: $mode")
                if (EqualizerPreferences.isParametricEqMode()) {
                    EqualizerManager.applyPeqBandsFromPreference()
                } else {
                    audioProcessorManager.applyEqualizerState()
                }
            }
            EqualizerPreferences.PEQ_BANDS_RAW -> {
                Log.d(TAG, "Parametric EQ bands preference changed")
                EqualizerManager.applyPeqBandsFromPreference()
            }
            EqualizerPreferences.PREAMP_DB -> {
                Log.d(TAG, "EQ preamp preference changed")
                EqualizerManager.applyPreampFromPreference()
            }
            EqualizerPreferences.BASS_DB -> {
                val db = EqualizerPreferences.getBassDb()
                Log.d(TAG, "Bass gain preference changed to: ${db}dB")
                audioProcessorManager.applyBass(db)
            }
            EqualizerPreferences.TREBLE_DB -> {
                val db = EqualizerPreferences.getTrebleDb()
                Log.d(TAG, "Treble gain preference changed to: ${db}dB")
                audioProcessorManager.applyTreble(db)
            }
            EqualizerPreferences.REPLAY_GAIN_DB -> {
                val db = EqualizerPreferences.getReplayGainDb()
                Log.d(TAG, "Replay gain preference changed to: ${db}dB")
                audioProcessorManager.applyReplayGain(db)
            }
            EqualizerPreferences.REVERB_MIX,
            EqualizerPreferences.REVERB_DECAY,
            EqualizerPreferences.REVERB_DAMP,
            EqualizerPreferences.REVERB_SIZE -> {
                val mix = EqualizerPreferences.getReverbMix()
                val decay = EqualizerPreferences.getReverbDecay()
                val damp = EqualizerPreferences.getReverbDamp()
                val size = EqualizerPreferences.getReverbSize()
                Log.d(TAG, "Reverb preference changed — mix=$mix, decay=$decay, damp=$damp, size=$size")
                audioProcessorManager.applyReverb(mix, decay, damp, size)
            }
            EqualizerPreferences.PITCH,
            EqualizerPreferences.PLAYBACK_SPEED -> {
                val pitch = EqualizerPreferences.getPitch()
                val speed = EqualizerPreferences.getPlaybackSpeed()
                Log.d(TAG, "Playback parameters changed — speed=${speed}x, pitch=${pitch}x")
                applyPlaybackParameters(speed, pitch)
            }
            else -> {
                // Handle each individual EQ band preference change
                if (key != null && key.startsWith(EqualizerPreferences.EQ_BAND_KEY_PREFIX)) {
                    val bandIndex = key.removePrefix(EqualizerPreferences.EQ_BAND_KEY_PREFIX).toIntOrNull()
                    if (bandIndex != null) {
                        Log.d(TAG, "EQ band $bandIndex preference changed")
                        EqualizerManager.applyBandFromPreference(bandIndex)
                    }
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackStateToDatabase(immediate = true)
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePlaybackStateToDatabase(immediate = true)
        unregisterSharedPreferenceChangeListener()

        // Remove the service-level USB DAC listener first so it no longer receives
        // attach/detach events during the rest of teardown.
        UsbDacManager.removeListener(usbDacManagerListener)

        // Stop listening for Sleep Timer events and cancel any timer still running —
        // a new service instance (e.g. after the app is relaunched) should start clean
        // rather than inheriting a timer armed by a now-dead player instance.
        SleepTimerManager.unregisterListener(sleepTimerListener)
        SleepTimerManager.cancel()
        sleepTimerFadeJob?.cancel()

        // Same idea for the crossfade ticker/temp player — a leaked temp ExoPlayer would hold
        // onto a native audio track after this service instance is gone.
        crossfadeTickerJob?.cancel()
        gapJob?.cancel()
        cancelCrossfadeIfActive()

        // Stop the periodic snapshot pulse before releasing resources.
        stopSnapshotPulse()

        // Unregister the audio-device-change callback so no stale reference is held after teardown.
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)

        // Clear the snapshot so observers know the pipeline is no longer active.
        AudioPipelineManager.updateSnapshot(null)

        // Detach the equalizer processor reference before releasing the player so the
        // manager does not hold a stale reference after teardown.
        EqualizerManager.detachProcessor()

        // Clear the visualizer processor reference so no stale direct-output connection
        // remains after the service has been destroyed.
        VisualizerManager.processor = null

        // Release the player and media session first. ExoPlayer.release() blocks until its
        // internal playback thread finishes, which includes FelicityAudioSink.release().
        // That call removes the sink's USB DAC listener from UsbDacManager on the correct
        // playback thread. Only after this is safe to call UsbDacDriver.detach(), because
        // notifyDetached() will find no sink listener left and won't try to release
        // DefaultAudioSink from the wrong thread (which is what was crashing before).
        releaseStandbyPlayer()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }

        // Now it is safe to tear down the USB driver. The sink listener is already gone,
        // so notifyDetached() won't cascade into DefaultAudioSink.release().
        UsbDacDriver.getInstance(applicationContext).detach()

        super.onDestroy()
    }

    private var pendingStateSave: kotlinx.coroutines.Job? = null

    /**
     * Saves the queue / position, coalescing bursts. Track changes, pauses and queue edits often
     * fire together, and each save writes the whole queue hash list, so a short debounce turns a
     * burst of identical writes into one.
     */
    private fun savePlaybackStateToDatabase(immediate: Boolean = false) {
        pendingStateSave?.cancel()
        pendingStateSave = serviceScope.launch {
            if (!immediate) kotlinx.coroutines.delay(400)
            PlaybackStateManager.saveCurrentPlaybackState(applicationContext, TAG)
        }
    }

    /**
     * Sends a broadcast to the home screen widget so it can redraw itself with the
     * latest song info and play/pause icon — no polling required.
     *
     * The broadcast is targeted directly at each registered widget provider so it never
     * leaks to other apps. The song metadata is attached as Intent extras; the
     * widget saves them to [WidgetStatePrefs] on receipt so the next cold draw
     * (days later) still shows the correct song title.
     *
     * Called from the player listener whenever the song or isPlaying state changes.
     */
    private fun broadcastWidgetUpdate() {
        val mediaItem = player.currentMediaItem ?: return
        // Only trust the local-queue song if it IS the item playing — during online playback
        // getCurrentSong() still returns whatever local song played last.
        val currentSong = MediaPlaybackManager.getCurrentSong()
            ?.takeIf { it.id.toString() == mediaItem.mediaId }
        val title = currentSong?.getProperTitle() ?: mediaItem.mediaMetadata.title?.toString()
        val artist = currentSong?.getProperArtists() ?: mediaItem.mediaMetadata.artist?.toString()
        val isPlaying = player.isPlaying
        val songId = mediaItem.mediaId.toLongOrNull() ?: -1L

        // Sent as one explicit broadcast per known provider class rather than a single
        // implicit one, because this module (engine) cannot depend on the module that
        // actually declares the widget classes (music) — see WIDGET_PROVIDER_CLASS_NAMES.
        // Widening this list is the ONLY step needed here whenever a new widget style is
        // added on the music-module side; everything else about pushing an update is
        // handled generically by BaseWidgetProvider.
        WIDGET_PROVIDER_CLASS_NAMES.forEach { className ->
            val intent = Intent("app.simple.felicity.ACTION_WIDGET_UPDATE").apply {
                component = ComponentName(applicationContext.packageName, className)
                putExtra("extra_title", title)
                putExtra("extra_artist", artist)
                putExtra("extra_is_playing", isPlaying)
                putExtra("extra_song_id", songId)
            }
            sendBroadcast(intent)
        }
    }

    /**
     * Starts a coroutine that pushes a refreshed [AudioPipelineSnapshot] to
     * [AudioPipelineManager] every 3 seconds while playback is active.
     *
     * The coroutine runs on the main dispatcher so [player] state can be read safely.
     * If a pulse job is already active this is a no-op.
     */
    private fun startSnapshotPulse() {
        if (snapshotPulseJob?.isActive == true) return

        snapshotPulseJob = serviceScope.launch(Dispatchers.Main.immediate) {
            buildAndPushSnapshot() // fire once immediately so the UI never waits
            while (isActive) {
                delay(3_000L.milliseconds)
                buildAndPushSnapshot()
            }
        }

        Log.d(TAG, "Started snapshot pulse")
    }

    /**
     * Cancels the running snapshot pulse and any pending debounced push.
     */
    private fun stopSnapshotPulse() {
        snapshotPulseJob?.cancel()
        snapshotPulseJob = null
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = null
        Log.d(TAG, "Stopped snapshot pulse")
    }

    /**
     * Assembles a fully-populated [AudioPipelineSnapshot] from live system sources and
     * pushes it to [AudioPipelineManager].
     *
     * Every field here is read directly from the player, the system's AudioManager, or the
     * DSP processor's own state — never from user preferences — so the snapshot always
     * reflects what is actually happening in the audio pipeline rather than what the user
     * asked for.
     *
     * Must be called from the main thread because several [ExoPlayer] API calls
     * (e.g., [ExoPlayer.audioFormat]) are not thread-safe. All call sites guarantee
     * this by using [Dispatchers.Main] or being inside main-thread callbacks.
     *
     * This method is debounced: rapid successive calls (e.g. during a song transition when
     * the analytics listener, player listener, and snapshot pulse all fire within ~200ms)
     * are coalesced into a single trailing push. Only the last call in a burst wins.
     */
    private fun buildAndPushSnapshot() {
        if (!::player.isInitialized) return
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = serviceScope.launch(Dispatchers.Main) {
            delay(SNAPSHOT_DEBOUNCE_MS.milliseconds)
            buildAndPushSnapshotNow()
        }
    }

    /**
     * Performs the actual snapshot assembly and push without any debounce guard.
     * All callers must route through [buildAndPushSnapshot] for debounce protection;
     * this function exists only as the target of the debounced coroutine.
     */
    private fun buildAndPushSnapshotNow() {
        if (!::player.isInitialized) return

        val inputFormat = currentAudioInputFormat
        val dspInputFormat = audioProcessorManager.nativeDspProcessor.currentInputFormat

        val outputDevice = currentOutputDevice ?: detectActiveOutputDevice().also {
            currentOutputDevice = it
        }

        // Track metadata from the compressed source format
        val trackFormat = mimeTypeToFormatString(inputFormat?.sampleMimeType)
        val bitDepth = when {
            inputFormat?.pcmEncoding != null && inputFormat.pcmEncoding != Format.NO_VALUE -> {
                pcmEncodingToBitDepth(inputFormat.pcmEncoding)
            }
            else -> 16
        }
        val sampleRateHz = inputFormat?.sampleRate?.takeIf { it > 0 } ?: 0
        val bitrateKbps = (inputFormat?.bitrate?.takeIf { it != Format.NO_VALUE } ?: 0) / 1000
        val channels = inputFormat?.channelCount?.takeIf { it > 0 } ?: 0

        // Decoder info
        val decoderLabel = when {
            currentDecoderName.contains("ffmpeg", ignoreCase = true) -> "Felicity Native FFmpeg Decoder"
            currentDecoderName.contains("c2.", ignoreCase = true) -> currentDecoderName
            currentDecoderName != "Unknown" -> currentDecoderName
            AudioPreferences.getAudioDecoder() == AudioPreferences.FFMPEG -> "Felicity Native FFmpeg Decoder (pending)"
            else -> "Android Built-in (pending)"
        }

        // Resampler state: keep source and DSP rates for later characterization
        val inputSampleRate = sampleRateHz

        // The DSP processor runs at the same sample rate the decoder hands it, which in normal
        // operation equals the source track's rate. However, `dspInputFormat` can briefly hold
        // a stale value from the previous track because ExoPlayer fires `onAudioInputFormatChanged`
        // (which updates `sampleRateHz`) before it reconfigures the audio processor chain for
        // the new track. Trusting `dspInputFormat.sampleRate` unconditionally during that window
        // would produce phantom "SW + HW resampling" entries on every track transition.
        //
        // The safe rule: use `dspInputFormat.sampleRate` only when it agrees with the source
        // rate, meaning the processor chain has been freshly configured for this track. If they
        // disagree, the source rate IS the ground truth (ExoPlayer does not resample between
        // its decoder and our processor chain under standard conditions).
        val dspSampleRateHz = when {
            sampleRateHz > 0 && dspInputFormat.sampleRate == sampleRateHz -> dspInputFormat.sampleRate
            sampleRateHz > 0 -> sampleRateHz
            dspInputFormat.sampleRate > 0 -> dspInputFormat.sampleRate
            else -> 0
        }

        // DSP state
        val dspFormatStr = pcmEncodingToFormatString(dspInputFormat.encoding)
        val activeEqName = when {
            !EqualizerPreferences.isEqEnabled() -> null
            EqualizerPreferences.getAllBandGains().all { it == 0f }
                    && EqualizerPreferences.getBassDb() == 0f
                    && EqualizerPreferences.getTrebleDb() == 0f -> "Flat"
            else -> "Custom"
        }
        val stereoExpandPercent = (EqualizerPreferences.getStereoWidth() * 100).roundToInt()

        // Buffer and latency estimation from actual AudioTrack minimum buffer size
        val (buffersStr, latencyEstimateMs) = computeBufferInfo(dspInputFormat)

        // Forward the current pipeline latency to the native DSP engine so the FFT
        // visualizer pre-delays its input by exactly this amount. This call covers all
        // trigger paths — playback start, track transition, format change, and device
        // change — because every one of them routes through buildAndPushSnapshot().
        audioProcessorManager.applyOutputLatency(latencyEstimateMs)

        // The PCM encoding that reaches the AudioSink is whatever the DSP processor is
        // configured to receive — it is the last processor in the chain before the sink.
        // We deliberately do NOT use `player.audioFormat?.pcmEncoding` here because that
        // returns the encoding from the compressed container metadata (e.g., 24-bit FLAC),
        // not the decoded PCM encoding. ExoPlayer's decoders truncate 24-bit PCM to 16-bit
        // unless the hi-res float output path is active, so `dspInputFormat.encoding` is the
        // only source that reflects the actual bit depth flowing through the pipeline right now.
        val deviceBitDepthIn = pcmEncodingToBitDepth(
                dspInputFormat.encoding.takeIf { it != Format.NO_VALUE } ?: C.ENCODING_PCM_16BIT
        )

        // The hardware HAL mixer rate is what AudioFlinger actually runs at, which may differ
        // from the source or DSP rate. Reading it directly from AudioManager avoids the guess
        // work of picking from a device's supported-rates list.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val halNativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

        // Hardware output device info
        val deviceName = outputDevice?.productName?.toString() ?: "Unknown"
        val deviceBitDepthOut = getDeviceBitDepth(outputDevice, deviceBitDepthIn)

        // Use the HAL's declared native rate as the true hardware sample rate, falling back
        // to probing the device's supported rates list if the property isn't available.
        val deviceSampleRate = if (halNativeSampleRate > 0) {
            halNativeSampleRate
        } else {
            getDeviceSampleRate(outputDevice, sampleRateHz)
        }

        // Full resampler characterisation — requires deviceSampleRate to detect HAL-level resampling.
        // SW resampling: ExoPlayer/Android pipeline changes rate before AudioTrack.
        // HW resampling: the AudioTrack/HAL resamples because its native rate ≠ what we write.
        val swResampling = inputSampleRate > 0 && inputSampleRate != dspSampleRateHz
        val hwResampling = dspSampleRateHz > 0 && dspSampleRateHz != deviceSampleRate
        val resamplerType = when {
            swResampling && hwResampling -> "SW + HW"
            swResampling -> "Software"
            hwResampling -> "Hardware (HAL)"
            else -> "None"
        }
        val resamplerQuality = when {
            swResampling && hwResampling -> "Android SRC + HAL"
            swResampling -> "Android SRC"
            hwResampling -> "HAL Native"
            else -> "Passthrough"
        }
        // Nyquist anti-aliasing cutoff = min rate in the chain ÷ 2
        val resamplerCutoffHz = if (swResampling || hwResampling) {
            listOf(inputSampleRate, dspSampleRateHz, deviceSampleRate)
                .filter { it > 0 }
                .minOrNull()
                ?.div(2) ?: 0
        } else {
            0
        }

        // Determine the true boundaries of the resampling chain for the UI
        // If SW resampling happens, the chain starts at the input file's rate. Otherwise, it starts at the DSP rate.
        val effectiveInRate = if (swResampling) inputSampleRate else dspSampleRateHz

        // If HW resampling happens, the chain ends at the hardware's forced rate. Otherwise, it ends at the DSP rate.
        val effectiveOutRate = if (hwResampling) deviceSampleRate else dspSampleRateHz

        // Reflect the true active output sink, not just user preferences. The priority matches
        // FelicityAudioSink's own routing logic: USB DAC beats AAudio, which beats AudioTrack.
        // If AAudio was enabled but the stream failed to open, this correctly shows "AudioTrack"
        // instead of falsely advertising a low-latency path that isn't actually running.
        val audioOutputMode = when {
            UsbDacManager.isActive -> "USB DAC (Direct)"
            FelicityAudioSink.isAAudioStreamActive -> "AAudio"
            FelicityAudioSink.isOboeStreamActive -> "Oboe"
            else -> "AudioTrack"
        }

        val snapshot = AudioPipelineSnapshot(
                trackFormat = trackFormat,
                bitDepth = bitDepth,
                sampleRateHz = sampleRateHz,
                bitrateKbps = bitrateKbps,
                channels = channels,
                decoderName = decoderLabel,
                inputSampleRate = inputSampleRate,
                outputSampleRate = dspSampleRateHz,
                resamplerType = resamplerType,
                resamplerQuality = resamplerQuality,
                resamplerCutoffHz = resamplerCutoffHz,
                effectiveInputSampleRate = effectiveInRate,
                effectiveOutputSampleRate = effectiveOutRate,
                dspFormat = dspFormatStr,
                dspSampleRate = dspSampleRateHz,
                activeEqName = activeEqName,
                stereoExpandPercent = stereoExpandPercent,
                buffers = buffersStr,
                latencyMs = latencyEstimateMs,
                visualizerLatencyMs = latencyEstimateMs,
                audioOutputMode = audioOutputMode,
                deviceName = deviceName,
                deviceBitDepthIn = deviceBitDepthIn,
                deviceBitDepthOut = deviceBitDepthOut,
                deviceSampleRate = deviceSampleRate
        )

        AudioPipelineManager.updateSnapshot(snapshot)
        Log.v(TAG, "Pipeline snapshot updated: $trackFormat @ ${sampleRateHz}Hz via $decoderLabel → $deviceName")
    }

    /**
     * Converts a MIME type string (e.g., `"audio/flac"`) to a short human-readable format
     * label (e.g., `"FLAC"`). Falls back to the subtype in uppercase for unknown types.
     *
     * @param mimeType The MIME type from [Format.sampleMimeType], or `null`.
     * @return A short uppercase label describing the audio format.
     */
    private fun mimeTypeToFormatString(mimeType: String?): String = when {
        mimeType == null -> "Unknown"
        mimeType.contains("flac", ignoreCase = true) -> "FLAC"
        mimeType.contains("mp4a") || mimeType.contains("aac") -> "AAC"
        mimeType.contains("mpeg") || mimeType.contains("mp3") -> "MP3"
        mimeType.contains("vorbis") -> "OGG"
        mimeType.contains("opus") -> "OPUS"
        mimeType.contains("wav") || mimeType.contains("wave") -> "WAV"
        mimeType.contains("alac") -> "ALAC"
        mimeType.contains("aiff") -> "AIFF"
        mimeType.contains("wma") -> "WMA"
        mimeType.contains("raw") -> "PCM"
        mimeType.contains("dsd") || mimeType.contains("dsf") -> "DSD"
        mimeType.contains("ape") -> "APE"
        else -> mimeType.substringAfterLast('/', mimeType).uppercase()
    }

    /**
     * Maps a Media3 [C.ENCODING_PCM_*] constant to a bit-depth integer.
     *
     * @param encoding A PCM encoding constant from [C].
     * @return The bit depth (8, 16, 24, or 32), defaulting to 16 for unknown encodings.
     */
    private fun pcmEncodingToBitDepth(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> 16
    }

    /**
     * Maps a Media3 [C.ENCODING_PCM_*] constant to a human-readable DSP format string.
     *
     * @param encoding A PCM encoding constant from [C].
     * @return A display string such as `"PCM 16-bit"` or `"Float32"`.
     */
    private fun pcmEncodingToFormatString(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_8BIT -> "PCM 8-bit"
        C.ENCODING_PCM_16BIT -> "PCM 16-bit"
        C.ENCODING_PCM_24BIT -> "PCM 24-bit"
        C.ENCODING_PCM_32BIT -> "PCM 32-bit"
        C.ENCODING_PCM_FLOAT -> "Float32"
        else -> "Unknown"
    }

    /**
     * Estimates the AudioTrack double-buffer size and total audio chain latency for the
     * given [dspInputFormat].
     *
     * Uses [AudioTrack.getMinBufferSize] to derive the minimum frame count at the
     * DSP sample rate, then estimates end-to-end latency as twice the buffer duration
     * plus a fixed 15 ms hardware/driver overhead.
     *
     * @param dspInputFormat The [AudioProcessor.AudioFormat] currently active in [NativeDspAudioProcessor].
     * @return A pair of (human-readable buffer string, estimated latency in ms).
     */
    private fun computeBufferInfo(dspInputFormat: AudioProcessor.AudioFormat): Pair<String, Int> {
        val sr = dspInputFormat.sampleRate.takeIf { it > 0 } ?: 44100
        val ch = dspInputFormat.channelCount.takeIf { it > 0 } ?: 2

        val channelConfig = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val afEncoding = when (dspInputFormat.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBufBytes = AudioTrack.getMinBufferSize(sr, channelConfig, afEncoding).coerceAtLeast(1)
        val bytesPerFrame = when (dspInputFormat.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4 * ch
            C.ENCODING_PCM_24BIT -> 3 * ch
            else -> 2 * ch
        }

        val framesInBuffer = minBufBytes / bytesPerFrame.coerceAtLeast(1)
        val bufferMs = framesInBuffer * 1000 / sr
        // Double-buffer (2×) is ExoPlayer's DefaultAudioSink default; add 15 ms for hardware latency.
        val latencyEstimate = bufferMs * 2 + 15

        return Pair("2x (${bufferMs}ms, $framesInBuffer frames)", latencyEstimate)
    }

    /**
     * Selects the highest-priority active audio output device from the system device list.
     *
     * Priority order: USB headset / USB device → Bluetooth A2DP → Bluetooth SCO →
     * wired headset → wired headphones → built-in earpiece → built-in speaker → other.
     *
     * @return The best-matching [AudioDeviceInfo], or `null` if no output devices are found.
     */
    private fun detectActiveOutputDevice(): AudioDeviceInfo? {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.maxByOrNull { outputDevicePriority(it.type) }
    }

    /**
     * Returns a numeric priority for the given [AudioDeviceInfo] type so the most
     * desirable (highest fidelity) output device wins in [detectActiveOutputDevice].
     *
     * @param type An [AudioDeviceInfo.TYPE_*] constant.
     * @return Priority integer; higher means more preferred.
     */
    private fun outputDevicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> 100
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 80
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 75
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 60
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 55
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 20
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 10
        else -> 0
    }

    /**
     * Returns the maximum PCM bit depth supported by [device] by inspecting
     * [AudioDeviceInfo.getEncodings]. Falls back to [fallback] when the device
     * reports no encodings or when [device] is `null`.
     *
     * @param device   The output device to inspect, or `null`.
     * @param fallback Bit depth to return when no encoding info is available.
     * @return Maximum supported bit depth: 8, 16, 24, or 32.
     */
    private fun getDeviceBitDepth(device: AudioDeviceInfo?, fallback: Int): Int {
        device ?: return fallback
        val encodings = device.encodings
        if (encodings.isEmpty()) return fallback
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && encodings.contains(AudioFormat.ENCODING_PCM_32BIT) -> 32
            encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) -> 32
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> 24
            encodings.contains(AudioFormat.ENCODING_PCM_16BIT) -> 16
            else -> fallback
        }
    }

    /**
     * Returns the best matching sample rate supported by [device] for the given [sourceSampleRate].
     *
     * Prefers the highest rate that does not exceed [sourceSampleRate] so the hardware
     * does not up-sample unnecessarily. If all device rates are above the source rate the
     * minimum device rate is returned. Returns [sourceSampleRate] when [device] is `null`
     * or its sample-rate list is empty.
     *
     * @param device           The output device to inspect, or `null`.
     * @param sourceSampleRate The source track's sample rate in Hz.
     * @return The best-matching hardware sample rate in Hz.
     */
    private fun getDeviceSampleRate(device: AudioDeviceInfo?, sourceSampleRate: Int): Int {
        device ?: return sourceSampleRate
        val rates = device.sampleRates
        if (rates.isEmpty()) return sourceSampleRate
        return rates.filter { it <= sourceSampleRate }.maxOrNull()
            ?: rates.minOrNull()
            ?: sourceSampleRate
    }

    private fun startPeriodicStateSaving() {
        if (periodicStateSaveJob?.isActive == true) return

        periodicStateSaveJob = serviceScope.launch {
            while (isActive) {
                delay(10000.milliseconds) // Save every 10 seconds
                savePlaybackStateToDatabase()
            }
        }

        Log.d(TAG, "Started periodic state saving")
    }

    private fun stopPeriodicStateSaving() {
        periodicStateSaveJob?.cancel()
        periodicStateSaveJob = null
        Log.d(TAG, "Stopped periodic state saving")
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private val toggleRepeatCommand = SessionCommand(COMMAND_TOGGLE_REPEAT, Bundle.EMPTY)
        private val toggleFavoriteCommand = SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY)

        /**
         * Advertise the custom repeat and favorite commands so the system notification controller can use them.
         */
        override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(toggleRepeatCommand)
                .add(toggleFavoriteCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        /**
         * Handle the repeat toggle and favorite toggle commands sent from the notification buttons.
         */
        override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_TOGGLE_REPEAT) {
                val current = PlayerPreferences.getRepeatMode()
                val next = when (current) {
                    MediaConstants.REPEAT_OFF -> MediaConstants.REPEAT_QUEUE
                    MediaConstants.REPEAT_QUEUE -> MediaConstants.REPEAT_ONE
                    else -> MediaConstants.REPEAT_OFF
                }
                PlayerPreferences.setRepeatMode(next)
                applyRepeatMode(next)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == COMMAND_TOGGLE_FAVORITE) {
                val currentSong = MediaPlaybackManager.getCurrentSong()
                if (currentSong != null) {
                    val newFavoriteState = !currentSong.isFavorite
                    serviceScope.launch(Dispatchers.IO) {
                        audioRepository.setFavorite(currentSong.id, newFavoriteState)
                        // Update the in-memory Audio object so the button reflects the new state
                        // without waiting for the next database read to come through.
                        currentSong.isFavorite = newFavoriteState
                        val repeatMode = PlayerPreferences.getRepeatMode()
                        serviceScope.launch(Dispatchers.Main) {
                            // Notify the active player fragment so its favorite button updates too.
                            MediaPlaybackManager.notifyCurrentSongUpdated()
                            mediaSession?.setCustomLayout(
                                    listOf(
                                            buildRepeatCommandButton(repeatMode),
                                            buildFavoriteCommandButton(newFavoriteState)
                                    )
                            )
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            Log.d(TAG, "onGetLibraryRoot called by: ${browser.packageName}")

            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setTitle("Felicity Music Library")
                            .build()
                )
                .build()

            LibraryResult.ofItem(rootItem, params)
        }

        /**
         * Allow clients to browse content (essential for "Play Music" generally)
         */
        override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            Log.d(TAG, "onGetChildren called for parentId: $parentId, page: $page, pageSize: $pageSize")

            when (parentId) {
                "root" -> {
                    // Use the cached list that Room keeps fresh via a Flow — no blocking DB call
                    // needed here, and no risk of an outdated list after a library rescan.
                    val songs = cachedSongList.ifEmpty {
                        // Cache hasn't populated yet on the very first call — fetch once as fallback.
                        audioRepository.getAllAudioList()
                    }

                    // Convert Audio models to MediaItems
                    val mediaItems = songs.map { audio ->
                        MediaItem.Builder()
                            .setMediaId(audio.id.toString())
                            .setUri(audio.uri)
                            .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(audio.getProperTitle())
                                        .setArtist(audio.getProperArtists())
                                        .setAlbumTitle(audio.getProperAlbum())
                                        .setIsBrowsable(false) // Songs are leaves, not folders
                                        .setIsPlayable(true)
                                        .build()
                            )
                            .build()
                    }

                    // Handle pagination
                    val startIndex = page * pageSize
                    val endIndex = minOf(startIndex + pageSize, mediaItems.size)
                    val paginatedItems = if (startIndex < mediaItems.size) {
                        mediaItems.subList(startIndex, endIndex)
                    } else {
                        emptyList()
                    }

                    Log.d(TAG, "Returning ${paginatedItems.size} items out of ${mediaItems.size} total")
                    LibraryResult.ofItemList(ImmutableList.copyOf(paginatedItems), params)
                }
                else -> {
                    // Unknown parent ID
                    Log.w(TAG, "Unknown parent ID: $parentId")
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
            }
        }

        /**
         * Handle "Play [Song Name]" commands from Assistant (Search Intent)
         */
        override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future {
            Log.d(TAG, "onAddMediaItems called with ${mediaItems.size} items")

            val updatedMediaItems = mediaItems.mapNotNull { mediaItem ->
                // If the mediaItem comes from a search query, it often lacks a URI
                if (mediaItem.requestMetadata.searchQuery != null) {
                    val query = mediaItem.requestMetadata.searchQuery!!
                    Log.d(TAG, "Assistant requested search for: $query")

                    // Search for the song in the AudioRepository
                    // Try title search first, then artist search
                    val titleResults = audioRepository.searchByTitle(query)
                    val artistResults = audioRepository.searchByArtist(query)
                    val audio = titleResults.firstOrNull() ?: artistResults.firstOrNull()

                    if (audio != null) {
                        Log.d(TAG, "Found audio: ${audio.title} by ${audio.artist}")
                        // Return the fully populated MediaItem with URI
                        MediaItem.Builder()
                            .setMediaId(audio.id.toString())
                            .setUri(audio.uri)
                            .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(audio.getProperTitle())
                                        .setArtist(audio.getProperArtists())
                                        .setAlbumTitle(audio.getProperAlbum())
                                        .setIsPlayable(true)
                                        .build()
                            )
                            .build()
                    } else {
                        Log.w(TAG, "No audio found for query: $query")
                        null
                    }
                } else if (mediaItem.localConfiguration != null) {
                    // Already has a URI, return as-is
                    mediaItem
                } else {
                    // Try to resolve by media ID
                    val mediaId = mediaItem.mediaId
                    if (mediaId.isNotEmpty()) {
                        val audioId = mediaId.toLongOrNull()
                        if (audioId != null) {
                            // Get audio by ID from database
                            val query = "SELECT * FROM audio WHERE id = ?"
                            val args = arrayOf<Any>(audioId)
                            val results = audioRepository.executeRawQuery(query, args)
                            val audio = results.firstOrNull()

                            if (audio != null) {
                                Log.d(TAG, "Resolved media ID $mediaId to audio: ${audio.title}")
                                MediaItem.Builder()
                                    .setMediaId(audio.id.toString())
                                    .setUri(audio.uri)
                                    .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(audio.getProperTitle())
                                                .setArtist(audio.getProperArtists())
                                                .setAlbumTitle(audio.getProperAlbum())
                                                .setIsPlayable(true)
                                                .build()
                                    )
                                    .build()
                            } else {
                                Log.w(TAG, "No audio found for media ID: $mediaId")
                                null
                            }
                        } else {
                            Log.w(TAG, "Invalid media ID format: $mediaId")
                            null
                        }
                    } else {
                        Log.w(TAG, "MediaItem has no URI, search query, or valid media ID")
                        null
                    }
                }
            }.toMutableList()

            Log.d(TAG, "Resolved ${updatedMediaItems.size} media items")
            updatedMediaItems
        }

        /**
         * Restores the last-known playback queue and position when the system starts this
         * service without an active player state — e.g. a media button event (Bluetooth
         * play/pause, headset button, or a resumption request from the system/Android Auto)
         * arriving while the app process is dead.
         *
         * Without this override, [Context.startForegroundService] is invoked by
         * [androidx.media3.session.MediaButtonReceiver] but the player never receives any
         * media items to play. Since nothing ever starts playing, [MediaSessionService]
         * never promotes the service to the foreground via [Service.startForeground], and
         * the framework kills the app with a
         * `ForegroundServiceDidNotStartInTimeException` a few seconds later.
         *
         * By returning the previously saved queue here, media3 automatically sets it on the
         * player and calls play(), which lets the service post its notification and call
         * startForeground() in time.
         */
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            Log.d(TAG, "onPlaybackResumption requested by: ${controller.packageName}")

            val db = AudioDatabase.getInstance(applicationContext)
            val state = PlaybackStateManager.fetchPlaybackState(db)
            val restoredSongs = PlaybackStateManager.getAudiosFromQueueIDs(db)

            if (restoredSongs.isNullOrEmpty()) {
                Log.w(TAG, "onPlaybackResumption: no saved queue found; nothing to resume.")
                return@future MediaSession.MediaItemsWithStartPosition(
                        emptyList(), C.INDEX_UNSET, C.TIME_UNSET
                )
            }

            val savedIndex = (state?.index ?: 0).coerceIn(0, restoredSongs.size - 1)
            val savedPositionMs = (state?.position ?: 0L).coerceAtLeast(0L)

            // Keep MediaPlaybackManager's in-memory state in sync so the widget, notification
            // favorite/repeat buttons, and stat recording all reflect the resumed queue —
            // mediaController is null at this point so this call only updates local state.
            withContext(Dispatchers.Main) {
                MediaPlaybackManager.setSongs(
                        restoredSongs, savedIndex, savedPositionMs, autoPlay = false, isRestore = true
                )
                state?.activeQueueId?.let { MediaPlaybackManager.setActiveQueueId(it) }
            }

            val mediaItems = restoredSongs.map { audio: Audio ->
                MediaItem.Builder()
                    .setMediaId(audio.id.toString())
                    .setUri(audio.uri)
                    .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(audio.getProperTitle())
                                .setArtist(audio.getProperArtists())
                                .setAlbumTitle(audio.getProperAlbum())
                                .setIsPlayable(true)
                                .build()
                    )
                    .build()
            }

            Log.d(
                    TAG, "onPlaybackResumption: restoring ${mediaItems.size} items, " +
                    "startIndex=$savedIndex, startPositionMs=$savedPositionMs"
            )

            MediaSession.MediaItemsWithStartPosition(mediaItems, savedIndex, savedPositionMs)
        }
    }

    companion object {
        private const val CROSSFADE_PRELOAD_LEAD_MS = 5_000L
        private const val DUAL_FADE_READY_TIMEOUT_MS = 2_000L
        private const val DUAL_FADE_END_MARGIN_MS = 1_500L
        private const val DUAL_FADE_MIN_MS = 1_000L
        private const val FADE_TICK_MEDIA_MS = 25L
        private const val DUAL_FADE_RETRY_COOLDOWN_MS = 3_000L
        private const val CROSSFADE_PREPARE_TIMEOUT_MS = 8_000L

        private const val TAG = "FelicityPlayerService"
        private const val GAP_DURATION_MS = 800L // Duration of silence gap when gapless playback is disabled

        /**
         * Fraction of a song's duration that must have elapsed before a transition is NOT
         * counted as a skip. Songs navigated away from before this threshold increment
         * the skip counter in the song statistics database.
         */
        private const val SKIP_THRESHOLD = 0.30

        /**
         * Cooldown window in milliseconds that coalesces rapid-fire [buildAndPushSnapshot]
         * calls during a song transition. Up to 6 callbacks (decoder init, format change,
         * item transition, state ready, playing changed, snapshot pulse) can fire within
         * ~200ms of each other. This debounce ensures only the last call in a burst
         * produces a snapshot, reducing duplicate pipeline updates from 6 to 1.
         */
        private const val SNAPSHOT_DEBOUNCE_MS = 200L

        /** Total duration of the volume ramp-down performed by [fadeOutAndPause] when a countdown sleep timer finishes. */
        private const val SLEEP_TIMER_FADE_DURATION_MS = 8000L

        /**
         * Every [app.simple.felicity.widget.BaseWidgetProvider] subclass that exists on the
         * music-module side, addressed by fully-qualified class name since this (engine)
         * module cannot depend on music. Add a new widget's class name here — that's the
         * only change needed in this file to keep it updating live.
         */
        private val WIDGET_PROVIDER_CLASS_NAMES = listOf(
                "app.simple.felicity.widget.LyricsWidgetProvider",
                "app.simple.felicity.widget.MaterialYouPlayerWidgetProvider",
                "app.simple.felicity.widget.MaterialYouPillWidgetProvider",
                "app.simple.felicity.widget.UpNextWidgetProvider",
                "app.simple.felicity.widget.GlassPlayerWidgetProvider",
                "app.simple.felicity.widget.GlassLyricsWidgetProvider",
                "app.simple.felicity.widget.GlassCardWidgetProvider",
                "app.simple.felicity.widget.GlassBarWidgetProvider",
                "app.simple.felicity.widget.GlassUpNextWidgetProvider",
                "app.simple.felicity.widget.VinylWidgetProvider"
        )

        /** Custom session command sent when the user taps the repeat button in the notification. */
        const val COMMAND_TOGGLE_REPEAT = "app.simple.felicity.TOGGLE_REPEAT"

        /** Custom session command sent when the user taps the favorite button in the notification. */
        const val COMMAND_TOGGLE_FAVORITE = "app.simple.felicity.TOGGLE_FAVORITE"
    }
}