package ro.davidmontei.loom.storage.saves

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The trailer Argosy appends to a raw save it uploads from a game played in hardcore mode
 * (`SaveArchiver.appendHardcoreTrailer`, v2.21 and v2.22): 16 bytes of JSON, `{"h":true,"v":1}`,
 * its length (u32 LE) and the magic `ARGOSY 01 00`, 28 bytes in all. melonDS would read them as
 * part of the save, so they come off before a restore, as Argosy takes them off its own downloads.
 *
 * Whether a file has one is decided exactly as Argosy's `getTrailerSize` does, without looking at
 * the JSON: the file is at least 12 bytes, its last 8 are the magic, and the length before them is
 * 0 to 1024 and fits in the file. RomM's hash of such a save is of the bytes with the trailer.
 */
object ArgosyTrailer {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'R'.code.toByte(), 'G'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte(), 'Y'.code.toByte(), 1, 0)
    private const val MIN_SIZE = 12L
    private const val MAX_JSON_BYTES = 1024

    /**
     * The trailer's size at the end of [file]; 0 when it has none.
     *
     * @throws java.io.IOException when the file can't be read.
     */
    fun size(file: Path): Long = FileChannel.open(file, StandardOpenOption.READ).use { size(it) }

    /**
     * Cuts the trailer off [file], in place, and returns its size; 0, with the file untouched,
     * when there is none. A file that was only a trailer is left empty, which is no save.
     *
     * @throws java.io.IOException when the file can't be read or written.
     */
    fun strip(file: Path): Long = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
        size(channel).also { if (it > 0) channel.truncate(channel.size() - it) }
    }

    internal fun size(channel: FileChannel): Long {
        val length = channel.size()
        if (length < MIN_SIZE) return 0
        val tail = readAt(channel, length - MIN_SIZE, MIN_SIZE.toInt()) ?: return 0
        if (MAGIC.indices.any { tail.get(4 + it) != MAGIC[it] }) return 0
        val jsonLength = tail.getInt(0)
        if (jsonLength !in 0..MAX_JSON_BYTES || length < MIN_SIZE + jsonLength) return 0
        return MIN_SIZE + jsonLength
    }
}

/**
 * Whether [file] is a DeSmuME save (`.dsv`): the raw save followed by a 122-byte footer ending in
 * `|-DESMUME SAVE-|`, which melonDS would read as part of the save. Argosy and RetroArch upload
 * DeSmuME's saves as they are. A trailer Argosy appended after the footer doesn't hide it.
 *
 * @throws java.io.IOException when the file can't be read.
 */
fun isDesmumeSave(file: Path): Boolean = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
    val end = channel.size() - ArgosyTrailer.size(channel)
    if (end < DESMUME_COOKIE.size) return@use false
    val tail = readAt(channel, end - DESMUME_COOKIE.size, DESMUME_COOKIE.size) ?: return@use false
    DESMUME_COOKIE.indices.all { tail.get(it) == DESMUME_COOKIE[it] }
}

private val DESMUME_COOKIE = "|-DESMUME SAVE-|".toByteArray(Charsets.US_ASCII)

/** [length] bytes at [at], little-endian; null when the file ends first. */
private fun readAt(channel: FileChannel, at: Long, length: Int): ByteBuffer? {
    val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    var read = 0
    while (read < length) {
        val n = channel.read(buffer, at + read)
        if (n < 0) return null
        read += n
    }
    return buffer
}
