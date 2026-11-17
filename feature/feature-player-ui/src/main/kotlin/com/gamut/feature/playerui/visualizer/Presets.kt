package com.gamut.feature.playerui.visualizer

import com.gamut.core.player.AudioAnalysis
import kotlin.math.cos
import kotlin.math.sin

internal enum class WaveStyle { LINES, CIRCLE, RING }

/** What a preset decides each frame, like the per-frame variables of a MilkDrop preset. */
internal class FrameVars {
    var zoom = 1f
    var rot = 0f
    var decay = 0.95f
    var warp = 0f
    var cx = 0.5f
    var cy = 0.5f
    var driftX = 0f
    var driftY = 0f
    var spin = 0f
    var hue = 0f
    var radius = 0.45f
    var waveAmp = 0.35f
    var waveAlpha = 0.8f
    var thickness = 0.012f
    var flash = 0f

    fun reset() {
        zoom = 1f
        rot = 0f
        decay = 0.95f
        warp = 0f
        cx = 0.5f
        cy = 0.5f
        driftX = 0f
        driftY = 0f
        spin = 0f
        hue = 0f
        radius = 0.45f
        waveAmp = 0.35f
        waveAlpha = 0.8f
        thickness = 0.012f
        flash = 0f
    }
}

internal class Preset(
    val name: String,
    val style: WaveStyle,
    val frame: (t: Float, a: AudioAnalysis, f: FrameVars) -> Unit,
)

internal object Presets {
    val all: List<Preset> =
        listOf(
            Preset("Tunnel", WaveStyle.CIRCLE) { t, a, f ->
                f.zoom = 1.018f + 0.022f * (a.bassAtt - 1f).coerceIn(-0.5f, 2f)
                f.rot = 0.006f * sin(t * 0.3f) + 0.004f * (a.midAtt - 1f)
                f.decay = 0.955f
                f.warp = 0.5f + 0.5f * a.trebAtt
                f.hue = t * 0.03f
                f.radius = 0.28f + 0.1f * a.beat
                f.waveAmp = 0.3f
                f.waveAlpha = 0.55f + 0.45f * a.beat
                f.spin = 0.2f
                f.flash = a.beat
            },
            Preset("Spiral Bloom", WaveStyle.RING) { t, a, f ->
                f.zoom = 1.012f + 0.01f * a.bassAtt
                f.rot = 0.02f * (a.midAtt - 0.6f)
                f.decay = 0.94f
                f.warp = 0.8f
                f.hue = 0.55f + t * 0.02f
                f.radius = 0.2f + 0.05f * a.bass
                f.waveAmp = 0.12f
                f.waveAlpha = 0.7f + 0.3f * a.beat
                f.spin = 0.45f
                f.flash = a.beat * 0.6f
            },
            Preset("Deep Current", WaveStyle.LINES) { t, a, f ->
                f.zoom = 1.004f + 0.004f * a.bassAtt
                f.rot = 0.002f * sin(t * 0.17f)
                f.decay = 0.965f
                f.warp = 1.4f
                f.driftY = 0.0016f
                f.driftX = 0.0008f * cos(t * 0.2f)
                f.hue = 0.1f + 0.1f * sin(t * 0.07f)
                f.waveAmp = 0.45f + 0.2f * a.bassAtt
                f.waveAlpha = 0.75f
                f.thickness = 0.01f
            },
            Preset("Pulse", WaveStyle.CIRCLE) { t, a, f ->
                f.zoom = 1.01f + 0.06f * a.beat
                f.rot = 0.012f * (a.trebAtt - 1f)
                f.decay = 0.9f
                f.warp = 0.2f
                f.hue = 0.85f + t * 0.05f
                f.radius = 0.35f + 0.2f * a.beat
                f.waveAmp = 0.5f * a.volume.coerceIn(0.1f, 0.6f) + 0.1f
                f.waveAlpha = 0.9f
                f.thickness = 0.016f
                f.spin = -0.3f
                f.flash = a.beat
            },
            Preset("Aurora", WaveStyle.LINES) { t, a, f ->
                f.zoom = 1.002f
                f.rot = 0f
                f.decay = 0.972f
                f.warp = 2f + a.midAtt
                f.driftX = 0.002f * sin(t * 0.13f)
                f.driftY = -0.0012f
                f.hue = 0.35f + 0.15f * sin(t * 0.05f)
                f.waveAmp = 0.6f
                f.waveAlpha = 0.6f
                f.thickness = 0.008f
            },
        )
}
