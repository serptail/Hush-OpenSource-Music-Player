package com.gamut.core.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SpatialMode(
    val label: String,
    val hint: String,
    val amount: Float,
) {
    OFF("Off", "The recording as it is", 0f),
    NATURAL("Natural", "A wider, airier stage that stays clear", 0.35f),
    THEATER("Theater", "A room around you, like a small cinema", 0.65f),
    IMMERSIVE("Immersive", "The biggest stage. Best on headphones", 0.92f),
}

/**
 * Headphone virtualizer that fakes a speaker stage around the listener. It is a simulation built from
 * a decorrelated side channel, a head-shadowed cross-ear path with interaural delay, a handful of early
 * reflections and a small height cue. It is not Dolby Atmos decoding.
 */
internal class SpatialVirtualizer {
    var outL = 0.0
        private set
    var outR = 0.0
        private set

    private var rate = 0
    private var amount = -1.0

    private var itd = 1
    private var dlL = DoubleArray(2)
    private var dlR = DoubleArray(2)
    private var dw = 0
    private var hsA = 0.0
    private var lpxL = 0.0
    private var lpxR = 0.0

    private var refSize = 2
    private var ringL = DoubleArray(2)
    private var ringR = DoubleArray(2)
    private var rw = 0
    private var refA = 0.0
    private var lprL = 0.0
    private var lprR = 0.0
    private val tapDelayL = IntArray(TAPS)
    private val tapDelayR = IntArray(TAPS)

    private var apBuf = arrayOf(DoubleArray(1), DoubleArray(1))
    private val apPos = IntArray(2)

    private var bq0 = 1.0
    private var bq1 = 0.0
    private var bq2 = 0.0
    private var bqa1 = 0.0
    private var bqa2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    private var cross = 0.0
    private var scale = 1.0

    fun prepare(
        sampleRate: Int,
        amount: Double,
    ) {
        if (sampleRate != rate) {
            rate = sampleRate
            allocate()
        }
        val a = amount.coerceIn(0.0, 1.0)
        if (a != this.amount) {
            this.amount = a
            retune()
        }
    }

    fun clear() {
        dlL.fill(0.0)
        dlR.fill(0.0)
        ringL.fill(0.0)
        ringR.fill(0.0)
        for (b in apBuf) b.fill(0.0)
        apPos[0] = 0
        apPos[1] = 0
        dw = 0
        rw = 0
        lpxL = 0.0
        lpxR = 0.0
        lprL = 0.0
        lprR = 0.0
        z1 = 0.0
        z2 = 0.0
    }

    /** Keeps decaying filter state from turning into denormals, which are slow on ARM. */
    fun flushDenormals() {
        if (abs(lpxL) < TINY) lpxL = 0.0
        if (abs(lpxR) < TINY) lpxR = 0.0
        if (abs(lprL) < TINY) lprL = 0.0
        if (abs(lprR) < TINY) lprR = 0.0
        if (abs(z1) < TINY) z1 = 0.0
        if (abs(z2) < TINY) z2 = 0.0
    }

    fun process(
        l: Double,
        r: Double,
    ) {
        val a = amount
        val m = (l + r) * 0.5
        val s = (l - r) * 0.5

        var x = s
        for (k in 0 until 2) {
            val buf = apBuf[k]
            val i = apPos[k]
            val delayed = buf[i]
            val v = x + AP_GAIN[k] * delayed
            x = -AP_GAIN[k] * v + delayed
            buf[i] = v
            apPos[k] = if (i + 1 == buf.size) 0 else i + 1
        }
        val wide = s * (1.0 + 0.6 * a) + 0.35 * a * x
        val side = bq0 * wide + z1
        z1 = bq1 * wide - bqa1 * side + z2
        z2 = bq2 * wide - bqa2 * side

        val l1 = m + side
        val r1 = m - side

        val rd = if (dw + 1 == dlL.size) 0 else dw + 1
        val farL = dlL[rd]
        val farR = dlR[rd]
        dlL[dw] = l1
        dlR[dw] = r1
        dw = rd
        lpxL += hsA * (farR - lpxL)
        lpxR += hsA * (farL - lpxR)
        val l2 = l1 + cross * lpxL
        val r2 = r1 + cross * lpxR

        ringL[rw] = l2
        ringR[rw] = r2
        var refL = 0.0
        var refR = 0.0
        for (k in 0 until TAPS) {
            var iL = rw - tapDelayL[k]
            if (iL < 0) iL += refSize
            refL += TAP_GAIN[k] * (if (TAP_L_FROM_RIGHT[k]) ringR[iL] else ringL[iL])
            var iR = rw - tapDelayR[k]
            if (iR < 0) iR += refSize
            refR += TAP_GAIN[k] * (if (TAP_R_FROM_RIGHT[k]) ringR[iR] else ringL[iR])
        }
        rw = if (rw + 1 == refSize) 0 else rw + 1
        lprL += refA * (refL * a - lprL)
        lprR += refA * (refR * a - lprR)

        outL = (l2 + lprL) * scale
        outR = (r2 + lprR) * scale
    }

    private fun allocate() {
        itd = max(1, (rate * ITD_SEC).roundToInt())
        dlL = DoubleArray(itd + 1)
        dlR = DoubleArray(itd + 1)
        hsA = 1.0 - exp(-2.0 * PI * HEAD_SHADOW_HZ / rate)

        refSize = (rate * 0.032).toInt() + 2
        ringL = DoubleArray(refSize)
        ringR = DoubleArray(refSize)
        refA = 1.0 - exp(-2.0 * PI * REFLECTION_LP_HZ / rate)
        for (k in 0 until TAPS) {
            tapDelayL[k] = max(1, (rate * TAP_L_MS[k] / 1000.0).roundToInt())
            tapDelayR[k] = max(1, (rate * TAP_R_MS[k] / 1000.0).roundToInt())
        }

        apBuf =
            arrayOf(
                DoubleArray(max(1, (rate * AP_SEC[0]).roundToInt())),
                DoubleArray(max(1, (rate * AP_SEC[1]).roundToInt())),
            )
        clear()
    }

    private fun retune() {
        val a = amount
        cross = CROSS_GAIN * a
        scale = 1.0 / (1.0 + OUT_COMP * a)

        val gain = 10.0.pow(HEIGHT_DB * a / 40.0)
        val w0 = 2.0 * PI * HEIGHT_HZ / rate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * HEIGHT_Q)
        val a0 = 1.0 + alpha / gain
        bq0 = (1.0 + alpha * gain) / a0
        bq1 = -2.0 * cw / a0
        bq2 = (1.0 - alpha * gain) / a0
        bqa1 = -2.0 * cw / a0
        bqa2 = (1.0 - alpha / gain) / a0
    }

    private companion object {
        const val TAPS = 4
        const val TINY = 1e-25
        const val ITD_SEC = 0.00027
        const val HEAD_SHADOW_HZ = 1_600.0
        const val REFLECTION_LP_HZ = 5_000.0
        const val CROSS_GAIN = 0.45
        const val OUT_COMP = 0.4
        const val HEIGHT_HZ = 7_200.0
        const val HEIGHT_Q = 0.9
        const val HEIGHT_DB = 3.0

        val AP_SEC = doubleArrayOf(0.0053, 0.0087)
        val AP_GAIN = doubleArrayOf(0.5, 0.45)

        val TAP_L_MS = doubleArrayOf(11.3, 23.1, 6.7, 17.9)
        val TAP_R_MS = doubleArrayOf(12.7, 25.7, 7.9, 19.3)
        val TAP_GAIN = doubleArrayOf(0.20, 0.10, 0.14, 0.10)
        val TAP_L_FROM_RIGHT = booleanArrayOf(true, true, false, false)
        val TAP_R_FROM_RIGHT = booleanArrayOf(false, false, true, true)
    }
}
