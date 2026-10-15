package ro.davidmontei.loom.storage.saves

import java.nio.file.Path

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
}
