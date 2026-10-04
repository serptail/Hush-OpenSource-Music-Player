package com.gamut.feature.search

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Records the microphone as 16 kHz mono 16-bit PCM, the format the recognition service wants. Nothing is written to
 * disk; the audio only lives in memory while listening.
 */
class MicRecorder @Inject constructor() {

    /**
     * Runs on a background thread, handing over 100 ms chunks until [onChunk] returns false or the caller's
     * coroutine is cancelled. The microphone is always released. Throws [SecurityException] without the
     * RECORD_AUDIO permission (the caller asks for it first) and [IOException] if the microphone is unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(onChunk: suspend (ByteArray) -> Boolean) = withContext(Dispatchers.IO) {
        val chunkBytes = SAMPLE_RATE * BYTES_PER_SAMPLE / 10
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IOException("The microphone isn't available")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,   // not VOICE_RECOGNITION: that one is tuned for speech and flattens music
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunkBytes * 4),
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("The microphone isn't available")
            recorder.startRecording()
            val buffer = ByteArray(chunkBytes)
            while (isActive) {
                val read = recorder.read(buffer, 0, buffer.size)   // blocks for ~100 ms
                if (read < 0) throw IOException("The microphone stopped working")
                if (read == 0) continue
                if (!onChunk(buffer.copyOf(read))) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_SAMPLE
    }
}
