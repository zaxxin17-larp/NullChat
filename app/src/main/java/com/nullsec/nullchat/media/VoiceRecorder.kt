package com.nullsec.nullchat.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.UUID

/**
 * Records a voice note as a low-bitrate (48 kbps, mono, 22.05 kHz) AAC MPEG-4
 * file. A 60-second note lands around 350-400 KB, comfortably under the 1 MB cap.
 *
 * Duration is capped at [MediaLimits.MAX_VOICE_MILLIS] by the caller (a UI timer
 * or [shouldAutoStop]); [stop] hard-rejects anything over the cap.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var startMs = 0L

    var outputFile: File? = null
        private set

    fun start(): File {
        val file = File(context.cacheDir, "voice_${UUID.randomUUID()}.m4a")
        outputFile = file

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mr.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(48_000)
            setAudioSamplingRate(22_050)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mr
        startMs = SystemClock.elapsedRealtime()
        return file
    }

    fun elapsedMs(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startMs

    fun shouldAutoStop(): Boolean = elapsedMs() >= MediaLimits.MAX_VOICE_MILLIS

    /** Finishes recording. Throws [MediaException] if over the cap. */
    fun stop(): File {
        val file = outputFile ?: throw MediaException("No recording in progress")
        val elapsed = elapsedMs()
        releaseQuietly()
        if (elapsed > MediaLimits.MAX_VOICE_MILLIS + 500) {
            file.delete()
            throw MediaException("Voice note exceeds ${MediaLimits.MAX_VOICE_MILLIS / 1000}s")
        }
        MediaValidator.assertSize(MediaKind.VOICE, file.length())
        return file
    }

    fun cancel() {
        releaseQuietly()
        outputFile?.delete()
        outputFile = null
    }

    private fun releaseQuietly() {
        recorder?.run {
            runCatching { stop() }
            runCatching { release() }
        }
        recorder = null
    }
}
