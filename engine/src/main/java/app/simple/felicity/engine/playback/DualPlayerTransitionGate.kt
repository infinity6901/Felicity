package app.simple.felicity.engine.playback

/** Pure transition guard, separated from Media3 so race behaviour can be unit-tested. */
internal class DualPlayerTransitionGate {
    enum class State { IDLE, PREPARING, READY, FADING, HANDING_OFF }
    @JvmInline value class Token(val generation: Long)

    private var generation = 0L
    private var token: Token? = null
    var state: State = State.IDLE
        private set

    fun begin(): Token {
        generation += 1
        return Token(generation).also { token = it; state = State.PREPARING }
    }

    fun ready(candidate: Token) = advance(candidate, State.PREPARING, State.READY)
    fun fading(candidate: Token) = advance(candidate, State.READY, State.FADING)
    fun handingOff(candidate: Token) = advance(candidate, State.FADING, State.HANDING_OFF)

    fun complete(candidate: Token): Boolean {
        if (token != candidate || state != State.HANDING_OFF) return false
        clear()
        return true
    }

    fun cancel(candidate: Token): Boolean {
        if (token != candidate) return false
        clear()
        return true
    }

    fun invalidate() { generation += 1; clear() }

    private fun advance(candidate: Token, from: State, to: State): Boolean {
        if (token != candidate || state != from) return false
        state = to
        return true
    }

    private fun clear() { token = null; state = State.IDLE }
}
