package ro.davidmontei.loom.storage.saves

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Everything Loom syncs as one game's save: what it hashes, packs into the one file it uploads,
 * backs up and restores ([SaveRestorer]). The shape depends on how the emulator keeps its saves.
 */
sealed interface SaveUnit {
    /**
     * Where the save is, as Loom records it with the save's scan and baseline: a baseline recorded
     * for another path is of another save.
     */
    val path: String

    /** The folder holding the unit: the card it is on, and where a restore works beside it. */
    val anchor: Path

    /**
     * RomM's content hash of what [pack] would write, without writing it. Null when the unit holds
     * no save.
     *
     * @throws java.io.IOException when the unit can't be read. That is never "no save": try again later.
     */
    fun hash(): String?

    /** @throws java.io.IOException when the unit can't be read: try again later. */
    fun snapshot(): UnitSnapshot

    /**
     * Writes what Loom uploads for the unit to [target] and returns RomM's content hash of it,
     * computed from the bytes as they went in.
     *
     * @throws SaveArchiveException when the unit holds no save.
     */
    fun pack(target: Path): String

    /**
     * Folders zipped under their root names, all in one parent folder (the [anchor]): Azahar's
     * `data`, Eden's title folder.
     */
    data class Folders(val roots: List<SaveRoot>) : SaveUnit {
        init {
            require(roots.isNotEmpty()) { "A save unit has at least one folder" }
        }

        /** The first folder's path, as Loom 0.3 recorded it: its baselines still count. */
        override val path: String get() = roots.first().folder.toString()

        override val anchor: Path get() = roots.first().folder.toAbsolutePath().parent

        override fun hash(): String? = SaveHasher.hashUnit(roots)

        override fun snapshot(): UnitSnapshot = UnitSnapshot.of(roots)

        override fun pack(target: Path): String = SaveZipper.zip(roots, target)
    }

    /**
     * One raw file, uploaded and restored as itself: a DS game's `.sav` in melonDS's save folder.
     * An empty file is no save: melonDS creates one when a game first runs.
     */
    data class SingleFile(val file: Path) : SaveUnit {
        /** Its name, under which a restore stages and journals it. */
        val name: String get() = file.fileName.toString()

        override val path: String get() = file.toString()

        override val anchor: Path get() = file.toAbsolutePath().parent

        /** The MD5 of its bytes, as RomM hashes a raw save; null when it is missing or empty. */
        override fun hash(): String? = if ((sizeOrNull() ?: 0L) == 0L) null else SaveHasher.md5(file)

        override fun snapshot(): UnitSnapshot = UnitSnapshot.ofFile(file)

        /** Copies the file to [target] and returns the MD5 of the bytes copied. */
        override fun pack(target: Path): String {
            if ((sizeOrNull() ?: 0L) == 0L) throw SaveArchiveException("Nothing to upload in $file")
            val md5 = MessageDigest.getInstance("MD5")
            DigestInputStream(Files.newInputStream(file), md5).use { input ->
                Files.newOutputStream(target).use { input.copyTo(it) }
            }
            return md5.digest().toHex()
        }

        /**
         * The file's size; null when there's no file.
         *
         * @throws IOException when something else is in its place (a folder, a link): Loom can't
         *   tell what the save is, which is never "no save".
         */
        private fun sizeOrNull(): Long? {
            val attrs = try {
                Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return null
            }
            if (!attrs.isRegularFile) throw IOException("$file isn't a file")
            return attrs.size()
        }
    }
}
