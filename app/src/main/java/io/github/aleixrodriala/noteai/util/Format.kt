package io.github.aleixrodriala.noteai.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** 0:07, 12:34, 1:02:03 */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** Compact length for list rows: "45s", "12m", "1h 5m". */
fun formatShortDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return when {
        total < 60 -> "${total}s"
        total < 3600 -> "${total / 60}m"
        else -> "${total / 3600}h ${(total % 3600) / 60}m"
    }
}

private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val fullDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
private val longFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)

/** "Today · 10:42", "Mon · 10:42", "Mar 2 · 10:42", "Mar 2, 2025 · 10:42". */
fun formatListDate(epochMs: Long, today: LocalDate = LocalDate.now()): String {
    val dt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val d = dt.toLocalDate()
    val day = when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today.minusDays(7)) -> dayFmt.format(dt)
        d.year == today.year -> dateFmt.format(dt)
        else -> fullDateFmt.format(dt)
    }
    return "$day · ${timeFmt.format(dt)}"
}

fun formatLongDate(epochMs: Long): String =
    longFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0)
}
