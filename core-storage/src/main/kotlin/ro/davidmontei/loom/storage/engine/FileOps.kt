package ro.davidmontei.loom.storage.engine

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** The rename failed because source and destination are on different mounts (EXDEV). */
class CrossDeviceException(cause: Throwable) : IOException("Cross-device move", cause)

/**
 * File-system operations the engine relies on, behind an interface so tests can inject EXDEV,
 * ENOSPC and crashes at exact points.
 */
interface FileOps {
    /** Single rename(2): either the whole entry appears at [target] or nothing changes. */
    fun atomicMove(source: Path, target: Path)

    fun copyRecursively(source: Path, target: Path)
    fun deleteRecursively(path: Path)
    fun createDirectories(path: Path)
    fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    fun writeText(path: Path, text: String)
    fun sizeOf(path: Path): Long
}

open class NioFileOps : FileOps {
    override fun atomicMove(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            throw CrossDeviceException(e)
        } catch (e: IOException) {
            if (e.message?.contains("EXDEV") == true || e.message?.contains("cross-device", ignoreCase = true) == true) {
                throw CrossDeviceException(e)
            }
            throw e
        }
    }

    override fun copyRecursively(source: Path, target: Path) {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
            return
        }
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, target.resolve(source.relativize(file).toString()))
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun createDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun writeText(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.write(path, text.toByteArray(Charsets.UTF_8))
    }

    override fun sizeOf(path: Path): Long {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return Files.size(path)
        var total = 0L
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size()
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }
}

/** Free space on the volume holding a path (StatFs on Android, File.usableSpace in tests). */
fun interface SpaceProbe {
    fun freeBytes(path: Path): Long
}
