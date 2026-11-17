package com.olgun.seskayit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var actionPanel: LinearLayout
    private lateinit var selectionLabel: TextView
    private lateinit var playButton: Button
    private lateinit var renameButton: Button
    private lateinit var exportButton: Button
    private lateinit var deleteButton: Button
    private lateinit var progressPanel: LinearLayout
    private lateinit var progressBar: SeekBar
    private lateinit var progressLabel: TextView

    private var recording = false
    private var stopping = false
    private var longStopTriggered = false
    private var selectedFile: File? = null
    private var selectedAction: String? = null
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var exportFile: File? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val progressTicker = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            if (activePlayer.isPlaying && !progressBar.isPressed) {
                progressBar.progress = activePlayer.currentPosition
                progressLabel.text = formatTime(activePlayer.currentPosition) + " / " + formatTime(activePlayer.duration)
            }
            uiHandler.postDelayed(this, 250L)
        }
    }
    private val stopAfterHold = Runnable {
        if (recording && stopping) {
            longStopTriggered = true
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        }
    }
    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            recording = false
            stopping = false
            longStopTriggered = false
            updateRecordButton()
            uiHandler.postDelayed({
                refresh()
                updateRecordButton()
            }, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val filter = IntentFilter(RecordingService.ACTION_FINISHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(finished, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(finished, filter)
        refresh()
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { stopPlayback(); super.onPause() }
    override fun onDestroy() {
        try { unregisterReceiver(finished) } catch (_: Exception) {}
        uiHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = 0xFFF8F6FC.toInt()
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 16, 0)
            setBackgroundColor(0xFFF8F6FC.toInt())
        }
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 90))
        val header = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            setPadding(8, 6, 8, 6)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cassette)
            layoutParams = android.widget.FrameLayout.LayoutParams(52, 52, Gravity.CENTER_VERTICAL or Gravity.START)
            contentDescription = "Rec"
        })
        header.addView(TextView(this).apply {
            text = "Ses kaydı"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(header)

        recordButton = Button(this)
        recordButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!recording) startRecording()
                    else if (!stopping) {
                        stopping = true
                        longStopTriggered = false
                        updateRecordButton()
                        uiHandler.postDelayed(stopAfterHold, 1_000L)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recording && stopping && !longStopTriggered) {
                        uiHandler.removeCallbacks(stopAfterHold)
                        stopping = false
                        updateRecordButton()
                        if (event.actionMasked == MotionEvent.ACTION_UP) toast("Kaydı durdurmak için 1 saniye basılı tutun.")
                    }
                    true
                }
                else -> true
            }
        }
        root.addView(recordButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        actionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 2, 6, 4)
            visibility = android.view.View.GONE
        }
        selectionLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF4D4D4D.toInt())
            setPadding(2, 4, 2, 0)
        }
        actionPanel.addView(selectionLabel)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playButton = controlButton("Oynat", "play") { selectedFile?.let { play(it) } }
        renameButton = controlButton("Ad", "rename") { selectedFile?.let { rename(it) } }
        exportButton = controlButton("İndir", "export") { selectedFile?.let { export(it) } }
        deleteButton = controlButton("Sil", "delete") { selectedFile?.let { confirmDelete(it) } }
        listOf(playButton, renameButton, exportButton, deleteButton).forEach { button ->
            buttons.addView(button, LinearLayout.LayoutParams(0, 44, 1f).apply { marginEnd = 3 })
        }
        actionPanel.addView(buttons)
        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE
        }
        progressBar = SeekBar(this)
        progressPanel.addView(progressBar, LinearLayout.LayoutParams(0, 40, 1f))
        progressLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF555555.toInt())
            setPadding(6, 0, 2, 0)
        }
        progressPanel.addView(progressLabel)
        actionPanel.addView(progressPanel)
        root.addView(actionPanel, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Android, telefon görüşmesi sırasında üçüncü taraf uygulamaların mikrofonunu susturabilir; bu durumda kayıt sessiz olabilir. Görüşme kaydı için cihazınızın yerleşik çağrı kayıt özelliğini kullanın."
            textSize = 14f
            setTextColor(0xFF40364A.toInt())
            setPadding(4, 8, 4, 4)
        })
        root.addView(TextView(this).apply {
            text = "Kayıtlar"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF352047.toInt())
            setPadding(2, 8, 2, 6)
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Muhammed Yusuf OLGUN"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF808080.toInt())
            setPadding(0, 8, 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 180))
        setContentView(root)

        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) progressLabel.text = formatTime(progress) + " / " + formatTime(player?.duration ?: 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { player?.seekTo(seekBar?.progress ?: 0) }
        })
        updateRecordButton()
        renderSelectedControls()
    }

    private fun controlButton(label: String, key: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        setPadding(2, 0, 2, 0)
        setOnClickListener { selectedAction = key; updateControlColors(); action() }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 20)
            return
        }
        recording = true
        stopping = false
        longStopTriggered = false
        ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START))
        updateRecordButton()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 20 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
    }

    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        recordButton.text = when {
            stopping -> "Kaydı Durdur"
            recording -> "Kayıt sürüyor · durdurmak için 1 sn basılı tut"
            else -> "Kaydı Başlat"
        }
        val color = when {
            stopping -> 0xFFFFA726.toInt()
            else -> 0xFF55B85A.toInt()
        }
        recordButton.setBackgroundColor(color)
        recordButton.setTextColor(0xFF202020.toInt())
    }

    private fun recordingsDir() = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Ses-kayit").apply { mkdirs() }
    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val files = recordingsDir().listFiles()
            ?.filter { it.isFile && it.extension.equals("mp3", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (selectedFile?.exists() != true) selectedFile = null
        if (files.isEmpty()) {
            list.addView(TextView(this).apply { text = "Henüz kayıt yok."; textSize = 15f; setPadding(8, 12, 8, 12) })
            renderSelectedControls()
            return
        }
        files.forEachIndexed { index, file ->
            val row = TextView(this).apply {
                text = (index + 1).toString() + ".  " + file.nameWithoutExtension.replace('_', ' ')
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF302638.toInt())
                setPadding(12, 12, 10, 12)
                setBackgroundColor(if (selectedFile == file) 0xFFD0D0D0.toInt() else 0xFFFFFFFF.toInt())
                isClickable = true
                setOnClickListener {
                    selectedFile = if (selectedFile == file) null else file
                    selectedAction = null
                    refresh()
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 5 }
            list.addView(row)
        }
        renderSelectedControls()
    }

    private fun renderSelectedControls() {
        if (!::actionPanel.isInitialized) return
        val file = selectedFile
        actionPanel.visibility = if (file?.exists() == true) android.view.View.VISIBLE else android.view.View.GONE
        if (file == null || !file.exists()) return
        selectionLabel.text = "Seçili: " + file.nameWithoutExtension.replace('_', ' ')
        playButton.text = if (player?.isPlaying == true && playingFile == file) "Duraklat" else "Oynat"
        progressPanel.visibility = if (player != null && playingFile == file) android.view.View.VISIBLE else android.view.View.GONE
        updateControlColors()
    }

    private fun updateControlColors() {
        if (!::actionPanel.isInitialized) return
        listOf("play" to playButton, "rename" to renameButton, "export" to exportButton, "delete" to deleteButton).forEach { (key, button) ->
            button.setBackgroundColor(if (selectedAction == key) 0xFF777777.toInt() else 0xFFE0E0E0.toInt())
            button.setTextColor(if (selectedAction == key) 0xFFFFFFFF.toInt() else 0xFF303030.toInt())
        }
    }

    private fun play(file: File) {
        try {
            if (playingFile == file && player != null) {
                if (player!!.isPlaying) player!!.pause() else player!!.start()
                renderSelectedControls()
                return
            }
            stopPlayback()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                    selectedAction = "play"
                    renderSelectedControls()
                }
                prepare()
                progressBar.max = duration
                progressBar.progress = 0
                progressLabel.text = "00:00 / " + formatTime(duration)
                start()
            }
            playingFile = file
            uiHandler.removeCallbacks(progressTicker)
            uiHandler.post(progressTicker)
            renderSelectedControls()
        } catch (_: Exception) {
            stopPlayback()
            toast("Kayıt açılamadı.")
        }
    }

    private fun stopPlayback() {
        uiHandler.removeCallbacks(progressTicker)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        playingFile = null
        if (::progressPanel.isInitialized) {
            progressPanel.visibility = android.view.View.GONE
            if (::actionPanel.isInitialized) renderSelectedControls()
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun rename(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle("Kayıt adını değiştir").setView(input)
            .setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet") { _, _ ->
                val base = input.text.toString().trim().filter { it.isLetterOrDigit() || it in " -_()." }
                if (base.isNotBlank()) {
                    val target = File(file.parentFile, "$base.mp3")
                    if (target.exists()) toast("Bu ad zaten kullanılıyor.")
                    else if (file.renameTo(target)) { selectedFile = target; refresh() }
                }
            }.show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this).setTitle("Kaydı sil")
            .setMessage(file.nameWithoutExtension + " kaydı silinsin mi?")
            .setNegativeButton("Vazgeç", null).setPositiveButton("Devam") { _, _ ->
                AlertDialog.Builder(this).setTitle("İkinci onay")
                    .setMessage("Bu kayıt kalıcı olarak silinecek. Emin misiniz?")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Kalıcı sil") { _, _ ->
                        if (playingFile == file) stopPlayback()
                        file.delete()
                        selectedFile = null
                        selectedAction = null
                        refresh()
                    }.show()
            }.show()
    }

    private fun export(file: File) {
        exportFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        startActivityForResult(intent, 30)
    }

    @Deprecated("Deprecated API retained for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30 && resultCode == RESULT_OK && data?.data != null && exportFile != null) {
            try {
                contentResolver.openOutputStream(data.data!!)?.use { output -> exportFile!!.inputStream().use { it.copyTo(output) } }
                toast("MP3 dışa aktarıldı.")
            } catch (_: Exception) { toast("Dışa aktarma başarısız oldu.") }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

