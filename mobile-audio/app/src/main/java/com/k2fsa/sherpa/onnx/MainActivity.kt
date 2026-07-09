package com.k2fsa.sherpa.onnx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var keyEdit: EditText
    private lateinit var englishCheck: CheckBox
    private lateinit var jyutRadio: RadioButton
    private lateinit var pinyinRadio: RadioButton
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val projLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val i = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_CODE, res.resultCode)
                    putExtra(CaptureService.EXTRA_DATA, res.data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                status.text = "Captions running. Play audio; drag the caption; tap a word for its meaning."
            } else {
                status.text = "Screen/audio capture was declined."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        keyEdit = findViewById(R.id.gemini_key)
        englishCheck = findViewById(R.id.english_enabled)
        jyutRadio = findViewById(R.id.reading_jyut)
        pinyinRadio = findViewById(R.id.reading_pinyin)

        keyEdit.setText(Prefs.geminiKey(this))
        englishCheck.isChecked = Prefs.english(this)
        if (Prefs.reading(this) == "pinyin") pinyinRadio.isChecked = true else jyutRadio.isChecked = true

        findViewById<Button>(R.id.start_button).setOnClickListener { startCaptions() }
        findViewById<Button>(R.id.stop_button).setOnClickListener { stopCaptions() }
        findViewById<Button>(R.id.share_transcript).setOnClickListener { shareTranscript() }

        requestBasicPermissions()
    }

    private fun saveSettings() {
        Prefs.setGeminiKey(this, keyEdit.text.toString())
        Prefs.setEnglish(this, englishCheck.isChecked)
        Prefs.setReading(this, if (pinyinRadio.isChecked) "pinyin" else "jyut")
    }

    private fun requestBasicPermissions() {
        val perms = ArrayList<String>()
        perms.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    private fun startCaptions() {
        saveSettings()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            status.text = "Grant 'Display over other apps', then tap Start captions again."
            return
        }
        status.text = "Preparing models (first run downloads ~230 MB over Wi-Fi)…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ModelStore.ensure(applicationContext) { msg -> runOnUiThread { status.text = msg } }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!ok) { status.text = "Model download failed. Connect to Wi-Fi and tap Start again."; return@launch }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun stopCaptions() {
        stopService(Intent(this, CaptureService::class.java))
        status.text = "Stopped."
    }

    private fun shareTranscript() {
        val f = java.io.File(java.io.File(filesDir, "transcripts"), "transcript.txt")
        if (!f.exists() || f.length() == 0L) { status.text = "No transcript yet — run captions first."; return }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Cantonese transcript")
            putExtra(Intent.EXTRA_TEXT, f.readText())
        }
        startActivity(Intent.createChooser(send, "Share transcript"))
    }
}
