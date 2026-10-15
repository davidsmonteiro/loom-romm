package ro.davidmontei.loom.romm.socket

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/** The first wait before connecting again after a connection ends. */
const val RECONNECT_FIRST_WAIT_MS = 1_000L

/** The longest wait before connecting again. */
const val RECONNECT_MAX_WAIT_MS = 60_000L

/**
 * Every event of every connection [connect] makes, connecting again each time one ends: after 1 s,
 * then twice as long each time up to 60 s, each wait shortened or lengthened by up to half at random so
 * devices dropped together don't all come back together. A connection RomM accepts starts the waits
 * over. A refusal ([DeviceEvent.Refused]) ends it all: trying again wouldn't change RomM's answer.
 * [delay] and [random] are the test's to set.
 */
fun reconnecting(
    connect: () -> Flow<DeviceEvent>,
    random: Random = Random.Default,
    delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): Flow<DeviceEvent> = flow {
    var wait = RECONNECT_FIRST_WAIT_MS
    while (true) {
        var refused = false
        connect()
            .catch { emit(DeviceEvent.Dropped(it)) } // a connection that couldn't even start is dropped too
            .collect { event ->
                emit(event)
                when (event) {
                    DeviceEvent.Connected -> wait = RECONNECT_FIRST_WAIT_MS
                    is DeviceEvent.Refused -> refused = true
                    else -> Unit
                }
            }
        if (refused) return@flow
        delay(jittered(wait, random))
        wait = (wait * 2).coerceAtMost(RECONNECT_MAX_WAIT_MS)
    }
}

/** [wait] ± up to half of it, never past [RECONNECT_MAX_WAIT_MS]. */
internal fun jittered(wait: Long, random: Random): Long =
    (wait * (0.5 + random.nextDouble())).toLong().coerceAtMost(RECONNECT_MAX_WAIT_MS)
