package ro.davidmontei.loom.storage.saves

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The Switch's NCA header key (`header_key` in a `prod.keys` dump): 32 bytes, the two AES-128 keys
 * of the AES-XTS that encrypts every NCA's header. It is the owner's, dumped from their own
 * console. Loom reads it from the card only when it has a game's title ID to find, holds it in
 * memory for that one read, and [close] drops it: Loom zeroes its own copy and re-keys its
 * ciphers, though copies the JCE made on the way (in its key specs and providers) only go when
 * they're collected. It never becomes text: not in [toString], a log, an exception, or anything
 * Loom stores or uploads.
 */
class SwitchHeaderKey private constructor(private val key: ByteArray) : AutoCloseable {
    private var ciphers: Pair<Cipher, Cipher>? = null
    private var closed = false

    /** The ciphers made from the key, once a header has been decrypted; for tests of [close]. */
    internal val ciphersInUse: Pair<Cipher, Cipher>? get() = ciphers

    /**
     * Decrypts [length] bytes of [data] in place, from the start of an NCA: Nintendo's AES-XTS over
     * sectors of [SECTOR_SIZE] bytes numbered from 0, each tweak the sector number as a 16-byte
     * big-endian integer. [length] is a whole number of sectors.
     */
    internal fun decryptNcaHeader(data: ByteArray, length: Int) {
        require(length % SECTOR_SIZE == 0 && length <= data.size) { "Decrypt whole sectors" }
        val (dataCipher, tweakCipher) = ciphers ?: run {
            check(!closed) { "This key was closed" }
            val decrypt = Cipher.getInstance(AES_ECB).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, 16, "AES")) }
            val encrypt = Cipher.getInstance(AES_ECB).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 16, 16, "AES")) }
            (decrypt to encrypt).also { ciphers = it }
        }
        for (sector in 0 until length / SECTOR_SIZE) {
            val tweak = ByteArray(16)
            for (i in 0 until 8) tweak[15 - i] = (sector.toLong() ushr (8 * i)).toByte()
            Xts.decrypt(dataCipher, tweakCipher, tweak, data, sector * SECTOR_SIZE, SECTOR_SIZE)
        }
    }

    /**
     * Drops the key: zeroes Loom's copy and, as far as the JCE lets it, the ciphers' (re-keyed with
     * zeros before they are let go).
     */
    override fun close() {
        key.fill(0)
        ciphers?.let { (decrypt, encrypt) ->
            runCatching {
                val zero = SecretKeySpec(ByteArray(16), "AES")
                decrypt.init(Cipher.DECRYPT_MODE, zero)
                encrypt.init(Cipher.ENCRYPT_MODE, zero)
            }
        }
        ciphers = null
        closed = true
    }

    override fun toString(): String = "SwitchHeaderKey(hidden)"

    companion object {
        const val SIZE = 32
        const val SECTOR_SIZE = 0x200
        private const val AES_ECB = "AES/ECB/NoPadding"

        /** A prod.keys file is a few kilobytes; anything far larger isn't one. */
        private const val MAX_KEYS_FILE_BYTES = 1L shl 20
        private val NAME = "header_key".toByteArray(Charsets.US_ASCII)

        /** A copy of [bytes], which must be [SIZE] long. */
        fun of(bytes: ByteArray): SwitchHeaderKey {
            require(bytes.size == SIZE) { "A header key is $SIZE bytes" }
            return SwitchHeaderKey(bytes.copyOf())
        }

        /**
         * Reads `header_key` from [file], a prod.keys dump: lines of `name = hex`. Null when it has
         * none Loom can use (absent, not 64 hex digits). The file's bytes are wiped once read.
         *
         * @throws IOException when the file can't be read; the message names the file, never its contents.
         */
        fun fromProdKeys(file: Path): SwitchHeaderKey? {
            if (Files.size(file) > MAX_KEYS_FILE_BYTES) return null
            val raw = Files.readAllBytes(file)
            try {
                return parse(raw)
            } finally {
                raw.fill(0)
            }
        }

        /** The `header_key` line of a prod.keys file's bytes, parsed without making text of them. */
        internal fun parse(raw: ByteArray): SwitchHeaderKey? {
            var start = 0
            while (start < raw.size) {
                var end = start
                while (end < raw.size && raw[end] != '\n'.code.toByte()) end++
                parseLine(raw, start, end)?.let { return it }
                start = end + 1
            }
            return null
        }

        private fun parseLine(raw: ByteArray, start: Int, end: Int): SwitchHeaderKey? {
            var i = skipBlanks(raw, start, end)
            if (end - i < NAME.size || NAME.indices.any { raw[i + it] != NAME[it] }) return null
            i = skipBlanks(raw, i + NAME.size, end)
            if (i >= end || raw[i] != '='.code.toByte()) return null // e.g. header_key_source
            i = skipBlanks(raw, i + 1, end)
            if (end - i < SIZE * 2) return null
            val key = ByteArray(SIZE)
            for (n in 0 until SIZE) {
                val high = hexDigit(raw[i + 2 * n])
                val low = hexDigit(raw[i + 2 * n + 1])
                if (high < 0 || low < 0) {
                    key.fill(0)
                    return null
                }
                key[n] = ((high shl 4) or low).toByte()
            }
            if (skipBlanks(raw, i + SIZE * 2, end) != end) {
                key.fill(0)
                return null
            }
            return SwitchHeaderKey(key)
        }

        private fun skipBlanks(raw: ByteArray, from: Int, end: Int): Int {
            var i = from
            while (i < end && (raw[i] == ' '.code.toByte() || raw[i] == '\t'.code.toByte() || raw[i] == '\r'.code.toByte())) i++
            return i
        }

        private fun hexDigit(b: Byte): Int = when (val c = b.toInt().toChar()) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}

/**
 * AES-XTS decryption (IEEE 1619) built on AES/ECB, which every JCE provider has; the JCE itself has
 * no XTS. For each 16-byte block: P = D(k1, C xor T) xor T, then T = T times alpha in GF(2^128).
 */
internal object Xts {
    private const val BLOCK = 16

    /**
     * Decrypts one data unit of [data] in place ([length] bytes from [offset], a multiple of 16).
     * [tweak] is the 16 bytes encrypted with key 2: how a data unit's number becomes those bytes is
     * the caller's (Nintendo writes it big-endian, IEEE 1619 little-endian).
     */
    fun decrypt(dataCipher: Cipher, tweakCipher: Cipher, tweak: ByteArray, data: ByteArray, offset: Int, length: Int) {
        require(tweak.size == BLOCK && length % BLOCK == 0) { "XTS works on whole 16-byte blocks here" }
        val t = tweakCipher.doFinal(tweak)
        val block = ByteArray(BLOCK)
        for (at in offset until offset + length step BLOCK) {
            for (i in 0 until BLOCK) block[i] = (data[at + i].toInt() xor t[i].toInt()).toByte()
            val plain = dataCipher.doFinal(block)
            for (i in 0 until BLOCK) data[at + i] = (plain[i].toInt() xor t[i].toInt()).toByte()
            timesAlpha(t)
        }
        block.fill(0)
    }

    /** Multiplies [t] by alpha (x) in GF(2^128), little-endian as XTS defines it, reducing by 0x87. */
    private fun timesAlpha(t: ByteArray) {
        var carry = 0
        for (i in 0 until BLOCK) {
            val b = t[i].toInt() and 0xFF
            t[i] = ((b shl 1) or carry).toByte()
            carry = b ushr 7
        }
        if (carry != 0) t[0] = (t[0].toInt() xor 0x87).toByte()
    }
}
