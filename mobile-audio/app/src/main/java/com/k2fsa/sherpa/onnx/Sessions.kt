package com.k2fsa.sherpa.onnx

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-session transcripts saved as plain-text files (filesDir/transcripts/<epochMillis>.txt),
 * with an optional custom name kept in SharedPreferences. Cheap to store; revisitable.
 */
object Sessions {
    data class Session(val id: String, val file: File)

    fun dir(c: Context): File = File(c.filesDir, "transcripts").apply { mkdirs() }
    fun fileFor(c: Context, id: String): File = File(dir(c), "$id.txt")

    fun list(c: Context): List<Session> =
        (dir(c).listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }
            .map { Session(it.nameWithoutExtension, it) }

    private fun prefs(c: Context) = c.getSharedPreferences("sessions", Context.MODE_PRIVATE)

    fun defaultName(id: String): String = try {
        SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(id.toLong()))
    } catch (e: Exception) { id }

    fun name(c: Context, id: String): String = prefs(c).getString(id, null) ?: defaultName(id)
    fun setName(c: Context, id: String, n: String) = prefs(c).edit().putString(id, n).apply()

    fun content(c: Context, id: String): String =
        fileFor(c, id).let { if (it.exists()) it.readText().trim() else "" }

    fun delete(c: Context, id: String) {
        fileFor(c, id).delete()
        prefs(c).edit().remove(id).apply()
    }
}
