package com.gamut.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Receives a read-only copy of every PCM buffer that reaches the output, without altering it, and keeps
 * the most recent samples so the visualizer can draw what is actually playing. While no visualizer is on
 * screen [enabled] is false and the tap returns immediately without reading a single sample.
 */
object AudioAnalyzer {
    private const val RING = 4096
    private const val MASK = RING - 1

    private val ringL = FloatArray(RING)
    private val ringR = FloatArray(RING)

    @Volatile private var writePos = 0

    @Volatile private var lastFeedNs = 0L

    @Volatile private var rate = 44_100

    @Volatile var enabled = false

    @UnstableApi
    internal fun newSink(isActive: () -> Boolean): TeeAudioProcessor.AudioBufferSink = TapSink(isActive)

    internal fun sampleRate(): Int = rate

    internal fun lastFeedNanos(): Long = lastFeedNs

    internal fun copyLatest(
        left: FloatArray,
        right: FloatArray,
        count: Int,
    ) {
        val end = writePos
        for (i in 0 until count) {
            val idx = (end - count + i) and MASK
            left[i] = ringL[idx]
            right[i] = ringR[idx]
        }
    }

    @UnstableApi
    private class TapSink(
        private val isActive: () -> Boolean,
    ) : TeeAudioProcessor.AudioBufferSink {
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT
        private var sampleRate = 44_100

        override fun flush(
            sampleRateHz: Int,
            channelCount: Int,
            encoding: Int,
        ) {
            this.sampleRate = sampleRateHz
            this.channels = channelCount
            this.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!enabled || !isActive()) return
            val ch = channels
            if (ch < 1) return
            val b = buffer.order(ByteOrder.LITTLE_ENDIAN)
            val bytes =
                when (encoding) {
                    C.ENCODING_PCM_16BIT -> 2
                    C.ENCODING_PCM_24BIT -> 3
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                    else -> return
                }
            val frames = b.remaining() / (bytes * ch)
            if (frames <= 0) return
            rate = sampleRate
            var pos = b.position()
            var w = writePos
            repeat(frames) {
                var l = 0f
                var r = 0f
                for (c in 0 until ch) {
                    val v =
                        when (encoding) {
                            C.ENCODING_PCM_16BIT -> b.getShort(pos) / 32768f
                            C.ENCODING_PCM_24BIT -> {
                                val raw = (b.get(pos).toInt() and 0xFF) or
                                    ((b.get(pos + 1).toInt() and 0xFF) shl 8) or
                                    (b.get(pos + 2).toInt() shl 16)
                                raw / 8_388_608f
                            }
                            C.ENCODING_PCM_32BIT -> b.getInt(pos) / 2_147_483_648f
                            else -> b.getFloat(pos)
                        }
                    if (c == 0) l = v
                    if (c == 1) r = v
                    pos += bytes
                }
                if (ch == 1) r = l
                ringL[w and MASK] = l
                ringR[w and MASK] = r
                w++
            }
            writePos = w and MASK
            lastFeedNs = System.nanoTime()
        }
    }
}

/**
 * One instance per visualizer. Turns the latest samples into what MilkDrop-style presets expect: a
 * waveform, a spectrum, bass / mid / treble levels that sit near 1.0 on average, smoothed copies of them
 * and a decaying beat impulse.
 */
class AudioAnalysis {
    val waveL = FloatArray(WAVE_POINTS)
    val waveR = FloatArray(WAVE_POINTS)
    val spectrum = FloatArray(BANDS)

    var bass = 1f
        private set
    var mid = 1f
        private set
    var treb = 1f
        private set
    var bassAtt = 1f
        private set
    var midAtt = 1f
        private set
    var trebAtt = 1f
        private set
    var beat = 0f
        private set
    var volume = 0f
        private set
    var live = false
        private set

    private val sampL = FloatArray(FFT_SIZE)
    private val sampR = FloatArray(FFT_SIZE)
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private val cosT = FloatArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE).toFloat() }
    private val sinT = FloatArray(FFT_SIZE / 2) { -sin(2.0 * PI * it / FFT_SIZE).toFloat() }
    private val bandLo = IntArray(BANDS)
    private val bandHi = IntArray(BANDS)
    private var bandsRate = 0

    private var bassAvg = 0.2f
    private var midAvg = 0.2f
    private var trebAvg = 0.2f
    private var sinceBeat = 1f

    fun update(dtSec: Float) {
        val dt = dtSec.coerceIn(0.001f, 0.1f)
        val fresh = AudioAnalyzer.enabled && (System.nanoTime() - AudioAnalyzer.lastFeedNanos()) < FRESH_NS
        live = fresh
        if (fresh) {
            AudioAnalyzer.copyLatest(sampL, sampR, FFT_SIZE)
            System.arraycopy(sampL, FFT_SIZE - WAVE_POINTS, waveL, 0, WAVE_POINTS)
            System.arraycopy(sampR, FFT_SIZE - WAVE_POINTS, waveR, 0, WAVE_POINTS)
        } else {
            val k = 1f - min(1f, dt * 8f)
            for (i in 0 until WAVE_POINTS) {
                waveL[i] *= k
                waveR[i] *= k
            }
            sampL.fill(0f)
            sampR.fill(0f)
        }

        var energy = 0f
        for (i in 0 until WAVE_POINTS) energy += waveL[i] * waveL[i] + waveR[i] * waveR[i]
        volume = sqrt(energy / (2f * WAVE_POINTS))

        if (fresh) {
            analyse()
        } else {
            val k = 1f - min(1f, dt * 6f)
            for (i in 0 until BANDS) spectrum[i] *= k
        }

        val bandsNow = average(0, BASS_END)
        val midNow = average(BASS_END, MID_END)
        val trebNow = average(MID_END, BANDS)

        val avgK = 1f - exp(-dt / AVG_SECONDS)
        bassAvg += (bandsNow - bassAvg) * avgK
        midAvg += (midNow - midAvg) * avgK
        trebAvg += (trebNow - trebAvg) * avgK

        bass = if (fresh) (bandsNow / max(bassAvg, FLOOR)).coerceIn(0f, 4f) else 0f
        mid = if (fresh) (midNow / max(midAvg, FLOOR)).coerceIn(0f, 4f) else 0f
        treb = if (fresh) (trebNow / max(trebAvg, FLOOR)).coerceIn(0f, 4f) else 0f

        val att = 1f - exp(-dt / ATT_SECONDS)
        bassAtt += (bass - bassAtt) * att
        midAtt += (mid - midAtt) * att
        trebAtt += (treb - trebAtt) * att

        sinceBeat += dt
        beat *= exp(-dt / BEAT_DECAY_SECONDS)
        if (fresh && bass > BEAT_THRESHOLD && sinceBeat > BEAT_REFRACTORY) {
            beat = min(1f, (bass - 1f) / 1.4f + 0.35f)
            sinceBeat = 0f
        }
    }

    private fun average(from: Int, to: Int): Float {
        var sum = 0f
        for (i in from until to) sum += spectrum[i]
        return sum / max(1, to - from)
    }

    private fun analyse() {
        for (i in 0 until FFT_SIZE) {
            re[i] = (sampL[i] + sampR[i]) * 0.5f * window[i]
            im[i] = 0f
        }
        fft()
        val sr = AudioAnalyzer.sampleRate()
        if (sr != bandsRate) buildBands(sr)
        for (b in 0 until BANDS) {
            var p = 0f
            val lo = bandLo[b]
            val hi = bandHi[b]
            for (k in lo..hi) p += re[k] * re[k] + im[k] * im[k]
            val mag = sqrt(p / (hi - lo + 1)) / (FFT_SIZE / 4f)
            val db = 20f * log10(mag + 1e-7f)
            val target = ((db + DB_RANGE) / DB_RANGE).coerceIn(0f, 1f)
            val old = spectrum[b]
            spectrum[b] = if (target > old) target else old + (target - old) * 0.35f
        }
    }

    private fun buildBands(sr: Int) {
        bandsRate = sr
        val nyq = sr / 2f
        val fMin = 40f
        val fMax = min(16_000f, nyq * 0.95f)
        val binHz = sr.toFloat() / FFT_SIZE
        var prev = 1
        for (b in 0 until BANDS) {
            val f = fMin * (fMax / fMin).toDouble().pow((b + 1).toDouble() / BANDS).toFloat()
            val hi = max(prev, (f / binHz).toInt()).coerceAtMost(FFT_SIZE / 2 - 1)
            bandLo[b] = min(prev, hi)
            bandHi[b] = hi
            prev = hi + 1
        }
    }

    private fun fft() {
        var j = 0
        for (i in 0 until FFT_SIZE - 1) {
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
            var m = FFT_SIZE shr 1
            while (m >= 1 && (j and m) != 0) {
                j = j xor m
                m = m shr 1
            }
            j = j or m
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size shr 1
            val step = FFT_SIZE / size
            var start = 0
            while (start < FFT_SIZE) {
                var k = 0
                for (n in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[n + half] * wr - im[n + half] * wi
                    val xi = re[n + half] * wi + im[n + half] * wr
                    re[n + half] = re[n] - xr
                    im[n + half] = im[n] - xi
                    re[n] += xr
                    im[n] += xi
                    k += step
                }
                start += size
            }
            size = size shl 1
        }
    }

    companion object {
        const val WAVE_POINTS = 576
        const val BANDS = 48
        private const val BASS_END = 8
        private const val MID_END = 30
        private const val FFT_SIZE = 1024
        private const val FRESH_NS = 250_000_000L
        private const val AVG_SECONDS = 3.5f
        private const val ATT_SECONDS = 0.18f
        private const val BEAT_DECAY_SECONDS = 0.22f
        private const val BEAT_THRESHOLD = 1.38f
        private const val BEAT_REFRACTORY = 0.2f
        private const val DB_RANGE = 70f
        private const val FLOOR = 0.03f
    }
}
