package com.k2fsa.sherpa.onnx

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.github.houbb.opencc4j.util.ZhConverterUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "mobile-audio"

/**
 * Foreground service (type mediaProjection). Captures internal audio → Silero VAD.
 *  - reader thread: reads audio, feeds VAD, accumulates the in-progress utterance, and every
 *    ~500 ms hands a snapshot to the decoder as a "partial" (streaming effect). On segment end,
 *    enqueues the final. Never blocks on ASR, so no audio is dropped.
 *  - decoder thread: decodes finals (priority) and partials serially → SenseVoice → HK-trad →
 *    overlay. English translation runs async per finalized line. A per-session transcript is
 *    written to filesDir/transcripts/transcript.txt.
 */
class CaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "captions"
        private const val NID = 1
        @Volatile var running = false
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var overlay: CaptionOverlay? = null
    private var vad: Vad? = null
    private var asr: OfflineRecognizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var capturing = false

    private val finalQueue = LinkedBlockingQueue<FloatArray>()
    @Volatile private var pendingPartial: FloatArray? = null

    private data class TLine(var zh: String, var en: String?)
    private val transcript = ArrayList<TLine>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundNotif()
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopEverything() }
        }, Handler(Looper.getMainLooper()))

        overlay = CaptionOverlay(this).also {
            it.setReading(Prefs.reading(this)); it.show(); it.message("Loading models…")
        }
        running = true
        scope.launch { initAndRun() }
        return START_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Captions", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, CH)
            .setContentTitle("Subtitle Everything")
            .setContentText("Captioning device audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NID, n)
        }
    }

    private fun initAndRun() {
        try {
            Dict.load(this)
            val p = ModelStore.paths(this)
            vad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = p.vad, threshold = 0.5f,
                        minSilenceDuration = 0.25f, minSpeechDuration = 0.25f, windowSize = 512
                    ),
                    sampleRate = 16000, numThreads = 1, provider = "cpu"
                )
            )
            asr = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = getFeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = p.senseVoice, language = "yue", useInverseTextNormalization = true
                        ),
                        tokens = p.tokens, numThreads = 2
                    )
                )
            )
            startCapture()
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            overlay?.message("Init failed: ${e.message}")
        }
    }

    private fun startCapture() {
        val mp = projection ?: return
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(min * 4)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        record?.startRecording()
        capturing = true
        overlay?.message("Listening… play some audio.")
        thread(name = "reader") { readerLoop() }
        thread(name = "decoder") { decoderLoop() }
    }

    private fun flatten(chunks: List<FloatArray>): FloatArray {
        var total = 0
        for (c in chunks) total += c.size
        val out = FloatArray(total)
        var o = 0
        for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
        return out
    }

    private fun readerLoop() {
        val v = vad ?: return
        val r = record ?: return
        val buf = ShortArray(512)
        val chunks = ArrayList<FloatArray>()
        var lastPartial = 0L
        while (capturing) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) continue
            val samples = FloatArray(n) { buf[it].toFloat() / 32768.0f }
            v.acceptWaveform(samples)
            if (v.isSpeechDetected()) {
                chunks.add(samples)
                val t = System.currentTimeMillis()
                if (t - lastPartial > 500) {
                    val flat = flatten(chunks)
                    if (flat.size >= 6400) { pendingPartial = flat; lastPartial = t } // >=0.4s
                }
            }
            while (!v.empty()) {
                val seg = v.front(); v.pop()
                finalQueue.add(seg.samples)
                chunks.clear(); pendingPartial = null; lastPartial = 0
            }
        }
    }

    private fun decoderLoop() {
        val a = asr ?: return
        while (capturing) {
            val fin = finalQueue.poll(50, TimeUnit.MILLISECONDS)
            if (fin != null) {
                val txt = decode(a, fin)
                if (txt.isNotBlank()) onFinal(toTrad(txt))
                continue
            }
            val p = pendingPartial ?: continue
            pendingPartial = null
            val txt = decode(a, p)
            if (txt.isNotBlank()) overlay?.partial(toTrad(txt))
        }
    }

    private fun onFinal(trad: String) {
        val id = overlay?.commit(trad) ?: -1
        val tline = TLine(trad, null)
        synchronized(transcript) { transcript.add(tline) }
        writeTranscript()
        if (Prefs.english(this)) {
            val key = Prefs.geminiKey(this)
            scope.launch {
                val en = Translator.translate(trad, key)
                if (en != null) {
                    overlay?.setEnglish(id, en)
                    synchronized(transcript) { tline.en = en }
                    writeTranscript()
                }
            }
        }
    }

    private fun toTrad(s: String): String = try { ZhConverterUtil.toTraditional(s) } catch (e: Exception) { s }

    private fun decode(a: OfflineRecognizer, samples: FloatArray): String {
        val s = a.createStream()
        s.acceptWaveform(samples, 16000)
        a.decode(s)
        val t = a.getResult(s).text
        s.release()
        return t
    }

    private fun writeTranscript() {
        try {
            val dir = File(filesDir, "transcripts").apply { mkdirs() }
            val sb = StringBuilder()
            synchronized(transcript) {
                for (l in transcript) {
                    sb.append(l.zh).append("\n")
                    if (!l.en.isNullOrBlank()) sb.append(l.en).append("\n")
                    sb.append("\n")
                }
            }
            File(dir, "transcript.txt").writeText(sb.toString())
        } catch (e: Exception) { Log.w(TAG, "transcript write failed", e) }
    }

    private fun stopEverything() {
        capturing = false
        running = false
        writeTranscript()
        try { record?.stop(); record?.release() } catch (e: Exception) {}
        record = null
        try { projection?.stop() } catch (e: Exception) {}
        projection = null
        try { overlay?.hide() } catch (e: Exception) {}
        try { vad?.release() } catch (e: Exception) {}
        try { asr?.release() } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        capturing = false
        running = false
        scope.cancel()
        super.onDestroy()
    }
}
