package com.gamut.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * The "space" and "texture" half of the Mods. Chain: tape wobble -> bass shelf -> stereo reverb (Freeverb: 8
 * parallel combs + 4 allpasses per channel, with pre-delay and damping) -> low-pass -> vinyl (light bit-crush,
 * crackle + hiss). Works on 16-bit PCM only; any other format is passed through untouched. Costs nothing while
 * every knob is neutral.
 *
 * The knobs are @Volatile: written from the UI thread, read by the playback thread. This runs BEFORE ExoPlayer's
 * speed/pitch stage, so times and cutoffs set here are scaled by it; Media3MusicPlayer.applyMod compensates.
 */
@UnstableApi
internal class ModAudioProcessor : BaseAudioProcessor() {
    @Volatile var reverbMix = 0f          // 0..1   wet amount
    @Volatile var reverbDecaySec = 3f     // approx. RT60 of the tail
    @Volatile var reverbPreDelayMs = 20f  // 0..180
    @Volatile var reverbDamping = 0.5f    // 0..1   how fast the highs die in the tail
    @Volatile var lowPassHz = 20_000f     // >= 19 000 = off
    @Volatile var vinyl = 0f              // 0..1   crackle + hiss + a touch of bit-crush
    @Volatile var bassDb = 0f             // low shelf @ 120 Hz
    @Volatile var wobbleCents = 0f        // tape wow/flutter depth

    private var channels = 2
    private var rate = 44100
    private var dirty = false

    private var combL = emptyArray<FloatArray>()
    private var combR = emptyArray<FloatArray>()
    private var apL = emptyArray<FloatArray>()
    private var apR = emptyArray<FloatArray>()
    private var combPosL = IntArray(0)
    private var combPosR = IntArray(0)
    private var combStoreL = FloatArray(0)
    private var combStoreR = FloatArray(0)
    private var apPosL = IntArray(0)
    private var apPosR = IntArray(0)

    private var preBuf = FloatArray(0)
    private var prePos = 0
    private var wobL = FloatArray(0)
    private var wobR = FloatArray(0)
    private var wobPos = 0
    private var wowPh = 0f
    private var fluPh = 0f
    private var bsL = 0f
    private var bsR = 0f

    private var hpLow = 0f
    private var lp1L = 0f; private var lp2L = 0f
    private var lp1R = 0f; private var lp2R = 0f
    private var popEnv = 0f
    private var seed = 0x2F6E2B1

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET   // inactive: ExoPlayer skips this processor for formats we can't handle
        }
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        allocate()
        return inputAudioFormat
    }

    override fun onFlush() { clearState() }      // a seek / track change must not drag the old tail along
    override fun onReset() { clearState() }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = 2 * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) return
        val out = replaceOutputBuffer(inputBuffer.remaining())

        val mix = reverbMix.coerceIn(0f, 1f)
        val vin = vinyl.coerceIn(0f, 1f)
        val cutoffHz = lowPassHz.coerceIn(500f, 20_000f)
        val bass = bassDb.coerceIn(-12f, 12f)
        val wob = wobbleCents.coerceIn(0f, 60f)
        val doReverb = mix > 0.001f
        val doLp = cutoffHz < 19_000f
        val doVinyl = vin > 0.001f
        val doBass = kotlin.math.abs(bass) > 0.1f
        val doWobble = wob > 0.5f
        if (!doReverb && !doLp && !doVinyl && !doBass && !doWobble) {
            if (dirty) { clearState(); dirty = false }
            out.put(inputBuffer)
            out.flip()
            return
        }
        dirty = true

        // Comb feedback from the wanted decay: RT60 = 3 * T / -log10(g), T = average comb length (~31.7 ms).
        val feedback = 10.0.pow(-3.0 * COMB_AVG_SEC / reverbDecaySec.coerceIn(0.2f, 12f)).toFloat().coerceIn(0.3f, 0.985f)
        val damp = 0.05f + 0.4f * reverbDamping.coerceIn(0f, 1f)   // 0.5 -> 0.25, the classic Freeverb value
        val preSamples = (reverbPreDelayMs.coerceIn(0f, 180f) * rate / 1000f).toInt().coerceIn(0, preBuf.size - 1)
        val bassA = (1.0 - exp(-2.0 * PI * 120.0 / rate)).toFloat()
        val bassGain = 10f.pow(bass / 20f) - 1f
        // Sine delay modulation of depth D samples at f Hz bends pitch by D * 2 * PI * f / rate; solve for D.
        val cents = 0.000578f * wob   // ~ (2^(cents/1200) - 1)
        val wowD = cents * rate / (2f * PI.toFloat() * WOW_HZ)
        val fluD = 0.25f * cents * rate / (2f * PI.toFloat() * FLUTTER_HZ)
        val wowInc = 2f * PI.toFloat() * WOW_HZ / rate
        val fluInc = 2f * PI.toFloat() * FLUTTER_HZ / rate
        val wobBase = 0.01f * rate
        val dry = 1f - 0.5f * mix
        val wet = mix * 5f
        val hpA = (2.0 * PI * 150.0 / rate).toFloat().coerceAtMost(1f)   // keep lows out of the reverb (no mud)
        val lpA = (1.0 - exp(-2.0 * PI * cutoffHz / rate)).toFloat()
        val q = 2f.pow(15f - 3f * vin)                                     // 16 -> 13 bits
        val popP = vin * 0.00007f
        val hiss = 0.004f * vin

        repeat(frames) {
            var l = inputBuffer.getShort() / 32768f
            var r = if (channels == 2) inputBuffer.getShort() / 32768f else l

            if (doWobble) {
                wobL[wobPos] = l; wobR[wobPos] = r
                val d = wobBase + wowD * sin(wowPh) + fluD * sin(fluPh)
                var rp = wobPos - d
                if (rp < 0f) rp += wobL.size
                val i0 = rp.toInt()
                val i1 = if (i0 + 1 == wobL.size) 0 else i0 + 1
                val fr = rp - i0
                l = wobL[i0] * (1f - fr) + wobL[i1] * fr
                r = wobR[i0] * (1f - fr) + wobR[i1] * fr
                wobPos = if (wobPos + 1 == wobL.size) 0 else wobPos + 1
                wowPh += wowInc; if (wowPh > 6.2831855f) wowPh -= 6.2831855f
                fluPh += fluInc; if (fluPh > 6.2831855f) fluPh -= 6.2831855f
            }

            if (doBass) {
                bsL += bassA * (l - bsL); l += bassGain * bsL
                bsR += bassA * (r - bsR); r += bassGain * bsR
            }

            if (doReverb) {
                val mono = (l + r) * 0.5f
                hpLow += hpA * (mono - hpLow)
                preBuf[prePos] = (mono - hpLow) * 0.03f
                val x = preBuf[(prePos - preSamples + preBuf.size) % preBuf.size]
                prePos = if (prePos + 1 == preBuf.size) 0 else prePos + 1
                var wl = 0f
                var wr = 0f
                for (i in combL.indices) {
                    val bl = combL[i]; val pl = combPosL[i]
                    val yl = bl[pl]
                    combStoreL[i] = yl * (1f - damp) + combStoreL[i] * damp
                    bl[pl] = x + combStoreL[i] * feedback
                    combPosL[i] = if (pl + 1 == bl.size) 0 else pl + 1
                    wl += yl

                    val br = combR[i]; val pr = combPosR[i]
                    val yr = br[pr]
                    combStoreR[i] = yr * (1f - damp) + combStoreR[i] * damp
                    br[pr] = x + combStoreR[i] * feedback
                    combPosR[i] = if (pr + 1 == br.size) 0 else pr + 1
                    wr += yr
                }
                for (i in apL.indices) {
                    val bl = apL[i]; val pl = apPosL[i]
                    val ol = bl[pl]
                    val inL = wl
                    wl = -inL + ol
                    bl[pl] = inL + ol * 0.5f
                    apPosL[i] = if (pl + 1 == bl.size) 0 else pl + 1

                    val br = apR[i]; val pr = apPosR[i]
                    val or = br[pr]
                    val inR = wr
                    wr = -inR + or
                    br[pr] = inR + or * 0.5f
                    apPosR[i] = if (pr + 1 == br.size) 0 else pr + 1
                }
                l = l * dry + wl * wet
                r = r * dry + wr * wet
            }

            if (doLp) {
                lp1L += lpA * (l - lp1L); lp2L += lpA * (lp1L - lp2L); l = lp2L
                lp1R += lpA * (r - lp1R); lp2R += lpA * (lp1R - lp2R); r = lp2R
            }

            if (doVinyl) {
                l = (l * q).roundToInt() / q
                r = (r * q).roundToInt() / q
                // vinyl: a soft hiss plus the odd short pop
                val noise = (nextRandom() * 2f - 1f) * hiss
                if (nextRandom() < popP) popEnv = (if (nextRandom() > 0.5f) 1f else -1f) * (0.03f + 0.12f * nextRandom())
                val crackle = noise + popEnv
                popEnv *= 0.85f
                l += crackle
                r += crackle
            }

            out.putShort((l.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            if (channels == 2) out.putShort((r.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.flip()
    }

    private fun nextRandom(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed ushr 8) / 16777216f
    }

    private fun allocate() {
        val k = rate / 44100f
        fun size(n: Int) = (n * k).roundToInt().coerceAtLeast(8)
        combL = Array(COMB.size) { FloatArray(size(COMB[it])) }
        combR = Array(COMB.size) { FloatArray(size(COMB[it] + SPREAD)) }
        apL = Array(ALLPASS.size) { FloatArray(size(ALLPASS[it])) }
        apR = Array(ALLPASS.size) { FloatArray(size(ALLPASS[it] + SPREAD)) }
        combPosL = IntArray(COMB.size); combPosR = IntArray(COMB.size)
        combStoreL = FloatArray(COMB.size); combStoreR = FloatArray(COMB.size)
        apPosL = IntArray(ALLPASS.size); apPosR = IntArray(ALLPASS.size)
        preBuf = FloatArray((rate * 0.2f).toInt())
        wobL = FloatArray((rate * 0.05f).toInt()); wobR = FloatArray(wobL.size)
        clearState()
    }

    private fun clearState() {
        combL.forEach { it.fill(0f) }; combR.forEach { it.fill(0f) }
        apL.forEach { it.fill(0f) }; apR.forEach { it.fill(0f) }
        combPosL.fill(0); combPosR.fill(0); apPosL.fill(0); apPosR.fill(0)
        combStoreL.fill(0f); combStoreR.fill(0f)
        preBuf.fill(0f); wobL.fill(0f); wobR.fill(0f)
        prePos = 0; wobPos = 0; wowPh = 0f; fluPh = 0f; bsL = 0f; bsR = 0f
        hpLow = 0f; lp1L = 0f; lp2L = 0f; lp1R = 0f; lp2R = 0f; popEnv = 0f
    }

    private companion object {
        val COMB = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)   // Freeverb tunings @ 44.1 kHz
        val ALLPASS = intArrayOf(556, 441, 341, 225)
        const val SPREAD = 23        // right channel is a little longer: that's what makes it wide
        const val COMB_AVG_SEC = 0.0317f   // mean comb delay (~1400 samples @ 44.1 kHz)
        const val WOW_HZ = 0.6f            // slow speed drift
        const val FLUTTER_HZ = 7f          // fast speed flutter (a quarter of the wow depth)
    }
}
