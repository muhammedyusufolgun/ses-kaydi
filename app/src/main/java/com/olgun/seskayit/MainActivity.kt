package com.olgun.seskayit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.provider.DocumentsContract
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var actionPanel: LinearLayout
    private lateinit var selectionLabel: TextView
    private lateinit var renameButton: Button
    private lateinit var exportButton: Button
    private lateinit var downloadsButton: Button
    private lateinit var deleteButton: Button
    private lateinit var textButton: Button
    private lateinit var transcriptPanel: LinearLayout
    private lateinit var transcriptTextView: TextView
    private lateinit var transcriptCopyButton: Button
    private lateinit var transcriptPdfButton: Button
    private var displayedTranscriptFile: File? = null
    private var pendingPdfText: String? = null
    private var pendingPdfName: String = "Ses-kaydi-metni.pdf"
    private val durationCache = mutableMapOf<String, String>()
    private lateinit var recordingsPanel: LinearLayout
    private lateinit var livePanel: LinearLayout
    private lateinit var liveTranscript: TextView
    private lateinit var copyLiveButton: Button

    private var recording = false
    private var blockRestoreUntilStart = false
    private var stopping = false
    private var longStopTriggered = false
    private var selectedFile: File? = null
    private var selectedAction: String? = null
    private var exportFile: File? = null
    private val transcriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val stopAfterHold = Runnable {
        if (recording && stopping) {
            longStopTriggered = true
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        }
    }
    private val transcriptUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            liveTranscript.text = intent?.getStringExtra(RecordingService.EXTRA_TRANSCRIPT).orEmpty().ifBlank { "Konuşma algılanıyor…" }
        }
    }
    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            recording = false
            stopping = false
            longStopTriggered = false
            updateRecordButton()
            updateRecordingPanels()
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
        val transcriptFilter = IntentFilter(RecordingService.ACTION_TRANSCRIPT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(transcriptUpdates, transcriptFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(transcriptUpdates, transcriptFilter)
        val prefs = getSharedPreferences("recording_state", MODE_PRIVATE)
        blockRestoreUntilStart = savedInstanceState == null && RecordingService.serviceRunning
        if (blockRestoreUntilStart) {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            prefs.edit().putBoolean("active", false).putString("live_transcript", "").apply()
        } else if (!RecordingService.serviceRunning) {
            prefs.edit().putBoolean("active", false).apply()
        }
        recording = false
        liveTranscript.text = "Konuşma algılanıyor…"
        refresh()
        updateRecordingPanels()
        updateRecordButton()
    }
    override fun onResume() {
        super.onResume()
        recording = !blockRestoreUntilStart && RecordingService.serviceRunning
        if (!recording) stopping = false
        refresh()
        updateRecordingPanels()
        updateRecordButton()
    }
    override fun onPause() { super.onPause() }
    override fun onDestroy() {
        try { unregisterReceiver(finished) } catch (_: Exception) {}
        try { unregisterReceiver(transcriptUpdates) } catch (_: Exception) {}
        uiHandler.removeCallbacksAndMessages(null)
        transcriptionScope.cancel()
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
            isClickable = true
            isFocusable = true
            setOnClickListener {
                closeTranscriptPanel()
                selectedAction = null
                updateControlColors()
                if (recording) {
                    livePanel.visibility = android.view.View.GONE
                    recordingsPanel.visibility = android.view.View.VISIBLE
                    actionPanel.visibility = android.view.View.GONE
                } else {
                    updateRecordingPanels()
                    recordingsPanel.visibility = android.view.View.VISIBLE
                }
            }
        }, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(header)
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 16))

        recordButton = Button(this)
        recordButton.setOnClickListener {
            if (recording) {
                stopping = true
                updateRecordButton()
                startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            } else {
                blockRestoreUntilStart = false
                startRecording()
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
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        renameButton = controlButton("Yeniden Adlandır", "rename") { selectedFile?.let { rename(it) } }
        deleteButton = controlButton("Sil", "delete") { selectedFile?.let { confirmDelete(it) } }
        textButton = controlButton("Metin", "text") {
            selectedFile?.let {
                if (transcriptPanel.visibility == android.view.View.VISIBLE && displayedTranscriptFile == it) closeTranscriptPanel()
                else showTranscriptPanel(it)
            }
        }
        renameButton.setOnClickListener {
            selectedAction = "rename"
            updateControlColors()
            val file = selectedFile
            if (transcriptPanel.visibility == android.view.View.VISIBLE) {
                closeTranscriptPanel()
                selectedAction = "rename"
                updateControlColors()
            } else file?.let { rename(it) }
        }
        exportButton = controlButton("İndir", "export") { selectedFile?.let { export(it) } }
        downloadsButton = Button(this).apply {
            text = "Git"
            textSize = 11f
            minWidth = 0
            minimumHeight = 0
            setPadding(2, 0, 2, 0)
            setBackgroundColor(0xFFE0E0E0.toInt())
            setTextColor(0xFF303030.toInt())
            setOnClickListener {
                selectedAction = "downloads"
                updateControlColors()
                goToDownloads()
            }
        }
        listOf(
            renameButton to 1.30f,
            textButton to 0.75f,
            deleteButton to 0.60f,
            exportButton to 0.75f,
            downloadsButton to 0.60f
        ).forEach { (button, weight) ->
            buttons.addView(button, LinearLayout.LayoutParams(0, 88, weight).apply { marginEnd = 8 })
        }
        actionPanel.addView(buttons, LinearLayout.LayoutParams(-1, 88))
        root.addView(actionPanel, LinearLayout.LayoutParams(-1, -2))

        transcriptPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(6, 4, 6, 4)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val transcriptToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val transcriptHeading = TextView(this).apply {
            text = "Metin"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF302638.toInt())
        }
        transcriptToolbar.addView(transcriptHeading, LinearLayout.LayoutParams(0, -2, 1f))
        transcriptCopyButton = Button(this).apply {
            text = "Kopyala"
            textSize = 11f
            minWidth = 0
            minimumHeight = 32
            setPadding(8, 0, 8, 0)
            setBackgroundColor(0xFFE0E0E0.toInt())
            setTextColor(0xFF202020.toInt())
        }
        transcriptToolbar.addView(transcriptCopyButton, LinearLayout.LayoutParams(-2, 34).apply { marginEnd = 6 })
        transcriptPdfButton = Button(this).apply {
            text = "PDF olarak aktar"
            textSize = 10f
            minWidth = 0
            minimumHeight = 32
            setPadding(6, 0, 6, 0)
            setBackgroundColor(0xFFE0E0E0.toInt())
            setTextColor(0xFF202020.toInt())
            setOnClickListener {
                flashTranscriptButton(this)
                exportTranscriptPdf()
            }
        }
        transcriptToolbar.addView(transcriptPdfButton, LinearLayout.LayoutParams(-2, 34))
        transcriptPanel.addView(transcriptToolbar, LinearLayout.LayoutParams(-1, -2))
        transcriptTextView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF242028.toInt())
            setPadding(10, 10, 10, 10)
            gravity = Gravity.TOP or Gravity.START
        }
        val transcriptScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(transcriptTextView)
        }
        transcriptPanel.addView(transcriptScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setCopyOnTouch(transcriptCopyButton, closePanelAfterCopy = true) { transcriptTextView.text.toString() }
        root.addView(transcriptPanel, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 32 })

        recordingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recordingsPanel.addView(TextView(this).apply {
            text = "Kayıtlar"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF352047.toInt())
            setPadding(2, 8, 2, 6)
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        recordingsPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(recordingsPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        livePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(8, 8, 8, 8)
        }
        liveTranscript = TextView(this).apply {
            text = "Konuşma algılanıyor…"
            textSize = 17f
            setTextColor(0xFF242028.toInt())
            setPadding(12, 12, 12, 12)
            gravity = Gravity.TOP or Gravity.START
        }
        val liveScroll = ScrollView(this).apply { isFillViewport = true; addView(liveTranscript) }
        livePanel.addView(liveScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        copyLiveButton = Button(this).apply {
            text = "Tümünü Kopyala"
            setBackgroundColor(0xFFFFFFFF.toInt())
            setTextColor(0xFF202020.toInt())
        }
        setCopyOnTouch(copyLiveButton) { liveTranscript.text.toString() }
        livePanel.addView(copyLiveButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(livePanel, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Muhammed Yusuf OLGUN"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF808080.toInt())
            setPadding(0, 8, 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 180))
        setContentView(root)

        updateRecordButton()
        renderSelectedControls()
        updateRecordingPanels()
    }

    private fun updateRecordingPanels() {
        if (!::livePanel.isInitialized) return
        if (recording && ::transcriptPanel.isInitialized) transcriptPanel.visibility = android.view.View.GONE
        livePanel.visibility = if (recording) android.view.View.VISIBLE else android.view.View.GONE
        val showingTranscript = ::transcriptPanel.isInitialized && transcriptPanel.visibility == android.view.View.VISIBLE
        recordingsPanel.visibility = if (recording || showingTranscript) android.view.View.GONE else android.view.View.VISIBLE
        actionPanel.visibility = if (!recording && selectedFile?.exists() == true) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun controlButton(label: String, key: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 9f
        minWidth = 0
        minimumHeight = 0
        setPadding(2, 0, 2, 0)
        setOnClickListener { selectedAction = key; updateControlColors(); action() }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 20)
            return
        }
        blockRestoreUntilStart = false
        liveTranscript.text = ""
        recording = true
        stopping = false
        longStopTriggered = false
        updateRecordButton()
        updateRecordingPanels()
        try {
            ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START))
            uiHandler.postDelayed({
                if (recording && !RecordingService.serviceRunning) {
                    recording = false
                    updateRecordButton()
                    updateRecordingPanels()
                }
            }, 2_000L)
        } catch (error: Exception) {
            recording = false
            updateRecordButton()
            updateRecordingPanels()
            // Keep the recording control ready for another immediate attempt.
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 20 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
    }

    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        recordButton.text = when {
            stopping -> "Kaydediliyor…"
            recording -> "Kaydı Durdur"
            else -> "Kaydı Başlat"
        }
        val color = when {
            stopping -> 0xFFFFA726.toInt()
            recording -> 0xFFFF3B30.toInt()
            else -> 0xFF55B85A.toInt()
        }
        recordButton.setBackgroundColor(color)
        recordButton.setTextColor(0xFF202020.toInt())
    }

    private fun transcriptFile(audioFile: File) =
        File(audioFile.parentFile, audioFile.nameWithoutExtension + ".txt")

    private fun showTranscriptPanel(file: File) {
        if (!file.exists()) return
        displayedTranscriptFile = file
        transcriptPanel.visibility = android.view.View.VISIBLE
        recordingsPanel.visibility = android.view.View.GONE
        transcriptTextView.text = "Metin hazırlanıyor…"
        val cached = transcriptFile(file)
        val cachedText = if (cached.isFile) runCatching { cached.readText() }.getOrDefault("") else ""
        if (cachedText.isNotBlank() && !cachedText.contains("ERROR_TYPE_") &&
            !cachedText.contains("Speech recognition engine", true)) {
            transcriptTextView.text = cachedText
            return
        }
        if (cached.isFile) cached.delete()
        transcribeToTurkish(file) { result, succeeded ->
            if (displayedTranscriptFile != file || transcriptPanel.visibility != android.view.View.VISIBLE) return@transcribeToTurkish
            transcriptTextView.text = if (succeeded && result.isNotBlank()) result else "Bu kayıtta konuşma algılanamadı."
            transcriptScrollToTop()
        }
    }

    private fun closeTranscriptPanel() {
        if (!::transcriptPanel.isInitialized) return
        transcriptPanel.visibility = android.view.View.GONE
        displayedTranscriptFile = null
        if (!recording) recordingsPanel.visibility = android.view.View.VISIBLE
        selectedAction = null
        updateControlColors()
    }

    private fun transcriptScrollToTop() {
        transcriptTextView.parent?.let { (it as? ScrollView)?.scrollTo(0, 0) }
    }

    private fun flashTranscriptButton(button: Button) {
        button.setBackgroundColor(0xFFB9EFC4.toInt())
        uiHandler.postDelayed({ button.setBackgroundColor(0xFFE0E0E0.toInt()) }, 500L)
    }

    private fun setCopyOnTouch(button: Button, closePanelAfterCopy: Boolean = false, textProvider: () -> String) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    if (button === transcriptCopyButton) flashTranscriptButton(button)
                    if (copyTranscript(textProvider())) {
                        view.performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                            else android.view.HapticFeedbackConstants.LONG_PRESS
                        )
                        if (closePanelAfterCopy) {
                            val copiedFile = displayedTranscriptFile
                            uiHandler.postDelayed({
                                if (displayedTranscriptFile == copiedFile) closeTranscriptPanel()
                            }, 1_000L)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun exportTranscriptPdf() {
        val text = transcriptTextView.text?.toString().orEmpty()
        if (text.isBlank() || text == "Metin hazırlanıyor…") {
            toast("Aktarılacak metin henüz hazır değil.")
            return
        }
        pendingPdfText = text
        val base = displayedTranscriptFile?.nameWithoutExtension ?: "Ses-kaydi-metni"
        pendingPdfName = "$base.pdf"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, pendingPdfName)
        }
        try {
            startActivityForResult(intent, 31)
        } catch (_: Exception) {
            pendingPdfText = null
            toast("PDF kaydetme ekranı açılamadı.")
        }
    }

    private fun writeTranscriptPdf(output: OutputStream, text: String) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 15f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            if (paragraph.isBlank()) {
                lines.add("")
            } else {
                var line = ""
                paragraph.trim().split(Regex("\\s+")).forEach { word ->
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (line.isNotEmpty() && bodyPaint.measureText(candidate) > pageWidth - margin * 2) {
                        lines.add(line)
                        line = word
                    } else line = candidate
                }
                if (line.isNotEmpty()) lines.add(line)
            }
        }
        val linesPerPage = 42
        val chunks = lines.chunked(linesPerPage).ifEmpty { listOf(listOf("")) }
        chunks.forEachIndexed { index, pageLines ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create())
            page.canvas.drawText("Ses Kaydı - Metin", margin, 35f, titlePaint)
            var y = 62f
            pageLines.forEach { line ->
                if (line.isNotEmpty()) page.canvas.drawText(line, margin, y, bodyPaint)
                y += 18f
            }
            document.finishPage(page)
        }
        document.writeTo(output)
        document.close()
    }

    private fun copyTranscript(text: String): Boolean {
        if (text.isBlank()) return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Konuşma metni", text))
        return true
    }

    private fun recordingDuration(file: File): String {
        return durationCache.getOrPut(file.absolutePath) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val estimatedMs = if (durationMs > 0L) durationMs else file.length() * 8_000L / 128_000L
                val totalSeconds = (estimatedMs / 1000L).coerceAtLeast(0L)
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            } catch (_: Exception) {
                "00:00"
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }

    private fun transcribeToTurkish(file: File, onDone: (String, Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) {
            onDone("Türkçe ses çözümleme bu Android sürümünde desteklenmiyor.", false)
            return
        }
        transcriptionScope.launch {
            var recognizer: com.google.mlkit.genai.speechrecognition.SpeechRecognizer? = null
            var audioSource: AudioSource? = null
            try {
                val options = speechRecognizerOptions {
                    locale = Locale.forLanguageTag("tr-TR")
                    preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
                }
                val activeRecognizer = SpeechRecognition.getClient(options)
                recognizer = activeRecognizer
                when (activeRecognizer.checkStatus()) {
                    FeatureStatus.AVAILABLE -> Unit
                    FeatureStatus.DOWNLOADABLE -> {
                        var ready = false
                        var failed = false
                        activeRecognizer.download().collect { status ->
                            when (status) {
                                is DownloadStatus.DownloadCompleted -> ready = true
                                is DownloadStatus.DownloadFailed -> failed = true
                                else -> Unit
                            }
                        }
                        if (!ready || failed) throw IllegalStateException("Türkçe ses modeli indirilemedi.")
                    }
                    FeatureStatus.DOWNLOADING -> throw IllegalStateException("Türkçe ses modeli indiriliyor. Biraz sonra yeniden deneyin.")
                    else -> throw IllegalStateException("Bu cihazda Türkçe ses modeli kullanılamıyor.")
                }

                val source = AudioSource.fromFile(file)
                audioSource = source
                val request = com.google.mlkit.genai.speechrecognition.speechRecognizerRequest {
                    this.audioSource = source
                }
                val parts = mutableListOf<String>()
                activeRecognizer.startRecognition(request).collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            response.text.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> Unit
                        else -> Unit
                    }
                }
                val result = parts.joinToString("\n\n").trim()
                if (result.isBlank()) throw IllegalStateException("Bu kayıtta konuşma algılanamadı.")
                transcriptFile(file).writeText(result)
                onDone(result, true)
            } catch (_: Exception) {
                onDone("Bu kayıtta konuşma algılanamadı.", false)
            } finally {
                try { audioSource?.close() } catch (_: Exception) {}
                try { recognizer?.close() } catch (_: Exception) {}
            }
        }
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
                val baseName = file.nameWithoutExtension.replace('_', ' ')
                val nameHasDuration = Regex("\\(\\d+:\\d+\\)$").containsMatchIn(file.nameWithoutExtension)
                text = (index + 1).toString() + ".  " + baseName + if (nameHasDuration) "" else "  (" + recordingDuration(file) + ")"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFF302638.toInt())
                setPadding(12, 12, 10, 12)
                setBackgroundColor(if (selectedFile == file) 0xFFD0D0D0.toInt() else 0xFFFFFFFF.toInt())
                isClickable = true
                setOnClickListener {
                    closeTranscriptPanel()
                    selectedFile = file
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
        updateControlColors()
    }

    private fun updateControlColors() {
        if (!::actionPanel.isInitialized) return
        val colors = mapOf(
            "rename" to 0xFF90CAF9.toInt(),
            "export" to 0xFFFFCC80.toInt(),
            "downloads" to 0xFFFFF59D.toInt(),
            "delete" to 0xFFEF9A9A.toInt(),
            "text" to 0xFF90CAF9.toInt()
        )
        listOf("rename" to renameButton, "export" to exportButton, "downloads" to downloadsButton, "delete" to deleteButton, "text" to textButton).forEach { (key, button) ->
            button.setBackgroundColor(if (selectedAction == key) colors.getValue(key) else 0xFFE0E0E0.toInt())
            button.setTextColor(0xFF303030.toInt())
        }
    }

    private fun rename(file: File) {
        val input = EditText(this).apply { setText(file.nameWithoutExtension); selectAll() }
        AlertDialog.Builder(this).setTitle("Kayıt adını değiştir").setView(input)
            .setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet") { _, _ ->
                val base = input.text.toString().trim().filter { it.isLetterOrDigit() || it in " -_()." }
                if (base.isNotBlank()) {
                    val target = File(file.parentFile, "$base.mp3")
                    if (target.exists()) toast("Bu ad zaten kullanılıyor.")
                    else if (file.renameTo(target)) {
                        closeTranscriptPanel()
                        val oldTranscript = transcriptFile(file)
                        if (oldTranscript.exists()) oldTranscript.renameTo(transcriptFile(target))
                        selectedFile = target
                        refresh()
                    }
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
                        closeTranscriptPanel()
                        file.delete()
                        transcriptFile(file).delete()
                        selectedFile = null
                        selectedAction = null
                        refresh()
                    }.show()
            }.show()
    }

    private fun goToDownloads() {
        try {
            startActivity(Intent("android.intent.action.VIEW_DOWNLOADS"))
        } catch (_: Exception) {
            try {
                val initialDownloads = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialDownloads)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Keep the recording screen usable if no file browser is installed.
            }
        }
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
        } else if (requestCode == 31 && resultCode == RESULT_OK && data?.data != null) {
            val text = pendingPdfText
            try {
                if (text != null) contentResolver.openOutputStream(data.data!!)?.use { output -> writeTranscriptPdf(output, text) }
                toast("Metin PDF olarak kaydedildi.")
            } catch (_: Exception) {
                toast("PDF kaydedilemedi.")
            } finally {
                pendingPdfText = null
            }
        } else if (requestCode == 31) {
            pendingPdfText = null
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

