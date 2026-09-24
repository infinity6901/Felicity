package app.simple.felicity.dialogs.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.R
import app.simple.felicity.databinding.DialogSleepTimerBinding
import app.simple.felicity.decorations.seekbars.FelicitySeekbar
import app.simple.felicity.engine.managers.SleepTimerManager
import app.simple.felicity.extensions.dialogs.ScopedBottomSheetFragment
import app.simple.felicity.preferences.SleepTimerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Bottom-sheet dialog that lets the user arm or cancel the app-wide Sleep Timer.
 *
 * <p>Talks directly to [SleepTimerManager] — the same in-process singleton the player
 * service listens to — rather than going through the service itself, matching how other
 * player-adjacent dialogs in this app (e.g. equalizer, visualizer config) read and write
 * shared state directly rather than binding to the service.</p>
 *
 * <p>The dialog registers itself as a [SleepTimerManager.Listener] so it stays in sync if
 * the timer is armed, ticks, finishes, or is cancelled while the sheet is open — including
 * from a second instance of this same dialog opened elsewhere, or the timer simply running
 * out while the user is looking at it.</p>
 *
 * <p>Usage:
 * ```
 * childFragmentManager.showSleepTimerDialog()
 * ```
 * </p>
 *
 * @author Hamza417
 */
class SleepTimerDialog : ScopedBottomSheetFragment(), SleepTimerManager.Listener {

    private lateinit var binding: DialogSleepTimerBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogSleepTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.durationSeekbar.setProgress(SleepTimerPreferences.getLastDurationMinutes().toFloat())
        binding.fadeOutSwitch.isChecked = SleepTimerPreferences.isFadeOutEnabled()
        binding.stopAtEndOfTrackSwitch.isChecked = SleepTimerPreferences.isStopAtEndOfTrackEnabled()

        applyEndOfTrackToggleState(binding.stopAtEndOfTrackSwitch.isChecked)
        refreshPrimaryActionLabel()
        refreshStatus()

        binding.durationSeekbar.setOnSeekChangeListener(object : FelicitySeekbar.OnSeekChangeListener {
            override fun onProgressChanged(seekbar: FelicitySeekbar, progress: Float, fromUser: Boolean) {
                if (fromUser) {
                    SleepTimerPreferences.setLastDurationMinutes(progress.toInt())
                    if (!SleepTimerManager.isArmed) {
                        refreshStatus()
                    }
                }
            }
        })

        binding.stopAtEndOfTrackSwitch.setOnCheckedChangeListener { _, isChecked ->
            SleepTimerPreferences.setStopAtEndOfTrack(isChecked)
            applyEndOfTrackToggleState(isChecked)
            if (!SleepTimerManager.isArmed) {
                refreshStatus()
            }
        }

        binding.fadeOutSwitch.setOnCheckedChangeListener { _, isChecked ->
            SleepTimerPreferences.setFadeOutEnabled(isChecked)
        }

        binding.dismiss.setOnClickListener {
            dismiss()
        }

        binding.primaryAction.setOnClickListener {
            if (SleepTimerManager.isArmed) {
                SleepTimerManager.cancel()
            } else {
                armTimer()
            }
            refreshPrimaryActionLabel()
            refreshStatus()
        }

        SleepTimerManager.registerListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        SleepTimerManager.unregisterListener(this)
    }

    /**
     * Reads the current dialog state and arms the appropriate [SleepTimerManager] mode.
     * The chosen duration is persisted regardless of mode so it's remembered even if the
     * user switches to "stop at end of track" and back later.
     */
    private fun armTimer() {
        val fadeOut = binding.fadeOutSwitch.isChecked
        val minutes = binding.durationSeekbar.getProgress().toInt()
            .coerceIn(SleepTimerPreferences.MIN_DURATION_MINUTES, SleepTimerPreferences.MAX_DURATION_MINUTES)
        SleepTimerPreferences.setLastDurationMinutes(minutes)

        if (binding.stopAtEndOfTrackSwitch.isChecked) {
            SleepTimerManager.startEndOfTrack(fadeOut)
        } else {
            SleepTimerManager.startCountdown(minutes.minutes.inWholeMilliseconds, fadeOut)
        }
    }

    /** Dims and disables the duration seekbar while "stop at end of track" is selected, since it has no effect in that mode. */
    private fun applyEndOfTrackToggleState(stopAtEndOfTrack: Boolean) {
        binding.durationRow.alpha = if (stopAtEndOfTrack) 0.4f else 1f
        binding.durationSeekbar.isEnabled = !stopAtEndOfTrack
    }

    private fun refreshPrimaryActionLabel() {
        binding.primaryAction.text = getString(
                if (SleepTimerManager.isArmed) R.string.sleep_timer_cancel else R.string.sleep_timer_start
        )
    }

    /** Also disables the two mode switches while a timer is running — changing them mid-countdown would be ambiguous. */
    private fun refreshStatus() {
        val armed = SleepTimerManager.isArmed
        binding.stopAtEndOfTrackSwitch.isEnabled = !armed
        if (!armed) {
            applyEndOfTrackToggleState(binding.stopAtEndOfTrackSwitch.isChecked)
        } else {
            binding.durationSeekbar.isEnabled = false
        }

        binding.sleepTimerStatus.text = when (SleepTimerManager.currentMode()) {
            is SleepTimerManager.Mode.EndOfTrack -> getString(R.string.sleep_timer_status_active_end_of_track)
            is SleepTimerManager.Mode.Countdown -> formatRemaining(SleepTimerManager.getRemainingMs())
            null -> getString(R.string.sleep_timer_status_idle, binding.durationSeekbar.getProgress().toInt())
        }
    }

    private fun formatRemaining(remainingMs: Long): String {
        return if (remainingMs > 60_000L) {
            getString(R.string.sleep_timer_status_active_minutes, (remainingMs / 60_000L).toInt().coerceAtLeast(1))
        } else {
            val totalSeconds = (remainingMs / 1000L).toInt().coerceAtLeast(0)
            getString(
                    R.string.sleep_timer_status_active_seconds,
                    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
            )
        }
    }

    // --- SleepTimerManager.Listener, all fired from a background dispatcher — hop to Main before touching views. -----

    override fun onSleepTimerTick(remainingMs: Long) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isAdded) binding.sleepTimerStatus.text = formatRemaining(remainingMs)
        }
    }

    override fun onSleepTimerArmed(mode: SleepTimerManager.Mode) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isAdded) {
                refreshPrimaryActionLabel()
                refreshStatus()
            }
        }
    }

    override fun onSleepTimerFinished(fadeOut: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isAdded) {
                refreshPrimaryActionLabel()
                refreshStatus()
            }
        }
    }

    override fun onSleepTimerCancelled() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isAdded) {
                refreshPrimaryActionLabel()
                refreshStatus()
            }
        }
    }

    companion object {
        fun newInstance(): SleepTimerDialog {
            val args = Bundle()
            val fragment = SleepTimerDialog()
            fragment.arguments = args
            return fragment
        }

        fun FragmentManager.showSleepTimerDialog() {
            val dialog = newInstance()
            dialog.show(this, TAG)
        }

        const val TAG = "SleepTimerDialog"
    }
}
