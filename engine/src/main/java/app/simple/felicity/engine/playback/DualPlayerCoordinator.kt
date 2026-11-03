package app.simple.felicity.engine.playback

import androidx.media3.common.Player

/**
 * Owns the logical roles of two long-lived Media3 players.
 *
 * All methods must be called on the service/player application looper. This class intentionally
 * does not perform I/O or player operations: the service must prepare the standby player, verify
 * readiness, update MediaSession and rebind observers before committing a promotion.
 */
class DualPlayerCoordinator(
    first: Player,
    second: Player,
) {
    enum class TransitionState { IDLE, PREPARING, READY, FADING, HANDING_OFF }

    data class TransitionToken internal constructor(val generation: Long)

    init {
        require(first !== second) { "Dual playback requires two distinct Player instances" }
    }

    private var active: Player = first
    private var standby: Player = second
    private var generation: Long = 0L
    private var currentToken: TransitionToken? = null

    var transitionState: TransitionState = TransitionState.IDLE
        private set

    val activePlayer: Player get() = active
    val standbyPlayer: Player get() = standby
    val hasDistinctPlayers: Boolean get() = active !== standby
    val isTransitionInProgress: Boolean get() = transitionState != TransitionState.IDLE

    /** Starts a new transition transaction. A previous in-flight transaction is invalidated. */
    fun beginTransition(): TransitionToken {
        check(hasDistinctPlayers) { "Active and standby players must be distinct" }
        generation += 1L
        return TransitionToken(generation).also {
            currentToken = it
            transitionState = TransitionState.PREPARING
        }
    }

    /** Advances a transaction only if its token is still current (stale coroutines are ignored). */
    fun markReady(token: TransitionToken): Boolean = advance(token, TransitionState.PREPARING, TransitionState.READY)

    fun markFading(token: TransitionToken): Boolean = advance(token, TransitionState.READY, TransitionState.FADING)

    fun markHandingOff(token: TransitionToken): Boolean = advance(token, TransitionState.FADING, TransitionState.HANDING_OFF)

    /**
     * Commits the role swap after the service has verified incoming readiness and performed the
     * MediaSession/observer handoff. The returned player is the new active instance.
     */
    fun commitPromotion(token: TransitionToken): Player {
        check(currentToken == token && transitionState == TransitionState.HANDING_OFF) {
            "Cannot promote a player outside the current handoff transaction"
        }
        check(hasDistinctPlayers) { "Active and standby players must be distinct" }
        val previous = active
        active = standby
        standby = previous
        finishTransaction()
        return active
    }

    /** Invalidates a transition without changing the active player. Safe for skip/error/cancel. */
    fun abortTransition(token: TransitionToken): Boolean {
        if (currentToken != token) return false
        finishTransaction()
        return true
    }

    /** Invalidates any pending transaction, for service teardown or full player rebuild. */
    fun resetTransitions() {
        generation += 1L
        finishTransaction()
    }

    fun isCurrent(token: TransitionToken): Boolean = currentToken == token
    fun isActive(player: Player): Boolean = active === player
    fun isStandby(player: Player): Boolean = standby === player

    private fun advance(token: TransitionToken, expected: TransitionState, next: TransitionState): Boolean {
        if (currentToken != token || transitionState != expected) return false
        transitionState = next
        return true
    }

    private fun finishTransaction() {
        currentToken = null
        transitionState = TransitionState.IDLE
    }
}
