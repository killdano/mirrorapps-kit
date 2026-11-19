package com.mirrorapps.kit.feedback.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.mirrorapps.kit.MirrorAppsKit
import java.io.File

/**
 * A voice message for the Feedback tab: AAC in an MP4 container (`.m4a`,
 * `audio/mp4`), which the backend's transcription reads. One at a time.
 * Needs RECORD_AUDIO, which the tab asks for when the microphone is tapped.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    /** When the current recording started (phone clock, ms), or 0. */
    var startedAt = 0L
        private set

    val isRecording: Boolean get() = recorder != null

    /** Starts recording. False if the microphone couldn't be opened. */
    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(context.cacheDir, "kit_voice").apply { mkdirs() }
        // Leftovers from a recording the process didn't live to send.
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(dir, "voice-${System.currentTimeMillis()}.m4a")
        val r = newRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            startedAt = System.currentTimeMillis()
            recorder = r
            file = out
            MirrorAppsKit.log.i(TAG, "voice: recording")
            true
        } catch (e: Exception) {
            MirrorAppsKit.log.w(TAG, "voice: couldn't start", e)
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    /**
     * Stops and returns the recording, or null if there's nothing usable
     * (too short for the encoder to have written anything).
     */
    fun stop(): MediaSource? {
        val r = recorder ?: return null
        val out = file
        val started = startedAt
        recorder = null
        file = null
        startedAt = 0L
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // MediaRecorder throws when stopped before any audio was written.
            false
        } finally {
            runCatching { r.release() }
        }
        val duration = System.currentTimeMillis() - started
        if (!ok || out == null || !out.exists() || out.length() == 0L) {
            MirrorAppsKit.log.i(TAG, "voice: nothing recorded")
            out?.delete()
            return null
        }
        MirrorAppsKit.log.i(TAG, "voice: ${duration}ms, ${out.length()}B")
        return MediaReader.fromFile(out, MediaKind.AUDIO, "audio/mp4", started, duration)
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        val r = recorder ?: return
        val out = file
        recorder = null
        file = null
        startedAt = 0L
        runCatching { r.stop() }
        runCatching { r.release() }
        out?.delete()
        MirrorAppsKit.log.i(TAG, "voice: cancelled")
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private companion object {
        const val TAG = "FeedbackMedia"
    }
}
