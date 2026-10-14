package ro.davidmontei.loom.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/** "14 Oct, 13:05", in the Thor's time zone. */
fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_TIME.withZone(zone).format(Instant.ofEpochMilli(epochMs))

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
