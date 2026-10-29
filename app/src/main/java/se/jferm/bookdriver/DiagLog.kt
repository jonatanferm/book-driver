package se.jferm.bookdriver

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-screen + logcat diagnostics. The spike's whole point is to see what Drive actually
 * does, so everything interesting (auth results, HTTP codes, Range headers) lands here.
 */
object DiagLog {
    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(tag: String, msg: String) {
        val ts = synchronized(fmt) { fmt.format(Date()) }
        val line = "$ts [$tag] $msg"
        Log.i("BookDriver", line)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) =
        i(tag, "ERROR $msg${t?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")

    fun clear() = _lines.update { emptyList() }

    fun dump(): String = _lines.value.joinToString("\n")
}
