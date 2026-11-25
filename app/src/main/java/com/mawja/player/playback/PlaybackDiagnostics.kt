package com.mawja.player.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.ArrayDeque

/** In-memory breadcrumbs contain codes only, never signed stream URLs or user media titles. */
object PlaybackDiagnostics {
    private val events = ArrayDeque<String>()
    @Synchronized fun record(message: String) {
        if (events.size >= 40) events.removeFirst()
        events.addLast("${android.os.SystemClock.elapsedRealtime()} $message")
    }
    @Synchronized private fun recent() = events.joinToString("\n")
    /** Invoke on IO; historical system ANR traces may not be present on all devices. */
    fun report(context: Context): String = buildString {
        appendLine("Mawja ${com.mawja.player.BuildConfig.VERSION_CODE}; Android ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine(recent())
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            for (exit in manager.getHistoricalProcessExitReasons(context.packageName, 0, 3)) {
                appendLine("exit reason=${exit.reason}; time=${exit.timestamp}; importance=${exit.importance}")
                if (exit.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                    exit.traceInputStream?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(24_000)
                        var total = 0
                        while (total < buffer.size) {
                            val count = reader.read(buffer, total, buffer.size - total)
                            if (count < 0) break
                            total += count
                        }
                        appendLine(String(buffer, 0, total))
                    }
                }
            }
        }.onFailure { appendLine("System trace unavailable: ${it.javaClass.simpleName}") }
    }
}
