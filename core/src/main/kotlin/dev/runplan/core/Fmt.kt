package dev.runplan.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

object Fmt {
    /** 312.4 -> "5:12" (per km). Non-finite -> "--:--". */
    fun pace(secPerKm: Double): String {
        if (!secPerKm.isFinite() || secPerKm <= 0 || secPerKm > 3600) return "--:--"
        val s = secPerKm.roundToLong()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** "5:03–5:13", or "6:43 or slower" for a ceiling-only band (slow not finite). */
    fun band(fast: Double, slow: Double): String =
        if (fast.isFinite() && !slow.isFinite()) "${pace(fast)} or slower" else "${pace(fast)}–${pace(slow)}"

    /** 3725 -> "1:02:05", 325 -> "5:25". */
    fun duration(sec: Double): String {
        val s = sec.coerceAtLeast(0.0).roundToLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = (s % 60).toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }

    /** 5000.0 -> "5 km", 21097.5 -> "21.1 km", 400.0 -> "400 m". */
    fun distance(metres: Double): String = when {
        metres < 1000 -> "${metres.roundToLong()} m"
        metres % 1000 == 0.0 -> "${(metres / 1000).toLong()} km"
        else -> "%.1f km".format(metres / 1000)
    }

    fun km(metres: Double, decimals: Int = 2): String = "%.${decimals}f".format(metres / 1000)

    /** Rough duration for planning, to the nearest 5 minutes: "45 min", "1 h 25 min", "2 h". */
    fun estimate(sec: Double): String {
        val m = (Math.round(sec / 300.0) * 5).coerceAtLeast(5)
        return when {
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    fun minutes(sec: Double): String {
        val m = sec / 60
        return if (m == m.toLong().toDouble()) "${m.toLong()} min" else "%.1f min".format(m)
    }

    /** Human readable step amount: "5 min", "800 m", "12 km". */
    fun amount(step: Step): String = when {
        step.metres != null -> distance(step.metres)
        step.secs != null -> if (step.secs < 120 && step.secs % 60 != 0.0) "${step.secs.toLong()} s" else minutes(step.secs)
        else -> "Open"
    }

    /** One-line summary of a workout, e.g. "15 min easy, 5 × (5 min hard + 2 min jog), 10 min easy". */
    fun summary(w: Workout): String = w.blocks.joinToString(", ") { b ->
        when (b) {
            is Single -> if (b.step.hill && b.step.isOpen) "jog to the hill" else stepShort(b.step)
            is Repeat -> "${b.times} × (" + b.steps.filter { it.kind != StepKind.READY }.joinToString(" + ") { stepShort(it) } + ")"
        }
    }

    private fun stepShort(s: Step): String = when {
        s.hill && s.isOpen -> "jog down"
        s.hill && s.kind == StepKind.RECOVERY -> "${amount(s)} rest"
        else -> amount(s) + when {
            s.zone != null -> " ${s.zone.word}"
            s.hill && s.kind == StepKind.INTERVAL -> " uphill"
            s.kind == StepKind.RECOVERY -> " jog"
            else -> ""
        }
    }
}

object Dates {
    /** The local calendar day (epoch day) a wall-clock time falls on: a run is filed under the day it started. */
    fun epochDay(wallMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().toEpochDay()
}
