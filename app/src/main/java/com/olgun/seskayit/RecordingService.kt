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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import dev.unicornwhodev.mp3codec.Mp3Encoder
import dev.unicornwhodev.mp3codec.Mp3EncoderConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.olgun.seskayit.START"
        const val ACTION_STOP = "com.olgun.seskayit.STOP"
        const val ACTION_STOP_DELAYED = "com.olgun.seskayit.STOP_DELAYED"
        const val ACTION_FINISHED = "com.olgun.seskayit.FINISHED"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 17
    }
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private val stopHandler = Handler(Looper.getMainLooper())
    private val delayedStop = Runnable { requestStop() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_DELAYED) {
            stopHandler.removeCallbacks(delayedStop)
            stopHandler.postDelayed(delayedStop, 1_500L)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopHandler.removeCallbacks(delayedStop)
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
            worker = Thread({ recordLoop(startId) }, "mp3-record").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
    }

    private fun recordLoop(startId: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val rate = 48_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { finishService(startId); return }
        val size = maxOf(min, 8192)
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "Ses-kayit").apply { mkdirs() }
        val temporary = File(dir, ".recording-${System.currentTimeMillis()}.part")
        var audio: AudioRecord? = null
        var stream: FileOutputStream? = null
        var encoder: Mp3Encoder? = null
        var successful = false
        try {
            audio = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Mikrofon başlatılamadı")
            recorder = audio
            stream = FileOutputStream(temporary)
            encoder = Mp3Encoder.open(Mp3EncoderConfig(rate, 1, 128), stream)
            audio.startRecording()
            val pcm = ByteArray(size)
            while (running.get()) {
                val count = audio.read(pcm, 0, pcm.size)
                if (count > 0) encoder.writePcm16Le(if (count == pcm.size) pcm else pcm.copyOf(count))
            }
            encoder.finish()
            stream.flush()
            encoder.close()
            encoder = null
            stream.close()
            stream = null

            if (temporary.length() > 0) {
                val endStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.getDefault()).format(Date())
                var destination = File(dir, "$endStamp.mp3")
                var suffix = 1
                while (destination.exists()) destination = File(dir, "$endStamp-$suffix.mp3").also { suffix++ }
                successful = temporary.renameTo(destination)
            }
        } catch (_: Exception) {
            temporary.delete()
        } finally {
            running.set(false)
            try { audio?.stop() } catch (_: Exception) {}
            audio?.release()
            recorder = null
            try { encoder?.close() } catch (_: Exception) {}
            try { stream?.close() } catch (_: Exception) {}
            if (!successful) temporary.delete()
            finishService(startId)
        }
    }

    private fun finishService(startId: Int) {
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cassette)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_cassette))
            .setColor(0xFF65D96E.toInt())
            .setContentTitle("Ses-kayit kayıt yapıyor")
            .setContentText("Mikrofon üzerinden ses kaydı etkin")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Kaydı bitir", stop).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ses kaydı", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
