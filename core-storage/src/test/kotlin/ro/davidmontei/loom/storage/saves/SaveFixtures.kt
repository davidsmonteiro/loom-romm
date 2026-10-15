package ro.davidmontei.loom.storage.saves

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Test zips and save folders. */
object SaveFixtures {
    /**
     * Writes a zip with exactly these entries, in this order; a null body is a directory entry.
     * Commons Compress writes what it's given, duplicates and "../" names included.
     */
    fun zipOf(target: Path, vararg entries: Pair<String, ByteArray?>): Path {
        ZipArchiveOutputStream(Files.newOutputStream(target)).use { zip ->
            for ((name, body) in entries) {
                zip.putArchiveEntry(ZipArchiveEntry(name))
                body?.let(zip::write)
                zip.closeArchiveEntry()
            }
        }
        return target
    }

    /** A zip holding one symlink entry pointing at [linkTarget]. */
    fun zipWithSymlink(target: Path, name: String, linkTarget: String): Path {
        ZipArchiveOutputStream(Files.newOutputStream(target)).use { zip ->
            val entry = ZipArchiveEntry(name).apply { unixMode = "120777".toInt(8) } // S_IFLNK | 0777
            zip.putArchiveEntry(entry)
            zip.write(linkTarget.toByteArray())
            zip.closeArchiveEntry()
        }
        return target
    }

    /** Writes [files] (relative path to bytes) under [dir] and returns [dir]. */
    fun folderOf(dir: Path, vararg files: Pair<String, ByteArray>): Path {
        Files.createDirectories(dir)
        for ((rel, body) in files) {
            val file = dir.resolve(rel)
            Files.createDirectories(file.parent)
            Files.write(file, body)
        }
        return dir
    }

    /** Omega Ruby's save as Azahar writes it: `00000001/main` beside `00000001.metadata`. */
    val AZAHAR_MAIN = ByteArray(1024) { (it % 256).toByte() }
    val AZAHAR_METADATA = "META".toByteArray() + ByteArray(28)

    /** Python's `zipfile` hash of `data/00000001/main` + `data/00000001.metadata` above. */
    const val AZAHAR_UNIT_HASH = "c43ef632a43368c6ec5e23a4e9653a50"

    /**
     * A PARAM.SFO holding [entries] in this order, laid out as PPSSPP writes one: the header, the
     * index, the keys, then the values, each padded to 4 bytes. A String is text (format 0x0204,
     * with its NUL), an Int a u32 (0x0404), a ByteArray raw bytes (0x0004).
     */
    fun sfoOf(vararg entries: Pair<String, Any>): ByteArray {
        val keys = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val index = ByteBuffer.allocate(16 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        for ((key, value) in entries) {
            val (format, bytes) = when (value) {
                is String -> 0x0204 to value.toByteArray() + byteArrayOf(0)
                is Int -> 0x0404 to ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, value).array()
                is ByteArray -> 0x0004 to value
                else -> error("No SFO format for $value")
            }
            val room = (bytes.size + 3) / 4 * 4
            index.putShort(keys.size().toShort()).putShort(format.toShort()).putInt(bytes.size).putInt(room).putInt(data.size())
            keys.write(key.toByteArray() + byteArrayOf(0))
            data.write(bytes.copyOf(room))
        }
        while (keys.size() % 4 != 0) keys.write(0)
        val keyStart = 0x14 + 16 * entries.size
        val header = ByteBuffer.allocate(0x14).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46535000).putInt(0x0101).putInt(keyStart).putInt(keyStart + keys.size()).putInt(entries.size)
        return header.array() + index.array() + keys.toByteArray() + data.toByteArray()
    }

    /** A DS game's raw `.sav`, 8 KiB as a small cartridge's save is. */
    val RAW_SAVE = ByteArray(8192) { ((it * 31 + 7) % 251).toByte() }

    /** `hashlib.md5(bytes((i * 31 + 7) % 251 for i in range(8192))).hexdigest()`: RomM's hash of [RAW_SAVE]. */
    const val RAW_SAVE_MD5 = "5756fb1d2eb2a0bd1a8dfeb93648c920"
}
