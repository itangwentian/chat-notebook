package com.chatnotebook.app.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException

data class VoiceRecording(val file: File, val durationMs: Long)

/** Records AAC/M4A audio into the app cache; the caller copies it into the attachment store. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun elapsedMs(): Long = if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    fun start(): Result<Unit> {
        if (recorder != null) return Result.success(Unit)
        return try {
            val directory = File(context.cacheDir, "voice").apply { mkdirs() }
            val file = File(directory, "voice-${System.currentTimeMillis()}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            target = file
            startedAt = SystemClock.elapsedRealtime()
            Result.success(Unit)
        } catch (error: Exception) {
            release()
            target?.delete()
            target = null
            startedAt = 0L
            Result.failure(IOException("录音失败，请检查麦克风", error))
        }
    }

    /** Stops recording and returns null when the clip is shorter than [MIN_DURATION_MS]. */
    fun stop(): Result<VoiceRecording?> {
        val mediaRecorder = recorder ?: return Result.success(null)
        val file = target
        val duration = elapsedMs()
        return try {
            mediaRecorder.stop()
            mediaRecorder.release()
            recorder = null
            target = null
            startedAt = 0L
            if (file == null || duration < MIN_DURATION_MS) {
                file?.delete()
                Result.success(null)
            } else {
                Result.success(VoiceRecording(file, duration))
            }
        } catch (error: Exception) {
            runCatching { mediaRecorder.release() }
            recorder = null
            target = null
            startedAt = 0L
            file?.delete()
            if (duration < MIN_DURATION_MS) {
                Result.success(null)
            } else {
                Result.failure(IOException("录音失败，请重试", error))
            }
        }
    }

    fun cancel() {
        runCatching { recorder?.stop() }
        release()
        target?.delete()
        target = null
        startedAt = 0L
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
    }

    companion object {
        const val MIN_DURATION_MS = 1_000L
        const val MAX_DURATION_MS = 60_000L
    }
}
