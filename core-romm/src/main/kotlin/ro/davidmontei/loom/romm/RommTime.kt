package ro.davidmontei.loom.romm

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * RomM's timestamps. Responses carry an offset ("2026-10-13T17:49:45+00:00", sometimes with
 * microseconds); a value without one is UTC, which is how RomM reads what clients send. Going the
 * other way, [Instant.toString] ("…Z") is what Loom sends.
 */
object RommTime {
    /** Null when [raw] isn't an ISO-8601 date-time. */
    fun parse(raw: String): Instant? {
        val text = raw.trim().replace(' ', 'T')
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}
