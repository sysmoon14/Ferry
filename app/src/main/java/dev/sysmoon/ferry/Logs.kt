package dev.sysmoon.ferry

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ferry's own log: the last few hundred lines kept in memory, whatever the TV's logcat buffer is
 * doing (some TVs flood a 256 KB buffer within seconds). Served at `GET /api/debug/log` and the
 * tail is attached to the Last crash sensor in Home Assistant. Everything also goes to logcat.
 */
object Logs {
    data class Line(val at: Long, val level: Char, val tag: String, val message: String)

    private const val CAPACITY = 600
    private val lines = ArrayDeque<Line>(CAPACITY)

    fun d(tag: String, message: String) { Log.d(tag, message); add('D', tag, message) }
    fun i(tag: String, message: String) { Log.i(tag, message); add('I', tag, message) }
    fun w(tag: String, message: String, e: Throwable? = null) { Log.w(tag, message, e); add('W', tag, describe(message, e)) }
    fun e(tag: String, message: String, e: Throwable? = null) { Log.e(tag, message, e); add('E', tag, describe(message, e)) }

    private fun describe(message: String, e: Throwable?) =
        if (e == null) message else "$message — ${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(500)

    @Synchronized private fun add(level: Char, tag: String, message: String) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(Line(System.currentTimeMillis(), level, tag, message))
    }

    @Synchronized fun recent(n: Int = CAPACITY): List<Line> = lines.takeLast(n)

    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    fun format(l: Line): String = "${fmt.format(Instant.ofEpochMilli(l.at).atZone(ZoneId.systemDefault()))} ${l.level}/${l.tag}: ${l.message}"
    fun dump(n: Int = CAPACITY): String = recent(n).joinToString("\n", transform = ::format)
}
