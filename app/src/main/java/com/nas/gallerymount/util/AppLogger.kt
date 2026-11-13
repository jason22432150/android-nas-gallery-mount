package com.nas.gallerymount.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 應用內環形日誌：供 UI 顯示與 ADB dump，避免依賴螢幕截圖判讀狀態。
 * 注意：勿寫入密碼等敏感內容。
 */
object AppLogger {
    enum class Level { D, I, W, E }

    data class Line(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        fun format(): String {
            val ts = TIME_FMT.format(Date(timeMs))
            return "$ts ${level.name}/$tag: $message"
        }
    }

    private const val MAX_LINES = 500
    private val lock = Any()
    private val buffer = CopyOnWriteArrayList<Line>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) = append(Level.D, tag, message)
    fun i(tag: String, message: String) = append(Level.I, tag, message)
    fun w(tag: String, message: String) = append(Level.W, tag, message)
    fun e(tag: String, message: String) = append(Level.E, tag, message)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            publish()
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        buffer.map { it.format() }
    }

    fun dumpToFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "debug").also { it.mkdirs() }
        val file = File(dir, "app.log")
        file.writeText(snapshot().joinToString("\n") + "\n")
        return file
    }

    private fun append(level: Level, tag: String, message: String) {
        val line = Line(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            buffer.add(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeAt(0)
            }
            publish()
        }
        // 同步到 logcat 方便 adb logcat 過濾
        when (level) {
            Level.D -> android.util.Log.d("NasGallery/$tag", message)
            Level.I -> android.util.Log.i("NasGallery/$tag", message)
            Level.W -> android.util.Log.w("NasGallery/$tag", message)
            Level.E -> android.util.Log.e("NasGallery/$tag", message)
        }
    }

    private fun publish() {
        _lines.update { buffer.map { it.format() } }
    }
}
