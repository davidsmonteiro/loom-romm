package ro.davidmontei.loom.storage.engine

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

/** Incremental digest for the algorithms RomM records: "sha1", "md5" or "crc32". */
class Hasher(val algo: String) {
    private val digest: MessageDigest? = when (algo) {
        "sha1" -> MessageDigest.getInstance("SHA-1")
        "md5" -> MessageDigest.getInstance("MD5")
        "crc32" -> null
        else -> throw IllegalArgumentException("Unsupported hash $algo")
    }
    private val crc = if (digest == null) CRC32() else null

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        digest?.update(bytes, offset, length)
        crc?.update(bytes, offset, length)
    }

    fun update(buffer: ByteBuffer) {
        digest?.update(buffer.duplicate())
        crc?.update(buffer.duplicate())
    }

    fun hex(): String = digest?.digest()?.toHex() ?: "%08x".format(crc!!.value)

    companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
