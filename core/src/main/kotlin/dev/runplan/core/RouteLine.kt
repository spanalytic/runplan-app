package dev.runplan.core

import kotlin.math.hypot

/** An arrowhead on the route: where it sits, the unit direction of travel, and the track point it belongs to. */
data class RouteArrow(val x: Float, val y: Float, val dx: Float, val dy: Float, val index: Int)

/**
 * Screen geometry for drawing a route on the map. Each pass is drawn a little to the right of its
 * direction of travel, so where you ran a road out and back, both ways show side by side instead
 * of one colour hiding the other. Coordinates are screen pixels (y grows downwards).
 */
object RouteLine {
    /**
     * Unit direction of travel at each point, taken across [span] pixels of track either side so
     * GPS jitter doesn't twist it. Where the track doesn't move (a stop), the last direction is kept.
     */
    fun directions(xs: FloatArray, ys: FloatArray, span: Float): Pair<FloatArray, FloatArray> {
        val n = xs.size
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        var lx = 1f
        var ly = 0f
        var first = -1                                   // first point with a real direction
        for (i in 0 until n) {
            var b = i
            while (b > 0 && hypot(xs[i] - xs[b], ys[i] - ys[b]) < span) b--
            var f = i
            while (f < n - 1 && hypot(xs[f] - xs[i], ys[f] - ys[i]) < span) f++
            val vx = xs[f] - xs[b]
            val vy = ys[f] - ys[b]
            val len = hypot(vx, vy)
            if (len > 0.5f) { lx = vx / len; ly = vy / len; if (first < 0) first = i }
            dx[i] = lx; dy[i] = ly
        }
        // the first points of a track that starts with a stop take the first real direction
        for (i in 0 until first) { dx[i] = dx[first]; dy[i] = dy[first] }
        return dx to dy
    }

    /** The track shifted [d] pixels to the right of the direction of travel. */
    fun offset(xs: FloatArray, ys: FloatArray, d: Float, span: Float): Pair<FloatArray, FloatArray> {
        val (dx, dy) = directions(xs, ys, span)
        val ox = FloatArray(xs.size) { xs[it] - dy[it] * d }
        val oy = FloatArray(ys.size) { ys[it] + dx[it] * d }
        return ox to oy
    }

    /** Arrowheads every [every] pixels along the track, the first half that distance from the start. */
    fun arrows(xs: FloatArray, ys: FloatArray, every: Float, span: Float): List<RouteArrow> {
        if (xs.size < 2 || every <= 0) return emptyList()
        val (dx, dy) = directions(xs, ys, span)
        val out = ArrayList<RouteArrow>()
        var next = every / 2
        var walked = 0f
        for (i in 1 until xs.size) {
            val seg = hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            while (seg > 0 && walked + seg >= next) {
                val t = (next - walked) / seg
                out += RouteArrow(xs[i - 1] + t * (xs[i] - xs[i - 1]), ys[i - 1] + t * (ys[i] - ys[i - 1]), dx[i], dy[i], i)
                next += every
            }
            walked += seg
        }
        return out
    }
}
