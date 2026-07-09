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
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private lateinit var langYue: RadioButton
    private lateinit var langZh: RadioButton
    private lateinit var langJa: RadioButton
    private lateinit var sessionsContainer: LinearLayout
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
        langYue = findViewById(R.id.lang_yue)
        langZh = findViewById(R.id.lang_zh)
        langJa = findViewById(R.id.lang_ja)

        keyEdit.setText(Prefs.geminiKey(this))
        englishCheck.isChecked = Prefs.english(this)
        if (Prefs.reading(this) == "pinyin") pinyinRadio.isChecked = true else jyutRadio.isChecked = true
        when (Prefs.lang(this)) {
            "zh" -> langZh.isChecked = true
            "ja" -> langJa.isChecked = true
            else -> langYue.isChecked = true
        }

        findViewById<Button>(R.id.start_button).setOnClickListener { startCaptions() }
        findViewById<Button>(R.id.stop_button).setOnClickListener { stopCaptions() }
        sessionsContainer = findViewById(R.id.sessions_container)

        requestBasicPermissions()
    }

    private fun saveSettings() {
        Prefs.setGeminiKey(this, keyEdit.text.toString())
        Prefs.setEnglish(this, englishCheck.isChecked)
        Prefs.setReading(this, if (pinyinRadio.isChecked) "pinyin" else "jyut")
        Prefs.setLang(this, when { langZh.isChecked -> "zh"; langJa.isChecked -> "ja"; else -> "yue" })
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

    override fun onResume() {
        super.onResume()
        renderSessions()
    }

    private fun renderSessions() {
        sessionsContainer.removeAllViews()
        val sessions = Sessions.list(this)
        if (sessions.isEmpty()) {
            sessionsContainer.addView(TextView(this).apply {
                text = "No sessions yet. Run captions and they'll appear here."
                setPadding(0, 8, 0, 8)
            })
            return
        }
        for (s in sessions) {
            sessionsContainer.addView(TextView(this).apply {
                text = Sessions.name(this@MainActivity, s.id)
                textSize = 16f
                setPadding(0, 20, 0, 20)
                setOnClickListener { openSession(s.id) }
            })
        }
    }

    private fun openSession(id: String) {
        AlertDialog.Builder(this)
            .setTitle(Sessions.name(this, id))
            .setMessage(Sessions.content(this, id).ifBlank { "(empty)" })
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("transcript", Sessions.content(this, id)))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Rename") { _, _ -> renameSession(id) }
            .setNegativeButton("Delete") { _, _ -> deleteSession(id) }
            .show()
    }

    private fun renameSession(id: String) {
        val input = EditText(this).apply { setText(Sessions.name(this@MainActivity, id)) }
        AlertDialog.Builder(this)
            .setTitle("Rename session")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                Sessions.setName(this, id, input.text.toString().trim().ifBlank { Sessions.defaultName(id) })
                renderSessions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSession(id: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete session?")
            .setMessage(Sessions.name(this, id))
            .setPositiveButton("Delete") { _, _ -> Sessions.delete(this, id); renderSessions() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
