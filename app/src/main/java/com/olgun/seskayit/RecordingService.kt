package com.olgun.seskayit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import dev.unicornwhodev.mp3codec.Mp3Encoder
import dev.unicornwhodev.mp3codec.Mp3EncoderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.olgun.seskayit.START"
        const val ACTION_STOP = "com.olgun.seskayit.STOP"
        const val ACTION_FINISHED = "com.olgun.seskayit.FINISHED"
        const val ACTION_TRANSCRIPT = "com.olgun.seskayit.TRANSCRIPT"
        const val EXTRA_TRANSCRIPT = "transcript"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 17
        private const val PREFS = "recording_state"
        private const val PREF_RECORDING = "active"
        private const val PREF_TRANSCRIPT = "live_transcript"
        private const val PREF_TRANSCRIPT_PATH = "live_transcript_path"
        @Volatile var serviceRunning: Boolean = false
            private set
    }

    private val running = AtomicBoolean(false)
    private val transcriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var transcriptPath: File? = null
    private var worker: Thread? = null
    private var recognitionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (!running.get()) {
            createChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, notification)
            running.set(true)
            serviceRunning = true
            worker = Thread({ recordLoop(startId) }, "audio-and-transcript").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
    }

    private fun recordLoop(startId: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sampleRate = 48_000
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) {
            finishService(startId)
            return
        }

        val bufferSize = maxOf(minimum, 3_200)
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "Ses-kayit").apply { mkdirs() }
        val temporaryAudio = File(dir, ".recording-${System.currentTimeMillis()}.mp3.part")
        val temporaryTranscript = File(dir, ".transcript-${System.currentTimeMillis()}.txt.part")
        transcriptPath = temporaryTranscript
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        preferences.edit()
            .putBoolean(PREF_RECORDING, true)
            .putString(PREF_TRANSCRIPT, "")
            .putString(PREF_TRANSCRIPT_PATH, temporaryTranscript.absolutePath)
            .apply()

        var capture: AudioRecord? = null
        var mp3Stream: FileOutputStream? = null
        var encoder: Mp3Encoder? = null
        var readSource: AudioSource? = null
        var writerThread: Thread? = null
        val transcriptQueue = ArrayBlockingQueue<ByteArray>(400)
        val stopChunk = ByteArray(0)
        val transcriptionActive = AtomicBoolean(true)
        var audioSaved = false

        try {
            val audio = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            capture = audio
            recorder = audio
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Mikrofon başlatılamadı")

            mp3Stream = FileOutputStream(temporaryAudio)
            encoder = Mp3Encoder.open(Mp3EncoderConfig(sampleRate, 1, 128), mp3Stream)
            audio.startRecording()

            // Microphone-to-MP3 recording starts first. Speech setup is optional and
            // must never cancel the audio recording if model or pipe setup fails.
            try {
                val pipe = ParcelFileDescriptor.createPipe()
                try {
                    readSource = AudioSource.fromPfd(pipe[0], AudioSource.Mode.STREAMING)
                    val writeDescriptor = pipe[1]
                    writerThread = Thread({
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(writeDescriptor).use { output ->
                                while (true) {
                                    val chunk = transcriptQueue.take()
                                    if (chunk.isEmpty()) break
                                    output.write(chunk)
                                }
                            }
                        } catch (_: Exception) {
                            transcriptionActive.set(false)
                        }
                    }, "speech-audio-pipe").also { it.start() }

                    val source = readSource
                    recognitionJob = transcriptionScope.launch {
                        runSpeechRecognition(source)
                    }
                } catch (_: Exception) {
                    transcriptionActive.set(false)
                    try { pipe[0].close() } catch (_: Exception) {}
                    try { pipe[1].close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                transcriptionActive.set(false)
            }
            val pcm = ByteArray(bufferSize)
            while (running.get()) {
                val count = audio.read(pcm, 0, pcm.size)
                if (count > 0) {
                    val alignedCount = count - (count % 2)
                    if (alignedCount > 0) {
                        val frame = if (alignedCount == pcm.size) pcm.copyOf() else pcm.copyOf(alignedCount)
                        encoder.writePcm16Le(frame)
                        if (transcriptionActive.get()) transcriptQueue.offer(downsample48To16(frame))
                    }
                }
            }

            encoder.finish()
            mp3Stream.flush()
            encoder.close()
            encoder = null
            mp3Stream.close()
            mp3Stream = null
            while (!transcriptQueue.offer(stopChunk, 250, TimeUnit.MILLISECONDS)) {
                if (recognitionJob?.isCompleted == true) break
            }
            writerThread.join(3_000)
            runBlocking { recognitionJob?.join() }

            if (temporaryAudio.length() > 0) {
                val endStamp = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
                var baseName = endStamp
                var suffix = 1
                var destinationAudio = File(dir, "$baseName.mp3")
                while (destinationAudio.exists()) {
                    baseName = "$endStamp-$suffix"
                    destinationAudio = File(dir, "$baseName.mp3")
                    suffix++
                }
                val destinationTranscript = File(dir, "$baseName.txt")
                audioSaved = temporaryAudio.renameTo(destinationAudio)
                if (audioSaved && temporaryTranscript.exists()) {
                    temporaryTranscript.renameTo(destinationTranscript)
                }
            }
        } catch (_: Exception) {
        } finally {
            running.set(false)
            try { capture?.stop() } catch (_: Exception) {}
            capture?.release()
            recorder = null
            try { encoder?.close() } catch (_: Exception) {}
            try { mp3Stream?.close() } catch (_: Exception) {}
            if (transcriptionActive.get()) {
                while (!transcriptQueue.offer(stopChunk)) {
                    transcriptQueue.poll()
                }
            }
            try { writerThread?.join(1_000) } catch (_: Exception) {}
            try { readSource?.close() } catch (_: Exception) {}
            try { recognitionJob?.cancel() } catch (_: Exception) {}
            if (!audioSaved) temporaryAudio.delete()
            if (!audioSaved) temporaryTranscript.delete()
            finishService(startId)
        }
    }

    private fun downsample48To16(input: ByteArray): ByteArray {
        val sampleCount = input.size / 2
        val outputSamples = sampleCount / 3
        val output = ByteArray(outputSamples * 2)
        for (i in 0 until outputSamples) {
            val base = i * 6
            val a = (input[base].toInt() and 0xFF) or (input[base + 1].toInt() shl 8)
            val b = (input[base + 2].toInt() and 0xFF) or (input[base + 3].toInt() shl 8)
            val c = (input[base + 4].toInt() and 0xFF) or (input[base + 5].toInt() shl 8)
            val sa = if (a >= 32768) a - 65536 else a
            val sb = if (b >= 32768) b - 65536 else b
            val sc = if (c >= 32768) c - 65536 else c
            val value = ((sa + sb + sc) / 3).coerceIn(-32768, 32767)
            output[i * 2] = (value and 0xFF).toByte()
            output[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return output
    }

    private suspend fun runSpeechRecognition(audioSource: AudioSource?) {
        val source = audioSource ?: return
        val options = speechRecognizerOptions {
            locale = Locale.forLanguageTag("tr-TR")
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        val recognizer = SpeechRecognition.getClient(options)
        val confirmed = StringBuilder()
        var partial = ""
        try {
            when (recognizer.checkStatus()) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    var ready = false
                    var failed = false
                    recognizer.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadCompleted -> ready = true
                            is DownloadStatus.DownloadFailed -> failed = true
                            else -> Unit
                        }
                    }
                    if (!ready || failed) throw IllegalStateException("Türkçe ses modeli indirilemedi.")
                }
                else -> throw IllegalStateException("Türkçe ses modeli bu cihazda kullanılamıyor.")
            }

            val request = speechRecognizerRequest { this.audioSource = source }
            recognizer.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        partial = response.text.trim()
                        publishTranscript((confirmed.toString() + partial).trim(), false)
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        val finalPart = response.text.trim()
                        if (finalPart.isNotEmpty()) {
                            if (confirmed.isNotEmpty()) confirmed.append("\n\n")
                            confirmed.append(finalPart)
                        }
                        partial = ""
                        publishTranscript(confirmed.toString(), true)
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        publishTranscriptIfEmpty(response.e.message ?: "Türkçe metin çözümlenemedi.")
                    }
                    else -> Unit
                }
            }
        } catch (error: Exception) {
            publishTranscriptIfEmpty(error.message ?: "Türkçe metin çözümlenemedi.")
        } finally {
            try { source.close() } catch (_: Exception) {}
            try { recognizer.close() } catch (_: Exception) {}
        }
    }

    private fun publishTranscript(text: String, persistFile: Boolean) {
        val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        preferences.edit().putString(PREF_TRANSCRIPT, text).apply()
        if (persistFile) {
            try { transcriptPath?.writeText(text) } catch (_: Exception) {}
        }
        sendBroadcast(
            Intent(ACTION_TRANSCRIPT)
                .setPackage(packageName)
                .putExtra(EXTRA_TRANSCRIPT, text)
        )
    }

    private fun publishTranscriptIfEmpty(message: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val existing = prefs.getString(PREF_TRANSCRIPT, "").orEmpty()
        if (existing.isBlank()) publishTranscript(message, false)
    }

    private fun finishService(startId: Int) {
        running.set(false)
        serviceRunning = false
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_RECORDING, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
    }

    override fun onDestroy() {
        running.set(false)
        serviceRunning = false
        transcriptionScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cassette)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_cassette))
            .setColor(0xFF65D96E.toInt())
            .setContentTitle("Ses kaydı sürüyor")
            .setContentText("Ses ve Türkçe metin kaydediliyor")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Kaydı bitir", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ses kaydı", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
