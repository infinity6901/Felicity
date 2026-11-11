package app.simple.felicity.decorations.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.coordinatorlayout.widget.CoordinatorLayout
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * A container that lets sibling or descendant views be real glass over whatever it shows.
 *
 * While [glassEnabled] is on, the host records everything it draws (the screens, lists, artwork) into a
 * [RenderNode]. A glass surface then asks [drawBackdrop] for that picture: the host replays the part
 * of it that lies behind the surface into a second node carrying a blur [RenderEffect] and, on
 * Android 13+, an AGSL refraction shader that bends the picture along the rim like the edge of a lens.
 * Everything stays on the GPU; there is no bitmap capture.
 *
 * Glass targets are invalidated whenever anything inside the host changes, so the glass follows
 * scrolling and animation frame by frame. Below Android 12 [drawBackdrop] returns false and the
 * surface falls back to a plain translucent tint.
 */
class GlassHostLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : CoordinatorLayout(context, attrs, defStyleAttr) {

    private class BlurState {
        var node: RenderNode? = null
        var appliedKey: String? = null
        var shader: Any? = null
    }

    private val targets = ArrayList<View>(2)
    private val blurStates = WeakHashMap<View, BlurState>()

    /** True when the host can actually produce glass on this device. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var glassEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                contentNode = null
                blurStates.clear()
            }
            invalidate()
            targets.forEach { it.invalidate() }
        }

    fun addGlassTarget(view: View) {
        if (!targets.contains(view)) targets.add(view)
    }

    fun removeGlassTarget(view: View) {
        targets.remove(view)
        blurStates.remove(view)
    }

    private var contentNode: RenderNode? = null

    internal fun isDescendant(view: View): Boolean {
        var current = view.parent
        while (current != null) {
            if (current === this) return true
            current = (current as? View)?.parent
        }
        return false
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!glassEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
            return
        }
        val node = contentNode ?: RenderNode("felicity-glass-content").also { contentNode = it }
        node.setPosition(0, 0, width, height)
        val rc = node.beginRecording(width, height)
        recording = true
        try {
            super.dispatchDraw(rc)
        } finally {
            recording = false
            node.endRecording()
        }
        canvas.drawRenderNode(node)
        // Descendant glass targets are drawn here, over the recording, under [drawingTargets] = true
        // so their own RenderNodes never record [contentNode] (which would create a RenderNode cycle).
        // Sibling targets (such as MiniPlayer) are drawn by their own parent after this host.
        val time = drawingTime
        drawingTargets = true
        try {
            for (target in targets) {
                if (target.visibility != VISIBLE || !target.isAttachedToWindow) continue
                if (!isDescendant(target)) continue
                if (target.parent === this) {
                    drawChild(canvas, target, time)
                } else {
                    val (hx, hy) = offsetInHost(target)
                    val save = canvas.save()
                    canvas.translate(hx, hy)
                    target.draw(canvas)
                    canvas.restoreToCount(save)
                }
            }
        } finally {
            drawingTargets = false
        }
    }

    private fun offsetInHost(view: View): Pair<Float, Float> {
        var vx = 0f
        var vy = 0f
        var current: View? = view
        while (current != null && current !== this) {
            vx += current.x
            vy += current.y
            current = current.parent as? View
        }
        return vx to vy
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (recording && child in targets) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    internal var recording = false
    internal var drawingTargets = false
    private var invalidatingTargets = false

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (!glassEnabled || invalidatingTargets) return
        // Re-record the host backdrop and refresh any sibling targets without recursing.
        invalidatingTargets = true
        try {
            invalidate()
            for (i in targets.indices) {
                val t = targets[i]
                if (t !== child && t !== target && !isDescendant(t)) {
                    t.invalidate()
                }
            }
        } finally {
            invalidatingTargets = false
        }
    }

    /**
     * Draws the blurred, refracted picture of what is behind [card] into [canvas].
     *
     * @param canvas the glass surface's canvas, already clipped to its rounded shape
     * @param target the glass surface
     * @param card the glass shape in [canvas] coordinates
     * @param originY extra vertical offset of [canvas] inside [target]
     * @param cornerRadius corner radius of the shape, which the refraction follows
     * @param blur blur radius in pixels
     * @param refraction 0..1 strength of the rim refraction (ignored below Android 13)
     * @param depth width in pixels of the band at the rim that bends
     * @return false when nothing was drawn and the caller should use its fallback
     */
    fun drawBackdrop(
            canvas: Canvas, target: View, card: RectF, originY: Float,
            cornerRadius: Float, blur: Float, refraction: Float, depth: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!glassEnabled || recording || !canvas.isHardwareAccelerated) return false
        // Never allow a descendant target's own RenderNode update pass to record contentNode.
        if (isDescendant(target) && !drawingTargets) return false
        val content = contentNode ?: return false
        if (card.width() <= 0f || card.height() <= 0f) return false

        val margin = ceil(blur * 2f + depth).toInt()
        val w = ceil(card.width()).toInt()
        val h = ceil(card.height()).toInt()
        val nodeW = w + margin * 2
        val nodeH = h + margin * 2

        // Where the glass sits inside this host (supports both direct children, nested views, and siblings).
        val (targetHostX, targetHostY) = if (isDescendant(target)) {
            offsetInHost(target)
        } else {
            val hostLoc = IntArray(2)
            val targetLoc = IntArray(2)
            getLocationInWindow(hostLoc)
            target.getLocationInWindow(targetLoc)
            (targetLoc[0] - hostLoc[0]).toFloat() to (targetLoc[1] - hostLoc[1]).toFloat()
        }
        val hostX = targetHostX + card.left
        val hostY = targetHostY + originY + card.top

        val state = blurStates.getOrPut(target) { BlurState() }
        val node = state.node ?: RenderNode("felicity-glass-blur").also { state.node = it }
        node.setPosition(0, 0, nodeW, nodeH)

        val key = "$nodeW/$nodeH/$margin/$blur/$refraction/$depth/$cornerRadius"
        if (key != state.appliedKey) {
            state.appliedKey = key
            node.setRenderEffect(buildEffect(state, blur, refraction, depth, cornerRadius, margin.toFloat(), w.toFloat(), h.toFloat()))
        }

        val rec = node.beginRecording(nodeW, nodeH)
        try {
            rec.translate(margin - hostX, margin - hostY)
            rec.drawRenderNode(content)
        } finally {
            node.endRecording()
        }

        val save = canvas.save()
        canvas.translate(card.left - margin, card.top - margin)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(save)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildEffect(
            state: BlurState, blur: Float, refraction: Float, depth: Float, radius: Float,
            margin: Float, w: Float, h: Float
    ): RenderEffect {
        val blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || refraction <= 0.01f) return blurEffect
        val s = (state.shader as? RuntimeShader) ?: RuntimeShader(REFRACTION_AGSL).also { state.shader = it }
        s.setFloatUniform("box", margin, margin, margin + w, margin + h)
        s.setFloatUniform("radius", radius.coerceAtMost(minOf(w, h) / 2f))
        s.setFloatUniform("depth", depth)
        s.setFloatUniform("strength", refraction.coerceIn(0f, 1f))
        val refract = RenderEffect.createRuntimeShaderEffect(s, "content")
        // The blur runs first, then the refraction bends the blurred picture along the rim.
        return RenderEffect.createChainEffect(refract, blurEffect)
    }

    companion object {
        /**
         * Rim refraction for a rounded rectangle: inside the outer [depth] pixels the sample point is
         * pulled inward along the surface normal, strongest at the edge, with a slight per-channel
         * offset so the rim disperses colour the way thick glass does.
         */
        private const val REFRACTION_AGSL = """
            uniform shader content;
            uniform float4 box;
            uniform float radius;
            uniform float depth;
            uniform float strength;

            half4 main(float2 p) {
                float2 c = (box.xy + box.zw) * 0.5;
                float2 hs = (box.zw - box.xy) * 0.5;
                float2 d = p - c;
                float2 q = abs(d) - (hs - float2(radius));
                float sd = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
                float t = clamp(1.0 + sd / depth, 0.0, 1.0);
                float bend = t * t * strength * depth;
                float2 s = float2(d.x < 0.0 ? -1.0 : 1.0, d.y < 0.0 ? -1.0 : 1.0);
                float2 n;
                if (q.x > 0.0 && q.y > 0.0) {
                    n = normalize(s * q);
                } else if (q.x > q.y) {
                    n = float2(s.x, 0.0);
                } else {
                    n = float2(0.0, s.y);
                }
                float2 off = -n * bend;
                half4 g = content.eval(p + off);
                half r = content.eval(p + off * 0.92).r;
                half b = content.eval(p + off * 1.08).b;
                return half4(r, g.g, b, g.a);
            }
        """
    }
}
