package ro.davidmontei.loom.data.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import ro.davidmontei.loom.storage.STAGING_DIR_NAME
import java.io.File

/** A mounted storage volume as Loom sees it. */
data class VolumeInfo(
    /** Stable id: the filesystem UUID ("9C33-6BBD") for SD cards, "primary" for internal storage. */
    val id: String,
    val root: File,
    val label: String,
    val removable: Boolean,
) {
    val stagingRoot: File get() = File(root, STAGING_DIR_NAME)
}

/** What reconcile and offload need to know about volumes (faked in tests). */
interface Volumes {
    fun forPath(path: String): VolumeInfo?
    fun isMounted(volumeId: String): Boolean
}

/** Storage volumes, free space, and whether Loom may write to them (all-files access). */
class StorageVolumes(private val context: Context) : Volumes {
    private val storage = context.getSystemService(StorageManager::class.java)

    fun mounted(): List<VolumeInfo> = storage.storageVolumes
        .filter { it.state == Environment.MEDIA_MOUNTED }
        .mapNotNull { it.toInfo() }

    /** The volume holding [path], or null if it isn't on a mounted volume. */
    override fun forPath(path: String): VolumeInfo? {
        val file = File(path)
        val volume = storage.getStorageVolume(file) ?: return null
        if (volume.state != Environment.MEDIA_MOUNTED) return null
        return volume.toInfo()
    }

    override fun isMounted(volumeId: String): Boolean = mounted().any { it.id == volumeId }

    fun freeBytes(path: File): Long = StatFs(path.existingAncestor().path).availableBytes

    fun totalBytes(path: File): Long = StatFs(path.existingAncestor().path).totalBytes

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Default ROMs root: `<SD card>/ROMs` when one exists, else internal `ROMs`. */
    fun defaultRomsRoot(): File {
        val sd = mounted().firstOrNull { it.removable }
        val candidates = listOfNotNull(sd?.let { File(it.root, "ROMs") }, File(Environment.getExternalStorageDirectory(), "ROMs"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }

    private fun StorageVolume.toInfo(): VolumeInfo? {
        val dir = directory ?: return null
        val id = if (isPrimary) "primary" else (uuid ?: dir.name)
        return VolumeInfo(id, dir, getDescription(context) ?: dir.name, isRemovable)
    }

    private fun File.existingAncestor(): File {
        var f: File? = this
        while (f != null && !f.exists()) f = f.parentFile
        return f ?: this
    }
}
