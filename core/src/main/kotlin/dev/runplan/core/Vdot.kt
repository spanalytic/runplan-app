package dev.runplan.core

import kotlin.math.exp
import kotlin.math.sqrt

/** Race distances accepted as fitness inputs. */
enum class Distance(val label: String, val metres: Double) {
    MILE("Mile", 1609.344),
    K3("3K", 3000.0),
    K5("5K", 5000.0),
    K10("10K", 10000.0),
    K15("15K", 15000.0),
    MI10("10 mile", 16093.44),
    HALF("Half marathon", 21097.5),
    MARATHON("Marathon", 42195.0),
}

/**
 * Daniels & Gilbert VDOT model ("Oxygen Power", 1979).
 * Matches the published VDOT tables (e.g. VDOT 50: T 4:15/km, I 3:55/km, R 3:40/km, M 4:31/km).
 */
object Vdot {
    /** O2 cost (ml/kg/min) of running at v metres/min. */
    fun vo2At(v: Double): Double = -4.60 + 0.182258 * v + 0.000104 * v * v

    /** Fraction of VO2max sustainable for t minutes. */
    fun pctMax(tMin: Double): Double =
        0.8 + 0.1894393 * exp(-0.012778 * tMin) + 0.2989558 * exp(-0.1932605 * tMin)

    fun vdot(distM: Double, tSec: Double): Double {
        val t = tSec / 60.0
        return vo2At(distM / t) / pctMax(t)
    }

    /** Inverse of [vo2At]: metres/min. */
    fun velocityForVo2(vo2: Double): Double {
        val a = 0.000104
        val b = 0.182258
        val c = -(4.60 + vo2)
        return (-b + sqrt(b * b - 4 * a * c)) / (2 * a)
    }

    /** Predicted race time in seconds for [distM] at [vdot] (bisection). */
    fun predictTime(vdot: Double, distM: Double): Double {
        var lo = 60.0
        var hi = 36000.0
        repeat(100) {
            val mid = (lo + hi) / 2
            if (vdot(distM, mid) > vdot) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /**
     * Realistic race-day fitness range if the plan is followed for [weeks] more weeks.
     * Rule of thumb: about one VDOT point per 4 to 6 weeks of consistent training for
     * recreational runners, less for runners who are already fit, capped at +5.
     * This is an estimate for motivation, never used to set training paces.
     */
    fun projected(vdot: Double, weeks: Int): Pair<Double, Double> {
        val train = (weeks - 1).coerceAtLeast(0).toDouble()     // race week adds no fitness
        val scale = when { vdot < 40 -> 1.0; vdot < 50 -> 0.75; else -> 0.5 }
        return (vdot + minOf(4.0, train / 6 * scale)) to (vdot + minOf(5.0, train / 4 * scale))
    }

    /** Race time rounded to a sensible precision for the distance (5 s for 5K up to 1 min for a marathon). */
    fun roundTime(sec: Double, distM: Double): Double {
        val step = when { distM <= 5000 -> 5.0; distM <= 10000 -> 15.0; distM <= 21100 -> 30.0; else -> 60.0 }
        return Math.round(sec / step) * step
    }

    /** Pace (s/km) at a fraction of VO2max. */
    fun paceAtFraction(vdot: Double, frac: Double): Double = 60000.0 / velocityForVo2(vdot * frac)
}

/**
 * Training zones. [code] is a stable internal id (stored and exported); [label] and [word] are
 * what users see, in plain language.
 */
enum class Zone(val code: String, val label: String, val word: String, val use: String) {
    E("E", "Easy", "easy", "Conversational. Easy runs, long runs, warm-ups"),
    M("M", "Marathon pace", "marathon pace", "Your marathon race pace"),
    HM("HM", "Half-marathon pace", "half-marathon pace", "Your half-marathon race pace"),
    T("T", "Tempo", "tempo", "Comfortably hard: a pace you could hold for about an hour"),
    I("I", "Hard", "hard", "Hard reps of 3 to 5 minutes"),
    R("R", "Fast", "fast", "Short, fast reps and strides, relaxed form"),
    G("G", "Race pace", "race pace", "Your goal race pace at current fitness"),
}

/** Pace bands in seconds per km. For every zone, `fast < slow`. */
data class Paces(
    val vdot: Double,
    val easyFast: Double,
    val easySlow: Double,
    val marathon: Double,
    val half: Double,
    val threshold: Double,
    val interval: Double,
    val rep: Double,
    val goal: Double,
) {
    fun band(z: Zone): Pair<Double, Double> {
        if (z == Zone.E) return easyFast to easySlow
        val c = when (z) {
            Zone.M -> marathon
            Zone.HM -> half
            Zone.T -> threshold
            Zone.I -> interval
            Zone.R -> rep
            Zone.G -> goal
            Zone.E -> error("unreachable")
        }
        return (c - TOLERANCE) to (c + TOLERANCE)
    }

    fun centre(z: Zone): Double = band(z).let { (f, s) -> (f + s) / 2 }

    /**
     * Easy has no slow limit during a run, only a ceiling: anything slower than marathon pace
     * counts as easy, so you're only told to ease off when you're well past easy effort.
     */
    val easyCap: Double get() = marathon

    /** What the run coaches against. Same as [band], except Easy is "[easyCap] or slower" (slow = +Inf). */
    fun target(z: Zone): Pair<Double, Double> = if (z == Zone.E) easyCap to Double.POSITIVE_INFINITY else band(z)

    /** Jog pace used to estimate recovery-step distance. */
    val jog: Double get() = easySlow + 20

    companion object {
        const val TOLERANCE = 5.0

        fun of(recent: Distance, recentSec: Double, goal: Distance): Paces =
            fromVdot(Vdot.vdot(recent.metres, recentSec), goal)

        fun fromVdot(v: Double, goal: Distance): Paces = Paces(
            vdot = v,
            easyFast = Vdot.paceAtFraction(v, 0.72),
            easySlow = Vdot.paceAtFraction(v, 0.62),
            marathon = Vdot.predictTime(v, Distance.MARATHON.metres) / 42.195,
            half = Vdot.predictTime(v, Distance.HALF.metres) / 21.0975,
            threshold = Vdot.paceAtFraction(v, 0.88),
            interval = Vdot.paceAtFraction(v, 0.975),
            rep = Vdot.paceAtFraction(v, 1.055),
            goal = Vdot.predictTime(v, goal.metres) / (goal.metres / 1000.0),
        )
    }
}
