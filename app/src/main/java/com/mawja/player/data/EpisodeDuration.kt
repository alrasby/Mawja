package com.mawja.player.data

/** Stable elapsed-time labels for episodes, independent of wall-clock time and locale. */
internal fun episodeDurationLabel(durationMs: Long): String {
    if (durationMs < 0) return ""
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    else "${minutes}:${(seconds % 60).toString().padStart(2, '0')}"
}
