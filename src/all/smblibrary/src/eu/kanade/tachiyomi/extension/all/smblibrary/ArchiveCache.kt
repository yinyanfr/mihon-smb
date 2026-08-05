package eu.kanade.tachiyomi.extension.all.smblibrary

import keiyoushi.utils.applicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ArchiveCache(
    private val repository: SmbRepository,
    private val maxBytes: () -> Long = { DEFAULT_MAX_BYTES },
    private val archiveDirectory: File? = null,
) {
    private val archiveDir: File by lazy {
        (archiveDirectory ?: File(applicationContext.cacheDir, "smb-library/archives")).also { it.mkdirs() }
    }
    private val downloadLocks = Array(DOWNLOAD_LOCK_COUNT) { Any() }
    private val cleanupLock = Any()

    fun getOrDownload(config: SmbConfig, fingerprint: ArchiveFingerprint): File {
        synchronized(downloadLock(fingerprint.cacheKey)) {
            val target = cacheFile(fingerprint)
            if (target.exists() && target.length() == fingerprint.size) {
                target.setLastModified(System.currentTimeMillis())
                return target
            }
            if (target.exists() && !target.delete()) {
                throw SmbLibraryException.ZipDownloadInterrupted(
                    fingerprint.relativePath,
                    IOException("Unable to replace invalid archive cache file."),
                )
            }

            archiveDir.mkdirs()
            cleanup(requiredBytes = fingerprint.size, protectedFile = target)
            val tmp = File(archiveDir, "${fingerprint.cacheKey}.tmp")
            tmp.delete()
            try {
                val downloadedSize = repository.openFile(config, fingerprint.relativePath).use { remote ->
                    FileOutputStream(tmp).use { output ->
                        remote.inputStream.use { input ->
                            input.copyTo(output, DOWNLOAD_BUFFER_SIZE).also {
                                output.fd.sync()
                            }
                        }
                    }
                }
                val current = repository.metadata(config, fingerprint.relativePath)
                ArchiveDownloadValidator.validate(fingerprint, downloadedSize, current)
                moveIntoPlace(tmp, target)
                target.setLastModified(System.currentTimeMillis())
                cleanup(requiredBytes = 0L, protectedFile = target)
                return target
            } catch (e: IOException) {
                tmp.delete()
                if (e.isNoSpaceError()) throw SmbLibraryException.CacheFull(e)
                throw SmbLibraryException.ZipDownloadInterrupted(fingerprint.relativePath, e)
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }
    }

    fun listImageEntries(archiveFile: File, archivePath: String): List<ArchivePageEntry> {
        try {
            ZipFile(archiveFile).use { zip ->
                return NaturalSort.sortedBy(
                    zip.entries().asSequence()
                        .filter(ContentDetector::isReadableZipImage)
                        .map {
                            ArchivePageEntry(
                                name = it.name,
                                size = it.size,
                                lastModifiedMillis = it.time.coerceAtLeast(0L),
                            )
                        }
                        .toList(),
                ) { it.name }
            }
        } catch (e: ZipException) {
            throw e.asArchiveError(archivePath)
        } catch (e: IOException) {
            throw SmbLibraryException.ZipBroken(archivePath, e)
        }
    }

    fun openEntry(archiveFile: File, entryName: String, archivePath: String): ZipEntryHandle {
        if (!PathCodec.isSafeRelativePath(entryName)) throw SmbLibraryException.UnsafePath(entryName)
        var zip: ZipFile? = null
        try {
            zip = ZipFile(archiveFile)
            val entry = zip.getEntry(entryName)
                ?: throw SmbLibraryException.FileRemoved("$archivePath!/$entryName")
            if (!ContentDetector.isReadableZipImage(entry)) throw SmbLibraryException.UnsupportedImage(entryName)
            val inputStream = zip.getInputStream(entry)
            return ZipEntryHandle(zip, inputStream).also { zip = null }
        } catch (e: ZipException) {
            throw e.asArchiveError(archivePath)
        } catch (e: IOException) {
            throw if (e.isEncryptedZipError()) {
                SmbLibraryException.ZipEncrypted(archivePath, e)
            } else {
                SmbLibraryException.ZipBroken(archivePath, e)
            }
        } finally {
            zip?.close()
        }
    }

    fun cacheFile(fingerprint: ArchiveFingerprint): File = File(archiveDir, "${fingerprint.cacheKey}.zip")

    internal fun cleanup(protectedFile: File) = cleanup(requiredBytes = 0L, protectedFile = protectedFile)

    private fun cleanup(requiredBytes: Long, protectedFile: File) {
        synchronized(cleanupLock) {
            val files = archiveDir.listFiles { file -> file.isFile && file.extension == "zip" }.orEmpty()
            val limit = maxBytes().coerceAtLeast(1L)
            var total = files.sumOf { it.length() }
            val targetTotal = if (requiredBytes >= limit) 0L else limit - requiredBytes
            if (total <= targetTotal) return

            files.asSequence()
                .filterNot { it.absolutePath == protectedFile.absolutePath }
                .sortedBy { it.lastModified() }
                .forEach { file ->
                    if (total <= targetTotal) return
                    val size = file.length()
                    if (file.delete()) total -= size
                }
        }
    }

    private fun downloadLock(cacheKey: String): Any = downloadLocks[(cacheKey.hashCode() and Int.MAX_VALUE) % downloadLocks.size]

    private fun moveIntoPlace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun IOException.isNoSpaceError(): Boolean = generateSequence(this as Throwable?) { it.cause }
        .mapNotNull { it.message }
        .any { it.contains("no space", ignoreCase = true) || it.contains("ENOSPC", ignoreCase = true) }

    private fun IOException.isEncryptedZipError(): Boolean = message?.contains("encrypt", ignoreCase = true) == true

    private fun ZipException.asArchiveError(path: String): SmbLibraryException = if (isEncryptedZipError()) {
        SmbLibraryException.ZipEncrypted(path, this)
    } else {
        SmbLibraryException.ZipBroken(path, this)
    }

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE = 1024 * 1024
        const val DOWNLOAD_LOCK_COUNT = 32
        const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L
    }
}

object ArchiveDownloadValidator {
    fun validate(fingerprint: ArchiveFingerprint, downloadedSize: Long, current: RemoteEntry) {
        val changed = downloadedSize != fingerprint.size ||
            current.size != fingerprint.size ||
            current.lastModifiedMillis != fingerprint.lastModifiedMillis
        if (changed) {
            throw SmbLibraryException.ZipDownloadInterrupted(
                fingerprint.relativePath,
                IOException("Remote archive changed during download. Refresh the chapter list and try again."),
            )
        }
    }
}

data class ArchivePageEntry(
    val name: String,
    val size: Long,
    val lastModifiedMillis: Long,
)

class ZipEntryHandle(
    private val zipFile: ZipFile,
    val inputStream: java.io.InputStream,
) : java.io.Closeable {
    override fun close() {
        try {
            inputStream.close()
        } finally {
            zipFile.close()
        }
    }
}
