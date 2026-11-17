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
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var recordButton: Button
    private var recording = false
    private var player: MediaPlayer? = null
    private var exportFile: File? = null
    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            recording = false; updateButton(); refresh()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val filter = IntentFilter(RecordingService.ACTION_FINISHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(finished, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(finished, filter)
        refresh()
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() {
        try { unregisterReceiver(finished) } catch (_: Exception) {}
        player?.release()
        super.onDestroy()
    }
    private fun buildUi() {
        window.statusBarColor = 0xFFF8F6FC.toInt()
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 0, 16, 8)
            setBackgroundColor(0xFFF8F6FC.toInt())
        }
        // Four text rows of breathing room below the status icons keep the title clear
        // of the camera cutout, battery and clock area.
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 72))
        val header = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFFD9C8E8.toInt())
            setPadding(8, 6, 8, 6)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cassette)
            layoutParams = android.widget.FrameLayout.LayoutParams(52, 52, Gravity.CENTER_VERTICAL or Gravity.START)
            contentDescription = "REC"
        })
        header.addView(TextView(this).apply {
            text = "Ses kaydı"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(0xFF49305F.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Telefon görüşmesinde hoparlörü açın. Kaydı burada başlatın ve görüşme bitince durdurun."
            textSize = 15f; setTextColor(0xFF40364A.toInt()); setPadding(4, 16, 4, 12)
        })
        root.addView(TextView(this).apply { text = "Kayıtlar"; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(0xFF352047.toInt()); setPadding(2, 18, 2, 8) })
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
        // Reserve clear space above the bottom control; the footer never overlays list buttons.
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 28))
        recordButton = Button(this).apply { setOnClickListener { toggleRecording() } }
        root.addView(recordButton, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateButton()
    }
    private fun toggleRecording() {
        if (recording) {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            recording = false
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 20)
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START))
            recording = true
        }
        updateButton()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 20 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleRecording()
    }
    private fun updateButton() {
        if (::recordButton.isInitialized) {
            recordButton.text = if (recording) "Kaydı bitir" else "Kaydı başlat"
            recordButton.setBackgroundColor(if (recording) 0xFFB93857.toInt() else 0xFF7B49A5.toInt())
            recordButton.setTextColor(0xFFFFFFFF.toInt())
        }
    }
    private fun recordingsDir() = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Ses-kayit").apply { mkdirs() }
    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val files = recordingsDir().listFiles()?.filter { it.isFile && it.extension.equals("mp3", true) }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply { text = "Henüz kayıt yok."; textSize = 15f; setPadding(8, 18, 8, 18) })
            return
        }
        files.forEach { file ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(10, 8, 10, 6); setBackgroundColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            }
            card.addView(TextView(this).apply { text = file.nameWithoutExtension.replace('_', ' '); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(0xFF302638.toInt()) })
            val actions = LinearLayout(this).apply { gravity = Gravity.END }
            actions.addView(actionButton("Oynat") { play(file) })
            actions.addView(actionButton("Ad değiştir") { rename(file) })
            actions.addView(actionButton("İndir") { export(file) })
            actions.addView(actionButton("Sil") { confirmDelete(file) })
            card.addView(actions); list.addView(card)
        }
    }
    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; minWidth = 0; setPadding(5, 1, 5, 1); setOnClickListener { action() }
    }
    private fun play(file: File) {
        try {
            player?.release()
            player = MediaPlayer().apply { setDataSource(file.absolutePath); setOnCompletionListener { it.release(); player = null }; prepare(); start() }
        } catch (_: Exception) { toast("Kayıt açılamadı.") }
    }
    private fun rename(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle("Kayıt adını değiştir").setView(input)
            .setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet") { _, _ ->
                val base = input.text.toString().trim().filter { it.isLetterOrDigit() || it in " -_()." }
                if (base.isNotBlank()) {
                    val target = File(file.parentFile, "$base.mp3")
                    if (target.exists()) toast("Bu ad zaten kullanılıyor.") else { file.renameTo(target); refresh() }
                }
            }.show()
    }
    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this).setTitle("Kaydı sil").setMessage("\"${file.nameWithoutExtension}\" kaydı silinsin mi?")
            .setNegativeButton("Vazgeç", null).setPositiveButton("Devam") { _, _ ->
                AlertDialog.Builder(this).setTitle("İkinci onay").setMessage("Bu kayıt kalıcı olarak silinecek. Emin misiniz?")
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Kalıcı sil") { _, _ -> file.delete(); refresh() }.show()
            }.show()
    }
    private fun export(file: File) {
        exportFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/mpeg"; putExtra(Intent.EXTRA_TITLE, file.name)
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
