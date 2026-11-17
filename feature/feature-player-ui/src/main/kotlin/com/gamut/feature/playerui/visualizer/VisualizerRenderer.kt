package com.gamut.feature.playerui.visualizer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.gamut.core.player.AudioAnalysis
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A MilkDrop-style renderer. Every frame the previous picture is pulled through a warp (zoom, rotation, sway
 * and decay driven by the music), the waveform or spectrum is drawn on top, and the result is shown and kept
 * for the next frame. That feedback loop is what gives MilkDrop its flowing, tunnelling look.
 *
 * It is a renderer with built-in presets, not an interpreter for Winamp `.milk` files.
 */
internal class VisualizerRenderer(
    private val analysis: AudioAnalysis,
) : GLSurfaceView.Renderer {
    @Volatile var presetIndex = 0

    @Volatile var live = false
        private set

    private var width = 1
    private var height = 1
    private var fbW = 2
    private var fbH = 2

    private val fbo = IntArray(2)
    private val tex = IntArray(2)
    private var cur = 0

    private var warpProgram = 0
    private var blitProgram = 0
    private var lineProgram = 0

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val strip: FloatBuffer = ByteBuffer.allocateDirect(MAX_STRIP_VERTS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val px = FloatArray(MAX_POINTS)
    private val py = FloatArray(MAX_POINTS)

    private val frame = FrameVars()
    private var lastNs = 0L
    private var time = 0f
    private var angle = 0f

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        warpProgram = program(VERTEX, WARP_FRAGMENT)
        blitProgram = program(VERTEX, BLIT_FRAGMENT)
        lineProgram = program(LINE_VERTEX, LINE_FRAGMENT)
        fbo.fill(0)
        tex.fill(0)
        lastNs = 0L
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        w: Int,
        h: Int,
    ) {
        width = max(1, w)
        height = max(1, h)
        val scale = min(1f, MAX_FEEDBACK_SIDE.toFloat() / max(width, height))
        fbW = max(2, (width * scale).toInt())
        fbH = max(2, (height * scale).toInt())
        releaseTargets()
        GLES20.glGenFramebuffers(2, fbo, 0)
        GLES20.glGenTextures(2, tex, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fbW, fbH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        cur = 0
    }

    override fun onDrawFrame(gl: GL10?) {
        if (fbo[0] == 0) return
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1f / 60f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastNs = now
        time += dt

        analysis.update(dt)
        live = analysis.live
        val presets = Presets.all
        val preset = presets[Math.floorMod(presetIndex, presets.size)]
        frame.reset()
        preset.frame(time, analysis, frame)

        // Warp zoom, rotation and drift are per-frame steps that the feedback loop compounds. Only the ring's
        // own spin needs to accumulate here.
        angle += frame.spin * dt

        val prev = cur
        cur = 1 - cur
        val aspect = width.toFloat() / height

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glViewport(0, 0, fbW, fbH)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawWarp(tex[prev], aspect)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        drawWave(preset.style, aspect)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawBlit(tex[cur])
    }

    private fun drawWarp(
        source: Int,
        aspect: Float,
    ) {
        GLES20.glUseProgram(warpProgram)
        bindQuad(warpProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(warpProgram, "uPrev"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uZoom"), frame.zoom)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uRot"), frame.rot)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uDecay"), frame.decay)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uWarp"), frame.warp)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uTime"), time)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(warpProgram, "uAspect"), aspect)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(warpProgram, "uCenter"), frame.cx, frame.cy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(warpProgram, "uShift"), frame.driftX, frame.driftY)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawBlit(source: Int) {
        GLES20.glUseProgram(blitProgram)
        bindQuad(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blitProgram, "uImage"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blitProgram, "uFlash"), frame.flash)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindQuad(program: Int) {
        val loc = GLES20.glGetAttribLocation(program, "aPos")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
    }

    private fun drawWave(
        style: WaveStyle,
        aspect: Float,
    ) {
        GLES20.glUseProgram(lineProgram)
        val pos = GLES20.glGetAttribLocation(lineProgram, "aPos")
        val color = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glEnableVertexAttribArray(pos)
        val a = analysis
        val thick = frame.thickness

        fun emit(
            n: Int,
            r: Float,
            g: Float,
            b: Float,
            alpha: Float,
        ) {
            val verts = buildStrip(n, thick, aspect)
            if (verts == 0) return
            strip.position(0)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, strip)
            GLES20.glUniform4f(color, r, g, b, alpha)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, verts)
        }

        when (style) {
            WaveStyle.LINES -> {
                val n = LINE_POINTS
                for (side in 0..1) {
                    val src = if (side == 0) a.waveL else a.waveR
                    val yOff = if (side == 0) 0.18f else -0.18f
                    for (i in 0 until n) {
                        val t = i / (n - 1f)
                        val s = src[(t * (AudioAnalysis.WAVE_POINTS - 1)).toInt()]
                        px[i] = (t * 2f - 1f) * aspect * 0.92f
                        py[i] = yOff + s * frame.waveAmp
                    }
                    val h = frame.hue + side * 0.12f
                    emit(n, hue(h, 0), hue(h, 1), hue(h, 2), frame.waveAlpha)
                }
            }

            WaveStyle.CIRCLE -> {
                val n = CIRCLE_POINTS
                for (i in 0 until n) {
                    val t = i / (n - 1f)
                    val s = (a.waveL[(t * (AudioAnalysis.WAVE_POINTS - 1)).toInt()] + a.waveR[(t * (AudioAnalysis.WAVE_POINTS - 1)).toInt()]) * 0.5f
                    val ang = t * 2f * PI.toFloat() + angle
                    val r = frame.radius + s * frame.waveAmp
                    px[i] = cos(ang) * r
                    py[i] = sin(ang) * r
                }
                emit(n, hue(frame.hue, 0), hue(frame.hue, 1), hue(frame.hue, 2), frame.waveAlpha)
            }

            WaveStyle.RING -> {
                val bands = AudioAnalysis.BANDS
                val n = bands * 2 + 1
                for (i in 0 until n) {
                    val band = if (i <= bands) i.coerceAtMost(bands - 1) else (2 * bands - i).coerceAtLeast(0).coerceAtMost(bands - 1)
                    val ang = i / (n - 1f) * 2f * PI.toFloat() + angle
                    val r = frame.radius + a.spectrum[band] * frame.waveAmp * 6f
                    px[i] = cos(ang) * r
                    py[i] = sin(ang) * r
                }
                emit(n, hue(frame.hue, 0), hue(frame.hue, 1), hue(frame.hue, 2), frame.waveAlpha)
            }
        }
    }

    /** Turns the points in [px] and [py] (square space, x scaled by [aspect]) into a thick ribbon. */
    private fun buildStrip(
        n: Int,
        thick: Float,
        aspect: Float,
    ): Int {
        if (n < 2) return 0
        strip.clear()
        for (i in 0 until n) {
            val i0 = max(0, i - 1)
            val i1 = min(n - 1, i + 1)
            val dx = px[i1] - px[i0]
            val dy = py[i1] - py[i0]
            val len = max(sqrt(dx * dx + dy * dy), 1e-5f)
            val nx = -dy / len * thick
            val ny = dx / len * thick
            strip.put((px[i] + nx) / aspect)
            strip.put(py[i] + ny)
            strip.put((px[i] - nx) / aspect)
            strip.put(py[i] - ny)
        }
        return n * 2
    }

    private fun hue(
        h: Float,
        channel: Int,
    ): Float {
        val k = ((channel * 2f / 3f + h) % 1f + 1f) % 1f
        val v = 0.5f + 0.5f * cos(2f * PI.toFloat() * k)
        return 0.25f + 0.75f * v
    }

    private fun releaseTargets() {
        if (fbo[0] != 0) GLES20.glDeleteFramebuffers(2, fbo, 0)
        if (tex[0] != 0) GLES20.glDeleteTextures(2, tex, 0)
        fbo.fill(0)
        tex.fill(0)
    }

    private fun program(
        vertex: String,
        fragment: String,
    ): Int {
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun shader(
        type: Int,
        source: String,
    ): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        return s
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private companion object {
        const val MAX_FEEDBACK_SIDE = 720
        const val LINE_POINTS = 192
        const val CIRCLE_POINTS = 192
        const val MAX_POINTS = 256
        const val MAX_STRIP_VERTS = MAX_POINTS * 2

        const val VERTEX = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val WARP_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uPrev;
            uniform float uZoom;
            uniform float uRot;
            uniform float uDecay;
            uniform float uWarp;
            uniform float uTime;
            uniform float uAspect;
            uniform vec2 uCenter;
            uniform vec2 uShift;
            void main() {
                vec2 p = vUv - uCenter;
                p.x *= uAspect;
                p += uWarp * 0.02 * vec2(sin(p.y * 7.0 + uTime * 1.3), cos(p.x * 7.0 + uTime * 1.1));
                float c = cos(uRot);
                float s = sin(uRot);
                p = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
                p /= uZoom;
                p.x /= uAspect;
                vec3 col = texture2D(uPrev, p + uCenter + uShift).rgb;
                gl_FragColor = vec4(col * uDecay, 1.0);
            }
        """

        const val BLIT_FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uImage;
            uniform float uFlash;
            void main() {
                vec3 col = texture2D(uImage, vUv).rgb;
                vec2 q = vUv - 0.5;
                float vig = 1.0 - 0.55 * dot(q, q) * 2.0;
                gl_FragColor = vec4((col + uFlash * 0.08) * vig, 1.0);
            }
        """

        const val LINE_VERTEX = """
            attribute vec2 aPos;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val LINE_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
